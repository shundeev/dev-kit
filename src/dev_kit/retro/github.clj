;; The GitHub half of a retrospective's data (see bin/retro.clj).
(ns dev-kit.retro.github
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.retro.log :as log]))


;; ------------------------------------------------------------------ GitHub


(def query
  (str
   "query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){issue(number:$number){"
   "labels(first:50){nodes{name}}"
   " timelineItems(itemTypes:[LABELED_EVENT],last:50){nodes{... on LabeledEvent{actor{login} label{name} createdAt}}}"
   " comments(first:100){nodes{body createdAt}}"
   " closedByPullRequestsReferences(first:5,includeClosedPrs:true){nodes{number createdAt mergedAt additions deletions"
   " changedFiles commits(first:100){nodes{commit{statusCheckRollup{state}}}}"
   " reviews(first:100){nodes{author{login} state submittedAt}}"
   " timelineItems(itemTypes:[REVIEW_DISMISSED_EVENT,READY_FOR_REVIEW_EVENT],last:50){nodes{__typename"
   " ... on ReviewDismissedEvent{createdAt} ... on ReadyForReviewEvent{createdAt}}}"
   " comments(first:100){nodes{body}}}}}}}"))


(defn nodes [parent & path] (get-in parent (concat path ["nodes"])))


(defn github-lines
  [repo number agent]
  (let [[owner repository] (str/split repo #"/")
        issue     (or (get-in (json/parse-string (base/gh! "api" "graphql"
                                                   "-f" (str "query=" query)
                                                   "-f" (str "owner=" owner)
                                                   "-f" (str "name=" repository)
                                                   "-F" (str "number=" number)))
                              ["data" "repository" "issue"])
                      (throw (ex-info (str repo "#" number " not found") {})))
        size      (some #(second (re-matches #"size:(XS|S|M|L|XL)" (get % "name"))) (nodes issue "labels"))
        design    (->> (nodes issue "comments")
                       (filter #(str/starts-with? (str (get % "body")) "## Design"))
                       (map #(log/instant (get % "createdAt")))
                       sort
                       first)
        approvals (->> (nodes issue "timelineItems")
                       (filter #(and (= "design-approved" (get-in % ["label" "name"]))
                                     (not= agent (get-in % ["actor" "login"]))))
                       (map #(log/instant (get % "createdAt")))
                       sort)
        after     (when design
                    (first (remove #(log/before? % design) approvals)))
        all-prs   (nodes issue "closedByPullRequestsReferences")]
    (cons
     (str "- Design approval: "
          (cond after  (str (count approvals) ", " (log/span design after) " after the first `## Design` comment")
                (and design (seq approvals)) (str (count approvals) ", all before the first `## Design` comment")
                design "the `## Design` comment was never approved"
                :else  (str "none (no `## Design` comment"
                            (when size
                              (str "; Size " size))
                            ")")))
     (for [pr   (or (seq (filter #(get % "mergedAt") all-prs)) all-prs)
           :let [reviews  (remove #(= agent (get-in % ["author" "login"])) (nodes pr "reviews"))
                 approved (sort (keep #(when (= "APPROVED" (get % "state")) (log/instant (get % "submittedAt")))
                                      reviews))
                 events   (nodes pr "timelineItems")
                 ready-ev (last (sort (keep #(when (= "ReadyForReviewEvent" (get % "__typename"))
                                               (log/instant (get % "createdAt")))
                                            events)))
                 ready    (or ready-ev (log/instant (get pr "createdAt")))
                 approve  (first (remove #(log/before? % ready) approved))
                 merged   (log/instant (get pr "mergedAt"))
                 rollups  (keep #(get-in % ["commit" "statusCheckRollup" "state"]) (nodes pr "commits"))
                 steps    (filter #(re-find #"(?m)^##\s*(Что сделать руками|Owner steps)" (str (get % "body")))
                                  (nodes pr "comments"))]]
       (str/join "\n"
                 [(str "- PR #" (get pr "number")
                       ": +" (get pr "additions")
                       " −" (get pr "deletions")
                       " in "
                       (get pr "changedFiles")
                       " files, " (count (nodes pr "commits"))
                       " commits; Size " (or size "not set"))
                  (str "- Review rounds with comments: "
                       (count (filter #(#{"COMMENTED" "CHANGES_REQUESTED"} (get % "state")) reviews))
                       "; approvals: " (count approved)
                       "; dismissed: " (count (filter #(= "ReviewDismissedEvent" (get % "__typename")) events)))
                  (str "- Waiting on the owner: PR "
                       (if ready-ev "ready" "opened")
                       " → Approve "
                       (if approve
                         (log/span ready approve)
                         "no approval")
                       (when (and approve merged)
                         (str "; Approve → merged " (log/span approve merged))))
                  (str "- CI: " (count (filter #{"FAILURE" "ERROR"} rollups)) " of " (count rollups) " commits failed")
                  (str "- Owner steps: "
                       (if (seq steps)
                         (str (count steps)
                              " comment"
                              (when (< 1 (count steps)) "s")
                              ", "
                              (reduce + (map #(count (re-seq #"(?m)^\d+\.\s" (str (get % "body")))) steps))
                              " steps")
                         "none"))])))))
