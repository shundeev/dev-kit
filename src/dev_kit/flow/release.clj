(ns dev-kit.flow.release
  "kit release: frees a held pull request: removes hold, marks it ready, turns auto-merge on (ADR-0007)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.problem :as problem]))


;; ------------------------------------------------------------------ kit release


(def release-usage
  "Usage: kit release [options]

For a pull request held with the label hold (kit finish --hold), once the PR it waited for has
merged: removes the label, marks the PR ready (GitHub then asks the owners for a review) and
turns on auto-merge (ADR-0007). It also frees a draft that was never held but that nothing marked ready. It refuses,
naming the check and changing nothing, while a required check on the PR's head is not green.

Options:
  --pr <number|url>             The pull request (default: the open PR of the current branch)
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --help                        Show this help")


(def release-spec
  {"--pr" [:pr :value] "--repo" [:repo :value] "--help" [:help :flag] "-h" [:help :flag]})


(defn read-input
  "The command line as one map. kit release reads no GHWF_* variable, so there is no env."
  [args]
  (shell/parse-opts release-spec args))


(defn- resolve-repo
  "The repository the run works in."
  [{:keys [repo]}]
  {:repo (shell/resolve-repo repo)})


(defn- find-pr
  "The pull request: --pr, else the open one of the current branch. The branch is read either way."
  [{:keys [pr repo]}]
  (let [branch (github/head-branch)]
    {:pr
     (or
      pr
      (github/open-pr-url repo branch "open")
      (problem/fail! :release/no-open-pr
                     (format "No open pull request for branch '%s' in %s" branch repo)
                     {:fix "run it from the held PR's branch, or name it with --pr <number>"}))}))


(defn- view-pr-for-release
  "gh pr view: the pull request's state, draft, hold and URL."
  [pr repo]
  (let [{:keys [state isDraft labels url]}
        (json/parse-string (shell/gh! "pr" "view" pr
                             "-R"     repo
                             "--json" "state,isDraft,labels,url")
                           true)]
    {:state state
     :draft isDraft
     :hold  (boolean (some #(= "hold" (:name %)) labels))
     :url   url}))


(defn- read-pr-state
  "The PR's state, draft and hold. Its URL names it in every message and turns on auto-merge; the PR as given stands in
  when GitHub gives none."
  [{:keys [pr repo]}]
  (let [found (view-pr-for-release pr repo)]
    (assoc found :url (or (:url found) pr))))


(defn- check-open
  "Refuses a pull request that is not open: there is nothing to release."
  [{:keys [state url]}]
  (when-not (= state "OPEN")
    (problem/fail! :release/not-open
                   (format "%s is %s, not open: nothing to release." url state)
                   {:fix "name the held pull request: kit release --pr <number>"}))
  nil)


(defn- check-checks-passed
  "Refuses while GitHub reports no required check, or one that is not green; nothing has been changed yet."
  [{:keys [pr repo url]}]
  (let [checks (github/required-checks pr repo #(problem/fail! :release/checks-unreadable %1 {:fix %2}))]
    (when (empty? checks)
      (problem/fail!
       :release/no-required-check
       (format "GitHub reports no required check on %s, so none can be called green." pr)
       {:fix (format "run it again once CI has started, or gh pr ready %s" pr)}))
    (let [bad (->> checks
                   (remove #(#{"pass" "skipping"} (:bucket %)))
                   (map #(str (:name %) " (" (:bucket %) ")")))]
      (when (seq bad)
        (problem/fail!
         :release/check-not-green
         (format "A required check of %s is not green: %s. Nothing was changed." url (str/join ", " bad))
         {:fix
          "wait for it (kit checks, in the background), then run kit release again; a failed check is fixed first"})))
    nil))


(defn- remove-label!
  "gh pr edit --remove-label."
  [pr repo label]
  (shell/run-on-terminal! "gh"   "pr"
                          "edit" pr
                          "-R"   repo
                          "--remove-label" label))


(defn- remove-hold
  "Takes the label hold off a held pull request."
  [{:keys [pr repo hold]}]
  (when hold
    (remove-label! pr repo "hold")
    (println "Hold removed."))
  nil)


(defn- mark-ready!
  "gh pr ready."
  [pr repo]
  (shell/run-on-terminal! "gh" "pr" "ready" pr "-R" repo))


(defn- mark-ready
  "Marks a draft ready, so GitHub asks the owners for a review."
  [{:keys [pr repo draft]}]
  (when draft
    (mark-ready! pr repo)
    (println "Marked ready: the owners are asked for a review."))
  nil)


(defn- turn-on-auto-merge
  "A repository that does not allow auto-merge gets a warning with the fix; the release still stands."
  [{:keys [url]}]
  (let [{:keys [exit out err]} (github/auto-merge! url)]
    (when-not (zero? exit)
      (shell/warn "Warning: auto-merge could not be turned on:" (str/trim (str out err)))
      (shell/warn
       "  Fix: the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37); until then kit merge merges after the Approve.")))
  nil)


(defn release
  "The recipe of kit release."
  [input]
  (steps/run input
    :resolve-repo        resolve-repo
    :find-pr             find-pr
    :read-pr-state       read-pr-state
    :check-open          check-open
    :check-checks-passed check-checks-passed
    :remove-hold         remove-hold
    :mark-ready          mark-ready
    :turn-on-auto-merge  turn-on-auto-merge))


(defn release!
  "kit release: read the command line, run the recipe on the real GitHub, print what it did, or throw the refusal, which the flow's edge prints."
  [args]
  (let [input (read-input args)]
    (if (:help input)
      (println release-usage)
      (let [result (steps/complete! (release input))]
        (println (format "Released: %s" (:url result)))))))
