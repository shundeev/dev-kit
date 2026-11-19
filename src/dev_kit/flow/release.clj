(ns dev-kit.flow.release
  "kit release: frees a held pull request: removes hold, marks it ready, turns auto-merge on (ADR-0007)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]))


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
      (steps/fail!
       :release/no-open-pr
       (format
        "No open pull request for branch '%s' in %s. Fix: run it from the held PR's branch, or name it with --pr <number>"
        branch
        repo)))}))


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
    (steps/fail! :release/not-open (format "%s is %s, not open: nothing to release." url state)))
  nil)


(defn- check-checks-passed
  "Refuses while GitHub reports no required check, or one that is not green; nothing has been changed yet."
  [{:keys [pr repo url]}]
  (let [checks (github/required-checks pr repo #(steps/fail! :release/checks-unreadable %))]
    (when (empty? checks)
      (steps/fail!
       :release/no-required-check
       (format
        "GitHub reports no required check on %s, so none can be called green. Fix: run it again once CI has started, or gh pr ready %s"
        pr
        pr)))
    (let [bad (->> checks
                   (remove #(#{"pass" "skipping"} (:bucket %)))
                   (map #(str (:name %) " (" (:bucket %) ")")))]
      (when (seq bad)
        (steps/fail!
         :release/check-not-green
         (format
          "A required check of %s is not green: %s. Nothing was changed: wait for it (kit checks, in the background), then run kit release again; a failed check is fixed first."
          url
          (str/join ", " bad)))))
    nil))


(defn- remove-label!
  "gh pr edit --remove-label."
  [pr repo label]
  (shell/ok! (shell/shell "gh" "pr" "edit" pr
               "-R"             repo
               "--remove-label" label)))


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
  (shell/ok! (shell/shell "gh" "pr" "ready" pr "-R" repo)))


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
  "kit release: read the command line, run the recipe on the real GitHub, print what it did, or the refusal."
  [_kit args]
  (let [input (read-input args)]
    (when (:help input)
      (println release-usage)
      (System/exit 0))
    (let [result (release input)]
      (when (= :fail (::steps/status result))
        (shell/exit-with! (steps/refusal result)))
      (println (format "Released: %s" (:url result))))))
