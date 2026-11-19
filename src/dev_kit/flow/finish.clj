(ns dev-kit.flow.finish
  "kit finish: commits, pushes, opens the pull request and moves the card to In review. `finish` is the recipe, finish! its
  edge. A gh or git call only this command makes sits right above the step that needs it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]))


;; ------------------------------------------------------------------ kit finish


(def finish-usage
  "Usage: kit finish [options]

Run from the issue branch (<number>-<slug>, made by kit start).

Commits what is staged (or the --add paths), pushes, opens the pull request and sets the
board Status to \"In review\". Never merges: merging is kit merge.

Where main of the repository holds .github/workflows/ready-when-green.yml (ADR-0007), the PR
opens as a draft: GitHub asks the owners for a review only when that workflow marks it ready,
once the required checks have passed, and it turns auto-merge on then. Elsewhere the PR opens
ready, with auto-merge on, and the owners are asked at once.

Subjects follow Conventional Commits with the issue in the scope:
  feat(GH-12): add landing page
GitHub appends the PR number on squash: \"feat(GH-12): add landing page (#13)\".

Options:
  --type <type>                 feat|fix|docs|refactor|test|perf|build|ci|chore (default: the type of
                                the branch's first commit, else feat)
  --summary <text>              Subject text after \"type(GH-n): \" (default: the issue title)
  --commit-message-file <path>  Full commit message (subject, body, trailers); overrides --summary for the commit
  --pr-body-file <path>         PR description. Required when the PR is opened. Must contain a line
                                \"ADR: none\" or \"ADR: adr/NNNN-....md\". \"Closes #<issue>\" is added if missing.
  --add <path>                  Stage this path before committing (repeatable)
  --hold                        Open the PR with the label hold, for a PR that overlaps one still waiting: the
                                workflow leaves it a draft until kit release. Needs the ready workflow on main
  --no-commit                   Commit nothing; push and open the PR for commits already on the branch
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --review-status <option>      Default: In review
  --help                        Show this help")


(def finish-spec
  {"--type" [:type :value]
   "--summary" [:summary :value]
   "--commit-message-file" [:message-file :value]
   "--pr-body-file" [:body-file :value]
   "--add" [:add :many]
   "--no-commit" [:no-commit :flag]
   "--hold" [:hold :flag]
   "--repo" [:repo :value]
   "--owner" [:owner :value]
   "--project-number" [:project-number :value]
   "--base" [:base :value]
   "--status-field" [:status-field :value]
   "--review-status" [:review-status :value]
   "--help" [:help :flag]
   "-h" [:help :flag]})


(defn- request-auto-merge
  "Auto-merge (squash) on a ready PR; a repository that does not allow it gets a warning with the fix."
  [pr-url]
  (let [result (github/auto-merge! pr-url)]
    (when-not (zero? (:exit result))
      (shell/warn "Warning: auto-merge could not be turned on:" (str/trim (str (:out result) (:err result))))
      (shell/warn
       "  Fix: the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37); until then kit merge merges after the Approve."))))


;; ------------------------------------------------------------------ the steps


(defn- branch-commit-types
  "The subjects of the commits the branch has on top of origin/<base>, oldest first."
  [base]
  (str/split-lines (shell/git-out "log" "--reverse" "--format=%s" (str "origin/" base "..HEAD"))))


(defn- pick-type
  "Without --type the PR follows the branch: its first `type(GH-n): …` commit that is not docs, else the first, else feat."
  [{:keys [base] given-type :type}]
  {:commit-type (or given-type
                    (let [types (keep #(second (re-find #"^([a-z]+)\(GH-[0-9]+\): " %)) (branch-commit-types base))]
                      (or (first (remove #{"docs"} types)) (first types)))
                    "feat")})


(defn- resolve-repo
  "The repository: --repo, else the folder's, else the session's."
  [{:keys [repo]}]
  {:repo (shell/resolve-repo repo)})


(defn- check-branch
  "The branch the work is on: not detached, not the base, named <number>-<slug>. The issue is the branch's number: CI's
  pull request gate accepts no other (#21)."
  [{:keys [base]}]
  (let [branch (github/head-branch)]
    (when (= branch "HEAD")
      (steps/fail! :finish/detached-head "Detached HEAD is not supported"))
    (when (= branch base)
      (steps/fail! :finish/on-base (format "On the base branch '%s': run from the issue branch" base)))
    {:branch branch
     :number (or
              (shell/issue-branch-number branch)
              (steps/fail!
               :finish/branch-without-number
               (format
                "The branch '%s' is not <number>-<slug>: open the issue with kit start and move the work to its branch"
                branch)))}))


(defn- check-files
  "Everything that can be wrong with the inputs is checked before the commit and the push: a run that dies after the
  push leaves a branch with no pull request and a confusing rerun."
  [{:keys [body-file message-file]}]
  (when (and body-file (not (fs/readable? body-file)))
    (steps/fail! :finish/no-body-file (format "PR body file '%s' not found" body-file)))
  (when (and message-file (not (fs/readable? message-file)))
    (steps/fail! :finish/no-message-file (format "Commit message file '%s' not found" message-file)))
  {})


(defn- find-open-pr
  "Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones."
  [{:keys [repo branch]}]
  {:pr-url (github/open-pr-url repo branch "open")})


(defn- check-body-file
  "A PR to open needs its description."
  [{:keys [pr-url body-file]}]
  (when-not (or pr-url body-file)
    (steps/fail! :finish/body-file-required "--pr-body-file is required to open the pull request"))
  {})


(defn- issue-title
  "gh issue view: the issue's title."
  [repo number]
  (shell/gh! "issue" "view" number
    "-R"     repo
    "--json" "title"
    "--jq"   ".title"))


(defn- make-subject
  "The subject: --summary, else the message file's first line, else the issue's title, after \"type(GH-n): \"."
  [{:keys [commit-type number summary message-file repo]}]
  {:subject (cond summary      (format "%s(GH-%s): %s" commit-type number summary)
                  message-file (first (str/split-lines (slurp message-file)))
                  :else        (let [title        (issue-title repo number)
                                     first-letter (min 1 (count title))]
                                 (format "%s(GH-%s): %s%s"
                                         commit-type
                                         number
                                         (str/lower-case (subs title 0 first-letter))
                                         (subs title first-letter))))})


(defn- run-shape-check
  "kit gate --shape: {:exit :out :err}, the rules CI checks on the pull request."
  [kit branch title body-file]
  (apply shell/run "bb" kit "gate"
    "--shape"
    "--branch" branch
    "--title"  title
    (when body-file
      ["--body-file" body-file])))


(defn- check-shape
  "The title and the body pass the rules CI checks on the pull request (#21): one implementation, in kit. The body is
  checked only when it becomes a new PR's body; an open PR keeps its own."
  [{:keys [kit branch subject pr-url body-file]}]
  (let [shape (run-shape-check kit branch subject (when-not pr-url body-file))]
    (when-not (zero? (:exit shape))
      (steps/fail! :finish/shape
                   (format "The pull request would fail CI's shape check; nothing was committed or pushed:\n%s"
                           (str/trimr (str (:out shape) (:err shape))))))
    {}))


(defn- pr-draft?
  "gh pr view: whether the pull request is a draft."
  [pr-url]
  (= "true"
     (shell/gh! "pr" "view" pr-url
       "--json" "isDraft"
       "--jq"   ".isDraft")))


(defn- decide-draft
  "ADR-0007: with the ready workflow on the base the PR opens as a draft and the workflow marks it ready. An open PR is
  judged by its own state, not the base's: a ready PR gets what a ready PR gets, and a draft is left to the workflow."
  [{:keys [repo base pr-url]}]
  (let [base-draft? (github/ready-workflow? repo base #(steps/fail! :finish/workflow-unreadable %))]
    {:base-draft? base-draft?
     :draft?      (if pr-url
                    (pr-draft? pr-url)
                    base-draft?)}))


(defn- check-hold
  "--hold works through the ready workflow, which reads drafts only."
  [{:keys [repo base pr-url hold draft? base-draft?]}]
  (when (and hold pr-url (not draft?))
    (steps/fail!
     :finish/hold-on-ready-pr
     (format
      "%s is ready already: the workflow only reads drafts, so --hold would hold nothing. Nothing was committed or pushed: make it a draft first (gh pr ready %s --undo; that turns auto-merge off and keeps the owners' request) and run kit finish --hold again, or run kit finish without --hold."
      pr-url
      pr-url)))
  (when (and hold (not base-draft?))
    (steps/fail!
     :finish/hold-without-workflow
     (format
      "--hold works through %s, which '%s' of %s does not hold yet. Nothing was committed or pushed: run kit finish without --hold (the PR opens ready, the owners are asked at once), or after the dev-kit update PR with the file has merged."
      github/ready-workflow-path
      base
      repo)))
  {})


(defn- create-hold-label!
  "gh label create: the label the ready workflow reads, {:exit :out :err}."
  [repo]
  (shell/run "gh" "label" "create" "hold"
    "-R"            repo
    "--color"       "FBCA04"
    "--description" "Stays a draft until kit release (ADR-0007)"))


(defn- make-hold-label
  "The label the workflow reads must exist before the PR is opened with it; a run that cannot make it stops before the
  first commit."
  [{:keys [repo hold]}]
  (when hold
    (let [{:keys [exit out err]} (create-hold-label! repo)]
      (when-not (or (zero? exit) (re-find #"already exists" (str out err)))
        (steps/fail!
         :finish/hold-label
         (format
          "Label 'hold' does not exist in %s and could not be created: create it on GitHub (Issues > Labels), then run again"
          repo)))))
  {})


(defn- stage!
  "git add: the paths, ending the run when git refuses."
  [paths]
  (apply shell/run-on-terminal! "git" "add" "--" paths))


(defn- staged?
  "Whether anything is staged to commit."
  []
  (not (zero? (:exit (shell/run "git" "diff" "--cached" "--quiet")))))


(defn- commit!
  "git commit with the message file, else with the subject, ending the run when git refuses."
  [message-file subject]
  (if message-file
    (shell/run-on-terminal! "git" "commit" "-F" message-file)
    (shell/run-on-terminal! "git" "commit" "-m" subject)))


(defn- commit
  [{:keys [no-commit add message-file subject]}]
  (when-not no-commit
    (when (seq add)
      (stage! add))
    (when-not (staged?)
      (steps/fail! :finish/nothing-staged "Nothing staged to commit (use --add <path>, or --no-commit)"))
    (commit! message-file subject))
  {})


(defn- push!
  "git push -u origin <branch>, ending the run when git refuses."
  [branch]
  (shell/run-on-terminal! "git" "push" "-u" "origin" branch))


(defn- push
  [{:keys [branch]}]
  (push! branch)
  {})


(defn- create-pr!
  "gh pr create: the new pull request's URL."
  [repo base branch flags title body]
  (shell/last-line
   (apply shell/gh! "pr" "create"
     "-R"     repo
     "--base" base
     "--head" branch
     (concat flags ["--title" title "--body" body]))))


(defn- open-pr
  "The body is the file's, with \"Closes #<issue>\" in front unless it has a closing keyword for that issue."
  [{:keys [repo base branch number subject body-file pr-url base-draft? hold]}]
  (if pr-url
    {}
    (let [text  (shell/file-text body-file)
          body  (if (re-find (re-pattern (format "(?i)(close[sd]?|fix(e[sd])?|resolve[sd]?) #%s\\b" number)) text)
                  text
                  (format "Closes #%s\n\n%s" number text))
          flags (cond-> []
                  base-draft? (conj "--draft")
                  hold        (conj "--label" "hold"))]
      {:pr-url  (create-pr! repo base branch flags subject body)
       :opened? true})))


(defn- edit-pr!
  "gh pr edit with the arguments: {:exit :out :err}."
  [pr-url args]
  (apply shell/run "gh" "pr" "edit" pr-url args))


(defn- ask-the-owners
  "On the reviewers' desk, assigned to the person behind the agent (#52); repeating it is harmless. A draft asks nobody:
  the owners are requested when the ready workflow marks it ready (ADR-0007), and asking now would send the
  notification this change moves to the passed checks."
  [{:keys [pr-url draft? reviewers human]}]
  (let [edit (concat (when (and reviewers (not draft?))
                       ["--add-reviewer" reviewers])
                     (when human
                       ["--add-assignee" human]))]
    (when-not human
      (shell/warn
       "Warning: no GHWF_HUMAN, so the PR has no assignee. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login."))
    (when (seq edit)
      (let [result (edit-pr! pr-url edit)]
        (when-not (zero? (:exit result))
          (shell/warn "Warning: could not request the review or set the assignee:"
                      (str/trim (str (:out result) (:err result)))))))
    {}))


(defn- hold-open-pr
  "An open PR is held by its own label: the workflow reads it when CI completes."
  [{:keys [pr-url hold opened?]}]
  (when (and hold (not opened?))
    (let [result (edit-pr! pr-url ["--add-label" "hold"])]
      (when-not (zero? (:exit result))
        (steps/fail!
         :finish/hold-label-refused
         (format "%s could not get the label hold: %s. Fix: add the label on GitHub, or run kit finish --hold again."
                 pr-url
                 (str/trim (str (:out result) (:err result))))))))
  {})


(defn- turn-on-auto-merge
  "The owner's Approve is the word to merge: with auto-merge on, GitHub merges once the owner approved the latest commit,
  the checks passed and the branch contains the latest base. A draft cannot have it: the ready workflow turns it on
  after it marks the PR ready (ADR-0007)."
  [{:keys [draft? pr-url]}]
  (when-not draft?
    (request-auto-merge pr-url))
  {})


(defn- set-review-status
  "A failed board update ends the run (#41). The PR is open by now, so only the status is left to set."
  [{:keys [kit repo number owner project-number status-field review-status pr-url]}]
  (when (not-any? str/blank? [owner project-number review-status])
    (let [{:keys [exit out]} (github/issue-url repo number)
          url (when (zero? exit)
                (str/trim out))]
      (if (str/blank? url)
        (shell/warn (format "Warning: issue #%s not found, Status not set" number))
        (when-let [failure (board/board-set-field project-number owner url status-field review-status)]
          (steps/fail!
           :finish/status-refused
           (format "The pull request is open: %s. But the board %s of #%s is not '%s': %s. Set it alone: %s"
                   pr-url
                   status-field
                   number
                   review-status
                   (board/board-reason failure)
                   (board/board-set-command kit project-number owner url status-field review-status)))))))
  {})


(defn finish
  "The recipe, from the command line's input (with the path of bin/kit as :kit). Every step before :commit only reads,
  so a refusal there leaves nothing committed, pushed or opened."
  [input]
  (steps/run input
    :pick-type          pick-type
    :resolve-repo       resolve-repo
    :check-branch       check-branch
    :check-files        check-files
    :find-open-pr       find-open-pr
    :check-body-file    check-body-file
    :make-subject       make-subject
    :check-shape        check-shape
    :decide-draft       decide-draft
    :check-hold         check-hold
    :make-hold-label    make-hold-label
    :commit             commit
    :push               push
    :open-pr            open-pr
    :ask-the-owners     ask-the-owners
    :hold-open-pr       hold-open-pr
    :turn-on-auto-merge turn-on-auto-merge
    :set-review-status  set-review-status))


;; ------------------------------------------------------------------ the edges


(defn read-input
  "The command line and the GHWF_* defaults as one map; env is (fn [name default]), shell/env in a run."
  [args env]
  (let [opts (shell/parse-opts finish-spec args)]
    (if (:help opts)
      opts
      (assoc opts
             :owner         (or (:owner opts) (env "GHWF_OWNER" nil))
             :project-number (or (:project-number opts) (env "GHWF_PROJECT_NUMBER" nil))
             :base          (or (:base opts) (env "GHWF_DEFAULT_BASE" "main"))
             :status-field  (or (:status-field opts) (env "GHWF_STATUS_FIELD" "Status"))
             :review-status (or (:review-status opts) (env "GHWF_REVIEW_STATUS" "In review"))
             :reviewers     (env "GHWF_REVIEWERS" nil)
             :human         (env "GHWF_HUMAN" nil)))))


(defn print-result
  "What the run made, in the lines the flow's readers expect."
  [{:keys [subject pr-url draft? hold review-status]}]
  (println (format "Subject: %s" subject))
  (println (format "PR URL: %s" pr-url))
  (when draft?
    (let [held-note (if hold " Held: when the earlier PR has merged, run kit release." "")]
      (println
       (format
        "Draft: the owners are asked once its required checks pass; the ready workflow marks it ready and turns on auto-merge.%s"
        held-note))))
  (println (format "Status: %s" review-status)))


(defn finish!
  "kit finish: read the command line, run the recipe on the real GitHub, print what it made — or the refusal."
  [kit args]
  (let [input (read-input args shell/env)]
    (when (:help input)
      (println finish-usage)
      (System/exit 0))
    (let [result (finish (assoc input :kit kit))]
      (when (= :fail (::steps/status result))
        (shell/exit-with! (steps/refusal result)))
      (print-result result))))
