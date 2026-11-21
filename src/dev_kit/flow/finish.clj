(ns dev-kit.flow.finish
  "kit finish: commits, pushes, opens the pull request and moves the card to In review. `finish` is the recipe, finish! its
  edge. A gh or git call only this command makes sits right above the step that needs it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.gate :as gate]
   [dev-kit.problem :as problem]))


;; ------------------------------------------------------------------ kit finish


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
      (problem/fail! :finish/detached-head
                     "Detached HEAD is not supported"
                     {:fix "check out the issue branch (git switch <number>-<slug>), then run kit finish again"}))
    (when (= branch base)
      (problem/fail! :finish/on-base
                     (format "On the base branch '%s'" base)
                     {:fix "run from the issue branch"}))
    {:branch branch
     :number (or
              (shell/issue-branch-number branch)
              (problem/fail! :finish/branch-without-number
                             (format "The branch '%s' is not <number>-<slug>" branch)
                             {:fix "open the issue with kit start and move the work to its branch"}))}))


(defn- check-files
  "Everything that can be wrong with the inputs is checked before the commit and the push: a run that dies after the
  push leaves a branch with no pull request and a confusing rerun."
  [{:keys [pr-body-file commit-message-file]}]
  (when (and pr-body-file (not (fs/readable? pr-body-file)))
    (problem/fail! :finish/no-body-file
                   (format "PR body file '%s' not found" pr-body-file)
                   {:fix
                    "write the pull request's description to that file, or pass the right path to --pr-body-file"}))
  (when (and commit-message-file (not (fs/readable? commit-message-file)))
    (problem/fail! :finish/no-message-file
                   (format "Commit message file '%s' not found" commit-message-file)
                   {:fix "write the commit message to that file, or pass the right path to --commit-message-file"}))
  {})


(defn- find-open-pr
  "Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones."
  [{:keys [repo branch]}]
  {:pr-url (github/open-pr-url repo branch "open")})


(defn- check-body-file
  "A PR to open needs its description."
  [{:keys [pr-url pr-body-file]}]
  (when-not (or pr-url pr-body-file)
    (problem/fail!
     :finish/body-file-required
     "--pr-body-file is required to open the pull request"
     {:fix
      "write the description (## What changed, ## Review, ## User-level test, ADR:) to a file and pass --pr-body-file <path>"}))
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
  [{:keys [commit-type number summary commit-message-file repo]}]
  {:subject (cond summary (format "%s(GH-%s): %s" commit-type number summary)
                  commit-message-file (first (str/split-lines (slurp commit-message-file)))
                  :else (let [title        (issue-title repo number)
                              first-letter (min 1 (count title))]
                          (format "%s(GH-%s): %s%s"
                                  commit-type
                                  number
                                  (str/lower-case (subs title 0 first-letter))
                                  (subs title first-letter))))})


(defn- check-shape
  "The title and the body pass the rules CI checks on the pull request (#21): one implementation, in kit. The body is
  checked only when it becomes a new PR's body; an open PR keeps its own."
  [{:keys [branch subject pr-url pr-body-file]}]
  (let [verdicts (gate/gate-shape {:branch branch :title subject :body-file (when-not pr-url pr-body-file)})]
    (when (seq verdicts)
      (problem/fail! :finish/shape
                     (format "The pull request would fail CI's shape check; nothing was committed or pushed:\n%s"
                             (str/join "\n" (map gate/verdict-line verdicts)))
                     {:fix "change the title or the body as the lines above say, then run kit finish again"}))
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
  (let [base-draft? (github/ready-workflow? repo base #(problem/fail! :finish/workflow-unreadable %1 {:fix %2}))]
    {:base-draft? base-draft?
     :draft?      (if pr-url
                    (pr-draft? pr-url)
                    base-draft?)}))


(defn- check-hold
  "--hold works through the ready workflow, which reads drafts only."
  [{:keys [repo base pr-url hold draft? base-draft?]}]
  (when (and hold pr-url (not draft?))
    (problem/fail!
     :finish/hold-on-ready-pr
     (format
      "%s is ready already: the workflow only reads drafts, so --hold would hold nothing. Nothing was committed or pushed."
      pr-url)
     {:fix
      (format
       "make it a draft first (gh pr ready %s --undo; that turns auto-merge off and keeps the owners' request) and run kit finish --hold again, or run kit finish without --hold"
       pr-url)}))
  (when (and hold (not base-draft?))
    (problem/fail!
     :finish/hold-without-workflow
     (format "--hold works through %s, which '%s' of %s does not hold yet. Nothing was committed or pushed."
             github/ready-workflow-path
             base
             repo)
     {:fix
      "run kit finish without --hold (the PR opens ready, the owners are asked at once), or after the dev-kit update PR with the file has merged"}))
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
        (problem/fail! :finish/hold-label
                       (format "Label 'hold' does not exist in %s and could not be created" repo)
                       {:fix "create it on GitHub (Issues > Labels), then run again"}))))
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
  [commit-message-file subject]
  (if commit-message-file
    (shell/run-on-terminal! "git" "commit" "-F" commit-message-file)
    (shell/run-on-terminal! "git" "commit" "-m" subject)))


(defn- commit
  [{:keys [no-commit add commit-message-file subject]}]
  (when-not no-commit
    (when (seq add)
      (stage! add))
    (when-not (staged?)
      (problem/fail! :finish/nothing-staged
                     "Nothing staged to commit"
                     {:fix "use --add <path> to stage files, or --no-commit when the work is committed already"}))
    (commit! commit-message-file subject))
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


(defn- closing-line
  "\"Part of #n\" for an issue of size L unless this is its last pull request, else \"Closes #n\". The size is read only
  when it matters."
  [repo number last?]
  (if (and (not last?) (= "L" (github/issue-size repo number)))
    (format "Part of #%s" number)
    (format "Closes #%s" number)))


(defn- open-pr
  "The body is the file's, with \"Closes #<issue>\" in front unless it has a closing keyword for that issue; an issue of
  size L gets \"Part of #<issue>\" instead, unless --last says this pull request is its last."
  [{:keys   [repo base branch number subject pr-body-file pr-url base-draft? hold]
    last-pr :last}]
  (if pr-url
    {}
    (let [text  (shell/file-text pr-body-file)
          body  (if (github/closes-issue? text number)
                  text
                  (format "%s\n\n%s" (closing-line repo number last-pr) text))
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
        (problem/fail! :finish/hold-label-refused
                       (format "%s could not get the label hold: %s"
                               pr-url
                               (str/trim (str (:out result) (:err result))))
                       {:fix "add the label on GitHub, or run kit finish --hold again."}))))
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
  [{:keys [repo number owner project-number status-field review-status pr-url]}]
  (when (not-any? str/blank? [owner project-number review-status])
    (let [{:keys [exit out]} (github/issue-url repo number)
          url (when (zero? exit)
                (str/trim out))]
      (if (str/blank? url)
        (shell/warn (format "Warning: issue #%s not found, Status not set" number))
        (when-let [failure (board/board-set-field project-number owner url status-field review-status)]
          (problem/fail!
           :finish/status-refused
           (format "The pull request is open: %s. But the board %s of #%s is not '%s': %s"
                   pr-url
                   status-field
                   number
                   review-status
                   (board/board-reason failure))
           {:fix (str "set it alone: "
                      (board/board-set-command project-number owner url status-field review-status))})))))
  {})


(defn finish
  "The recipe, from the command line's input. Every step before :commit only reads,
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
  "The options of the command line and the GHWF_* defaults as one map; env is (fn [name default]), shell/env in a run."
  [opts env]
  (assoc opts
         :owner         (or (:owner opts) (env "GHWF_OWNER" nil))
         :project-number (or (:project-number opts) (env "GHWF_PROJECT_NUMBER" nil))
         :base          (or (:base opts) (env "GHWF_DEFAULT_BASE" "main"))
         :status-field  (or (:status-field opts) (env "GHWF_STATUS_FIELD" "Status"))
         :review-status (or (:review-status opts) (env "GHWF_REVIEW_STATUS" "In review"))
         :reviewers     (env "GHWF_REVIEWERS" nil)
         :human         (env "GHWF_HUMAN" nil)))


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
  "kit finish: run the recipe on the real GitHub for the parsed options, print what it made, or throw the refusal, which the flow's edge prints."
  [opts]
  (print-result (steps/complete! (finish (read-input opts shell/env)))))
