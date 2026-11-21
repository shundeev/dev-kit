;; The gate: merged tasks still lacking their retrospective (see bin/retro.clj).
(ns dev-kit.retro.missing
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
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
   "query($search:String!){search(query:$search,type:ISSUE,first:50){nodes{... on Issue{number title"
   " comments(last:100){nodes{body}} closedByPullRequestsReferences(first:5,includeClosedPrs:true){nodes{mergedAt}}}}}}"))


(defn missing
  "[number title] of the issues closed as completed by a merged PR since `since` that lack a retrospective."
  [repo ^Instant since]
  (let [search (str "repo:" repo " is:issue is:closed reason:completed closed:>=" (subs (str since) 0 10))]
    (for [issue (get-in (json/parse-string (base/gh! "api" "graphql"
                                             "-f" (str "query=" missing-query)
                                             "-f" (str "search=" search)))
                        ["data" "search" "nodes"])
          :when (and (some #(get % "mergedAt") (github/nodes issue "closedByPullRequestsReferences"))
                     (not-any? #(str/starts-with? (str/trim (str (get % "body"))) "## Retrospective")
                               (github/nodes issue "comments")))]
      [(get issue "number") (get issue "title")])))


(defn merged-without-retrospective
  "[number title] of the issues merged in the 14 days before `now` that lack a retrospective. When GitHub cannot be
  asked, a problem (:retro/github-unavailable, exit 2)."
  [repo ^Instant now]
  (try (doall (missing repo (.minus now (Duration/ofDays 14))))
       (catch Exception exception
         (problem/fail! :retro/github-unavailable
                        (str "retro: GitHub not available: " (ex-message exception))
                        {:fix  "check gh auth status and the network, then run kit retro --missing again"
                         :exit 2}))))


(defn found-text
  "What a person reads about the issues found: the list, then what to do."
  [found]
  (str "Merged in the last 14 days without a `## Retrospective` comment: "
       (str/join ", " (for [[number title] found] (str "#" number " " title)))
       ".\nPost each one's retrospective before new work (repo-task-delivery, stage 11); its data comes from"
       " kit retro <n>, e.g. kit retro "
       (ffirst found)
       ".\nOnly the owner can waive this: the same start command with --skip-retro asks the owner first."))


(defn run-missing
  "The list of merged issues without a retrospective, with what to do, on stdout, and 1: an answer, not a problem.
  Nil and silence when every merged issue has its retrospective. When GitHub cannot be asked, a problem with exit 2."
  [{:keys [repo now]}]
  (let [now   (if now
                (log/parse-time "--now" now)
                (Instant/now))
        found (merged-without-retrospective repo now)]
    (when (seq found)
      (println (found-text found))
      1)))
