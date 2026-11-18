(ns dev-kit.flow.finish
  "kit finish: commits, pushes, opens the pull request and moves the card to In review."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]))


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


(defn git-out [& args] (some-> (apply shell/run "git" args) :out str/trim))


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure ends the
  run: guessing would open a ready PR in a repository that has the workflow, or a draft that nothing marks ready."
  [repo base]
  (let [{:keys [exit out err]}
        (shell/run "gh" "api" (str "repos/" repo "/contents/" ready-workflow-path "?ref=" base) "--silent")]
    (cond (zero? exit) true
          (re-find #"404|Not Found" (str out err)) false
          :else
          (shell/die
           (str
            "Could not look for "
            ready-workflow-path
            " on '"
            base
            "' of "
            repo
            " ("
            (let [m (str/trim (str out err))]
              (if (str/blank? m)
                (str "gh exit code " exit)
                m))
            "). Nothing was committed or pushed: run it again when GitHub answers, or check 'gh auth status'.")))))


(defn- ensure-hold-label!
  "The label hold exists in the repository; made when it does not."
  [repo]
  (let [{:keys [exit out err]} (shell/run "gh" "label" "create" "hold"
                                 "-R"            repo
                                 "--color"       "FBCA04"
                                 "--description" "Stays a draft until kit release (ADR-0007)")]
    (when-not (or (zero? exit) (re-find #"already exists" (str out err)))
      (shell/die (str "Label 'hold' does not exist in "
                      repo
                      " and could not be created: create it on GitHub (Issues > Labels), then run again")))))


(defn turn-on-auto-merge!
  "Auto-merge (squash) on a ready PR; a repository that does not allow it gets a warning with the fix."
  [pr-url]
  (let [r (shell/run "gh" "pr" "merge" pr-url "--auto" "--squash")]
    (when-not (zero? (:exit r))
      (shell/warn "Warning: auto-merge could not be turned on:" (str/trim (str (:out r) (:err r))))
      (shell/warn
       "  Fix: the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37); until then kit merge merges after the Approve."))))


(defn finish!
  [kit args]
  (let [o              (shell/parse-opts finish-spec args)
        _ (when (:help o)
            (println finish-usage)
            (System/exit 0))
        owner          (or (:owner o) (shell/env "GHWF_OWNER" nil))
        project-number (or (:project-number o) (shell/env "GHWF_PROJECT_NUMBER" nil))
        base           (or (:base o) (shell/env "GHWF_DEFAULT_BASE" "main"))
        status-field   (or (:status-field o) (shell/env "GHWF_STATUS_FIELD" "Status"))
        review-status  (or (:review-status o) (shell/env "GHWF_REVIEW_STATUS" "In review"))
        ;; Without --type the PR follows the branch: its first `type(GH-n): …` commit that is not docs, else the first, else feat.
        ctype          (or (:type o)
                           (let [types (keep #(second (re-find #"^([a-z]+)\(GH-[0-9]+\): " %))
                                             (str/split-lines (git-out "log" "--reverse"
                                                                       "--format=%s" (str "origin/" base "..HEAD"))))]
                             (or (first (remove #{"docs"} types)) (first types)))
                           "feat")
        repo           (shell/resolve-repo (:repo o))
        branch         (git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (= branch "HEAD")
            (shell/die "Detached HEAD is not supported"))
        _ (when (= branch base)
            (shell/die (str "On the base branch '" base "': run from the issue branch")))
        ;; The issue is the branch's number: CI's pull request gate accepts no other (#21).
        number         (or
                        (shell/issue-branch-number branch)
                        (shell/die
                         (str
                          "The branch '"
                          branch
                          "' is not <number>-<slug>: open the issue with kit start and move the work to its branch")))
        ;; Everything that can be wrong with the inputs is checked before the commit and the push: a run that dies
        ;; after the push leaves a branch with no pull request and a confusing rerun.
        _ (when (and (:body-file o) (not (fs/readable? (:body-file o))))
            (shell/die (str "PR body file '" (:body-file o) "' not found")))
        _ (when (and (:message-file o) (not (fs/readable? (:message-file o))))
            (shell/die (str "Commit message file '" (:message-file o) "' not found")))
        ;; Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones.
        pr-url         (not-empty (shell/gh! "pr" "list"
                                    "-R"      repo
                                    "--head"  branch
                                    "--state" "open"
                                    "--json"  "url"
                                    "--jq"    ".[0].url // empty"))
        _ (when-not (or pr-url (:body-file o))
            (shell/die "--pr-body-file is required to open the pull request"))
        subject        (cond
                         (:summary o)      (str ctype "(GH-" number "): " (:summary o))
                         (:message-file o) (first (str/split-lines (slurp (:message-file o))))
                         :else             (let [t
                                                 (shell/gh! "issue" "view" number
                                                   "-R"     repo
                                                   "--json" "title"
                                                   "--jq"   ".title")]
                                             (str ctype
                                                  "(GH-"
                                                  number
                                                  "): "
                                                  (str/lower-case (subs t 0 (min 1 (count t))))
                                                  (subs t (min 1 (count t))))))
        ;; The title and the body pass the rules CI checks on the pull request (#21): one implementation, in kit.
        ;; The body is checked only when it becomes a new PR's body; an open PR keeps its own.
        shape          (apply shell/run "bb" kit "gate"
                         "--shape"
                         "--branch" branch
                         "--title"  subject
                         (when (and (not pr-url) (:body-file o))
                           ["--body-file" (:body-file o)]))
        _ (when-not (zero? (:exit shape))
            (shell/die (str "The pull request would fail CI's shape check; nothing was committed or pushed:\n"
                            (str/trimr (str (:out shape) (:err shape))))))
        ;; ADR-0007: with the ready workflow on main the PR opens as a draft and the workflow marks it ready.
        base-draft?    (ready-workflow? repo base)
        ;; An open PR is judged by its own state, not by the base's: a ready PR gets what a ready PR gets, and a
        ;; draft is left to the workflow.
        draft?         (if pr-url
                         (= "true"
                            (shell/gh! "pr" "view" pr-url
                              "--json" "isDraft"
                              "--jq"   ".isDraft"))
                         base-draft?)
        _
        (when (and (:hold o) pr-url (not draft?))
          (shell/die
           (str
            pr-url
            " is ready already: the workflow only reads drafts, so --hold would hold nothing. Nothing was committed or "
            "pushed: make it a draft first (gh pr ready " pr-url
            " --undo; that turns auto-merge off and keeps the owners' "
            "request) and run kit finish --hold again, or run kit finish without --hold.")))
        _ (when (and (:hold o) (not base-draft?))
            (shell/die
             (str
              "--hold works through "
              ready-workflow-path
              ", which '"
              base
              "' of "
              repo
              " does not hold yet. Nothing was "
              "committed or pushed: run kit finish without --hold (the PR opens ready, the owners are asked at once), "
              "or after the dev-kit update PR with the file has merged.")))
        _ (when (:hold o)
            (ensure-hold-label! repo))]
    (when-not (:no-commit o)
      (when (seq (:add o))
        (shell/ok! (apply shell/shell "git" "add" "--" (:add o))))
      (when (zero? (:exit (shell/run "git" "diff" "--cached" "--quiet")))
        (shell/die "Nothing staged to commit (use --add <path>, or --no-commit)"))
      (shell/ok! (if (:message-file o)
                   (shell/shell "git" "commit" "-F" (:message-file o))
                   (shell/shell "git" "commit" "-m" subject))))
    (shell/ok! (shell/shell "git" "push" "-u" "origin" branch))
    (let [existing? (boolean pr-url)
          pr-url    (or pr-url
                        (let [body (shell/file-text (:body-file o))
                              body (if (re-find (re-pattern
                                                 (str "(?i)(close[sd]?|fix(e[sd])?|resolve[sd]?) #" number "\\b"))
                                                body)
                                     body
                                     (str "Closes #" number "\n\n" body))]
                          (shell/last-line
                           (apply shell/gh! "pr" "create"
                             "-R"     repo
                             "--base" base
                             "--head" branch
                             (cond-> []
                               base-draft? (conj "--draft")
                               (:hold o)   (conj "--label" "hold")
                               :always     (conj "--title" subject "--body" body))))))
          reviewers (shell/env "GHWF_REVIEWERS" nil)
          human     (not-empty (System/getenv "GHWF_HUMAN"))
          ;; A draft asks nobody: the owners are requested when the ready workflow marks it ready (ADR-0007), and
          ;; asking now would send the notification this change moves to the passed checks.
          edit      (concat (when (and reviewers (not draft?))
                              ["--add-reviewer" reviewers])
                            (when human
                              ["--add-assignee" human]))]
      ;; On the reviewers' desk, assigned to the person behind the agent (#52). Repeating it is harmless.
      (when-not human
        (shell/warn
         "Warning: no GHWF_HUMAN, so the PR has no assignee. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login."))
      (when (seq edit)
        (let [r (apply shell/run "gh" "pr" "edit" pr-url edit)]
          (when-not (zero? (:exit r))
            (shell/warn "Warning: could not request the review or set the assignee:"
                        (str/trim (str (:out r) (:err r)))))))
      ;; An open PR is held by its own label: the workflow reads it when CI completes.
      (when (and (:hold o) existing?)
        (let [r (shell/run "gh" "pr" "edit" pr-url "--add-label" "hold")]
          (when-not (zero? (:exit r))
            (shell/die (str pr-url
                            " could not get the label hold: "
                            (str/trim (str (:out r) (:err r)))
                            ". Fix: add the label on GitHub, or run kit finish --hold again.")))))
      ;; The owner's Approve is the word to merge: with auto-merge on, GitHub merges once the owner approved the
      ;; latest commit, the checks passed and the branch contains the latest base. A draft cannot have it: the
      ;; ready workflow turns it on after it marks the PR ready (ADR-0007).
      (when-not draft?
        (turn-on-auto-merge! pr-url))
      ;; A failed board update ends the run (#41). The PR is open by now, so only the status is left to set.
      (when (not-any? str/blank? [owner project-number review-status])
        (let [url (shell/gh-out "issue" "view" number
                    "-R"     repo
                    "--json" "url"
                    "--jq"   ".url")]
          (if (str/blank? url)
            (shell/warn (str "Warning: issue #" number " not found, Status not set"))
            (when-let [e (board/board-set-field project-number owner url status-field review-status)]
              (shell/die (str "The pull request is open: " pr-url
                              ". But the board " status-field
                              " of #" number
                              " is not '"
                              review-status
                              "': " (board/board-reason e)
                              ". Set it alone: "
                              (board/board-set-command kit project-number owner url status-field review-status)))))))
      (println (str "Subject: " subject))
      (println (str "PR URL: " pr-url))
      (when draft?
        (println
         (str
          "Draft: the owners are asked once its required checks pass; the ready workflow marks it ready and turns on auto-merge."
          (when (:hold o) " Held: when the earlier PR has merged, run kit release."))))
      (println (str "Status: " review-status)))))
