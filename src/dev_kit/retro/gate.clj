;; The gate: merged tasks still lacking their retrospective (see bin/retro.clj).
(ns dev-kit.retro.gate
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
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
  "Exit 0 and silence when every merged issue has its retrospective; exit 1 with the list and the fix; exit 2
  when GitHub cannot be asked."
  [{:keys [repo gh now]}]
  (let [now   (if now
                (log/parse-time "--now" now)
                (Instant/now))
        found (try (doall (missing gh repo (.minus ^Instant now (Duration/ofDays 14))))
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "kit: retro: GitHub not available: " (ex-message e)))
                       (flush))
                     (System/exit 2)))]
    (when (seq found)
      (println (str "Merged in the last 14 days without a `## Retrospective` comment: "
                    (str/join ", " (for [[n t] found] (str "#" n " " t)))
                    "."))
      (println (str "Post each one's retrospective before new work (repo-task-delivery, stage 11); its data comes from"
                    " kit retro <n>, e.g. kit retro "
                    (ffirst found)
                    "."))
      (println "Only the owner can waive this: the same start command with --skip-retro asks the owner first.")
      (flush)
      (System/exit 1))))
