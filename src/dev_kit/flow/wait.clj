(ns dev-kit.flow.wait
  "Waiting on a pull request until what is awaited happens, with no fixed time limit (#109)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.shell :as shell]))


;; ------------------------------------------------------------------ waiting on a pull request


;; Nothing here waits on a clock (#109). A wait ends when what it waits for happens, or when that became impossible,
;; and it says which. The only number is the pause between two looks at GitHub, which no event replaces: GitHub cannot
;; call a process on this machine.


(defn pause
  "The pause between two looks, in seconds: GHWF_MERGE_POLL, for kit merge and kit checks alike."
  []
  (let [v (shell/env "GHWF_MERGE_POLL" "10")]
    (or (parse-long v)
        (shell/die (str "GHWF_MERGE_POLL is '" v "': give it a whole number of seconds, or unset it for 10")))))


(defn- pr-args
  "The pull request as gh takes it: a URL alone, a number with its repository."
  [pr repo]
  (if (re-matches #"\d+" (str pr))
    [(str pr) "-R" repo]
    [pr]))


(defn gh-json
  "A gh call's JSON output. A call that fails goes to `fail` (die, or a stop with its own exit code) with gh's message."
  [fail & args]
  (let [{:keys [exit out err]} (apply shell/run "gh" args)]
    (if (zero? exit)
      (json/parse-string out true)
      (fail (str "gh "
                 (str/join " " (take 2 args))
                 " failed: "
                 (str/trim (str out err))
                 ". Fix: run it by hand to see why; an unreachable GitHub and an expired token both show there.")))))


(defn read-pr
  "A pull request's JSON fields: :state, :decision (nil when there is no review decision), :merge-state, :head, :base,
  :draft, :hold (it carries the label hold), :owners (who is asked for a review), :auto-merge."
  [pr repo fail]
  (let
    [j
     (apply
      gh-json
      fail
      "pr"
      "view"
      (concat
       (pr-args pr repo)
       ["--json"
        "state,reviewDecision,mergeStateStatus,headRefOid,baseRefName,isDraft,labels,reviewRequests,autoMergeRequest"]))]
    {:state       (:state j)
     :decision    (not-empty (:reviewDecision j))
     :merge-state (:mergeStateStatus j)
     :head        (:headRefOid j)
     :base        (:baseRefName j)
     :draft       (:isDraft j)
     :hold        (boolean (some #(= "hold" (:name %)) (:labels j)))
     :owners      (map #(or (:login %) (:slug %)) (:reviewRequests j))
     :auto-merge  (some? (:autoMergeRequest j))}))


(defn required-checks
  "The required checks registered on the pull request's head commit, as {:name :bucket :link}; [] when none is."
  [pr repo fail]
  (let [{:keys [out err]}
        (apply shell/run "gh" "pr" "checks" (concat (pr-args pr repo) ["--required" "--json" "name,bucket,link"]))
        parsed (try (json/parse-string out true) (catch Exception _ nil))]
    (cond (sequential? parsed) parsed
          (re-find #"(?i)no (required )?checks reported" (str out err)) []
          :else
          (fail (str "gh pr checks failed: "
                     (str/trim (str out err))
                     ". Fix: run `gh pr checks "
                     (first (pr-args pr repo))
                     " --required` by hand to see why; an unreachable GitHub and an expired token both show there.")))))


(defn failed-text
  [failed where]
  (str (count failed)
       " required check" (when (> (count failed) 1) "s")
       " failed " where
       ":\n"
       (str/join "\n" (map #(str "  " (:name %) ": " (:link %)) failed))))


(defn failed-checks [checks] (filter #(#{"fail" "cancel"} (:bucket %)) checks))
