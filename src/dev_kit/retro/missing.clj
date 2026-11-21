;; The gate: merged tasks still lacking their retrospective (see bin/retro.clj).
(ns dev-kit.retro.missing
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.problem :as problem]
   [dev-kit.retro.github :as github]
   [dev-kit.retro.log :as log])
  (:import
   (java.time Duration Instant)))


;; ------------------------------------------------------------------ the gate (#56)
;; New work waits while a task merged in the last 14 days lacks its `## Retrospective` comment: the start script
;; and the session's start ask this, and only the owner can waive it.


(def missing-query
  (str
   "query($q:String!){search(query:$q,type:ISSUE,first:50){nodes{... on Issue{number title"
   " comments(last:100){nodes{body}} closedByPullRequestsReferences(first:5,includeClosedPrs:true){nodes{mergedAt}}}}}}"))


(defn missing
  "[number title] of the issues closed as completed by a merged PR since `since` that lack a retrospective."
  [gh repo ^Instant since]
  (let [q (str "repo:" repo " is:issue is:closed reason:completed closed:>=" (subs (str since) 0 10))]
    (for [i     (get-in (json/parse-string (gh "api" "graphql" "-f" (str "query=" missing-query) "-f" (str "q=" q)))
                        ["data" "search" "nodes"])
          :when (and (some #(get % "mergedAt") (github/nodes i "closedByPullRequestsReferences"))
                     (not-any? #(str/starts-with? (str/trim (str (get % "body"))) "## Retrospective")
                               (github/nodes i "comments")))]
      [(get i "number") (get i "title")])))


(defn run-missing
  "Nil and silence when every merged issue has its retrospective; otherwise throws the plain problem with the list and the
  fix (exit 1), or, when GitHub cannot be asked, the problem with exit 2."
  [{:keys [repo gh now]}]
  (let [now   (if now
                (log/parse-time "--now" now)
                (Instant/now))
        found (try (doall (missing gh repo (.minus ^Instant now (Duration/ofDays 14))))
                   (catch Exception e
                     (problem/fail! :retro/github-unavailable
                                    (str "retro: GitHub not available: " (ex-message e))
                                    {:fix    "check gh auth status and the network, then run kit retro --missing again"
                                     :stderr true
                                     :exit   2})))]
    (when (seq found)
      (problem/fail!
       :retro/missing-retrospective
       (str "Merged in the last 14 days without a `## Retrospective` comment: "
            (str/join ", " (for [[n t] found] (str "#" n " " t)))
            ".")
       {:fix   (str
                "Post each one's retrospective before new work (repo-task-delivery, stage 11); its data comes from"
                " kit retro <n>, e.g. kit retro "
                (ffirst found)
                ".\nOnly the owner can waive this: the same start command with --skip-retro asks the owner first.")
        :plain true}))))
