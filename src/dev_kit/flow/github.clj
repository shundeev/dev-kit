(ns dev-kit.flow.github
  "What several flow commands ask of GitHub and git, as plain functions a step calls directly (#139, step 7b): a
  reader goes from the call to the code in one jump. The calls are today's gh and git calls, in the same order, since
  the selftest's fake gh checks them as strings. A function returns data and decides nothing: refusing is the
  step's. A call only one command needs sits in that command's file, above its step.

  The per-command operation maps below are for the commands not yet converted: a step calls
  `((:github ctx) :op args…)` and tests hand an in-memory GitHub. A map goes away with its command; no name may
  appear in two maps."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]))


(defn issue-url
  "gh issue view: {:exit :out :err}, the issue's URL on stdout. A missing issue is the step's to judge."
  [repo number]
  (shell/run "gh" "issue" "view" number
    "-R"     repo
    "--json" "url"
    "--jq"   ".url"))


(defn item-add!
  "The issue's card on the board: {:id card-id}, or {:error message} when the board refuses."
  [project-number owner url]
  (try {:id (board/board-gh! "project" "item-add" project-number
              "--owner"  owner
              "--url"    url
              "--format" "json"
              "--jq"     ".id")}
       (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))


(defn set-field!
  "A single-select field of the issue's card: nil, or why the board refused."
  [project-number owner url field option]
  (board/board-set-field project-number owner url field option))


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn ready-workflow-failure
  "What it says when GitHub cannot say whether `base` of `repo` holds the ready workflow: gh's message, or its exit code
  when it printed none."
  [repo base exit message]
  (let [reason (if (str/blank? message)
                 (format "gh exit code %s" exit)
                 message)]
    (format
     "Could not look for %s on '%s' of %s (%s). Nothing was committed or pushed: run it again when GitHub answers, or check 'gh auth status'."
     ready-workflow-path
     base
     repo
     reason)))


(defn ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure goes to
  `fail` with the text: guessing would open a ready PR in a repository that has the workflow, or a draft that nothing
  marks ready. Without `fail` it ends the run."
  ([repo base] (ready-workflow? repo base shell/die))
  ([repo base fail]
   (let [{:keys [exit out err]}
         (shell/run "gh" "api" (format "repos/%s/contents/%s?ref=%s" repo ready-workflow-path base) "--silent")]
     (cond (zero? exit) true
           (re-find #"404|Not Found" (str out err)) false
           :else (fail (ready-workflow-failure repo base exit (str/trim (str out err))))))))


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


(defn read-pr
  "The pull request as data, see wait/read-pr; a failed read goes to `fail` with gh's message."
  [pr repo fail]
  (wait/read-pr pr repo fail))


(defn required-checks
  "The pull request's required checks, see wait/required-checks."
  [pr repo fail]
  (wait/required-checks pr repo fail))


(defn auto-merge!
  "Turns auto-merge on: {:exit :out :err}. finish, merge and release make this one call; each step decides what a
  refusal means."
  [pr-url]
  (shell/run "gh" "pr" "merge" pr-url "--auto" "--squash"))


(def shared-operations
  "The operations of the commands not yet converted to direct calls, by name; the plain functions above are what a
  converted command calls. Goes away with the last of them."
  {:head-branch     head-branch
   :open-pr-url     open-pr-url
   :read-pr         read-pr
   :required-checks required-checks
   :ready-workflow? ready-workflow?
   :auto-merge!     auto-merge!
   :issue-url       issue-url
   :set-field!      set-field!})


(def command-operations
  "Each command's operations by the command, the shared ones under :shared."
  {:shared shared-operations
  })


(def operations
  "Every operation by name."
  (apply merge (vals command-operations)))


(defn real
  "The GitHub of a real run: (github :op args…) calls the operation of that name."
  [op & args]
  (let [operation (or (operations op) (throw (ex-info (str "no GitHub operation " op) {:op op})))]
    (apply operation args)))
