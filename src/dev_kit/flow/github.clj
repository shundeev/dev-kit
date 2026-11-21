(ns dev-kit.flow.github
  "What several flow commands ask of GitHub and git, as plain functions a step calls directly (#139, step 7b): a
  reader goes from the call to the code in one jump. The calls are today's gh and git calls, in the same order, since
  the selftest's fake gh checks them as strings. A function returns data and decides nothing: refusing is the
  step's. A call only one command needs sits in that command's file, above its step."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.shell :as shell]
   [dev-kit.problem :as problem]))


(defn issue-url
  "gh issue view: {:exit :out :err}, the issue's URL on stdout. A missing issue is the step's to judge."
  [repo number]
  (shell/run "gh" "issue" "view" number
    "-R"     repo
    "--json" "url"
    "--jq"   ".url"))


(def ^:private size-label-jq
  "The value of the issue's size:* label, or nothing."
  "[.labels[].name | select(startswith(\"size:\")) | ltrimstr(\"size:\")] | first // empty")


(defn issue-size
  "The issue's size, \"L\" for the label size:L; nil when it has none or GitHub cannot say (the issue is then treated as
  not L, which is the way that closes it)."
  [repo number]
  (let [{:keys [exit out]}
        (shell/run "gh" "issue" "view" (str number)
          "-R"     repo
          "--json" "labels"
          "--jq"   size-label-jq)]
    (when (zero? exit)
      (not-empty (str/trim out)))))


(defn closes-issue?
  "Whether the text has a closing keyword (close, fix, resolve, in any form and case) for the issue number."
  [text number]
  (boolean (re-find (re-pattern (format "(?i)(close[sd]?|fix(e[sd])?|resolve[sd]?) #%s\\b" number)) text)))


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn ready-workflow-failure
  "What it says when GitHub cannot say whether `base` of `repo` holds the ready workflow: gh's message, or its exit code
  when it printed none."
  [repo base exit message]
  (let [reason (if (str/blank? message)
                 (format "gh exit code %s" exit)
                 message)]
    (format "Could not look for %s on '%s' of %s (%s). Nothing was committed or pushed"
            ready-workflow-path
            base
            repo
            reason)))


(def ready-workflow-fix "run it again when GitHub answers, or check 'gh auth status'.")


(defn ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure goes to
  `fail` with the text and what to do: guessing would open a ready PR in a repository that has the workflow, or a
  draft that nothing marks ready. Without `fail` it stops the step as a problem of its own."
  ([repo base]
   (ready-workflow? repo
                    base
                    #(problem/fail! :github/workflow-unreadable %1 {:fix %2})))
  ([repo base fail]
   (let [{:keys [exit out err]}
         (shell/run "gh" "api" (format "repos/%s/contents/%s?ref=%s" repo ready-workflow-path base) "--silent")]
     (cond (zero? exit) true
           (re-find #"404|Not Found" (str out err)) false
           :else (fail (ready-workflow-failure repo base exit (str/trim (str out err))) ready-workflow-fix)))))


(defn head-branch
  "The branch checked out now, by git."
  []
  (shell/git-out "rev-parse" "--abbrev-ref" "HEAD"))


(defn open-pr-url
  "The URL of the branch's pull request in this state, nil when it has none."
  [repo branch state]
  (not-empty (shell/gh! "pr" "list"
               "-R"      repo
               "--head"  branch
               "--state" state
               "--json"  "url"
               "--jq"    ".[0].url // empty")))


(defn- pr-args
  "The pull request as gh takes it: a URL alone, a number with its repository."
  [pr repo]
  (if (re-matches #"\d+" (str pr))
    [(str pr) "-R" repo]
    [pr]))


(defn gh-failure-message
  "What a failed gh call says: the command's first two words and gh's output."
  [command output]
  (format "gh %s failed: %s" command (str/trim output)))


(def gh-failure-fix
  "run it by hand to see why; an unreachable GitHub and an expired token both show there.")


(def not-json-fix "run it by hand to see what gh answers; GitHub may be returning an error page")


(defn- abbreviate
  "The start of a text, for a message that quotes it."
  [text]
  (let [text (str/trim text)]
    (if (> (count text) 200)
      (str (subs text 0 200) "…")
      text)))


(defn gh-json
  "A gh call's JSON output. A call that fails, or answers with something that is not JSON, goes to `fail` (a function
  of the text and what to do: a stop with its own exit code) with gh's message."
  [fail & args]
  (let [{:keys [exit out err]} (apply shell/run "gh" args)
        command (str/join " " (take 2 args))]
    (if (zero? exit)
      (try (json/parse-string out true)
           (catch Exception _
             (fail (format "gh %s answered with text that is not JSON: %s" command (abbreviate out)) not-json-fix)))
      (fail (gh-failure-message command (str out err)) gh-failure-fix))))


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


(defn checks-failure-message
  "What a failed `gh pr checks` says: gh's output."
  [output]
  (format "gh pr checks failed: %s" (str/trim output)))


(defn checks-failure-fix
  "What to do about a failed `gh pr checks`: the pull request as gh took it."
  [pr]
  (format
   "run `gh pr checks %s --required` by hand to see why; an unreachable GitHub and an expired token both show there."
   pr))


(defn required-checks
  "The required checks registered on the pull request's head commit, as {:name :bucket :link}; [] when none is."
  [pr repo fail]
  (let [{:keys [out err]}
        (apply shell/run "gh" "pr" "checks" (concat (pr-args pr repo) ["--required" "--json" "name,bucket,link"]))
        parsed (try (json/parse-string out true) (catch Exception _ nil))]
    (cond (sequential? parsed) parsed
          (re-find #"(?i)no (required )?checks reported" (str out err)) []
          :else
          (fail (checks-failure-message (str out err)) (checks-failure-fix (first (pr-args pr repo)))))))


(defn auto-merge!
  "Turns auto-merge on: {:exit :out :err}. finish, merge and release make this one call; each step decides what a
  refusal means."
  [pr-url]
  (shell/run "gh" "pr" "merge" pr-url "--auto" "--squash"))
