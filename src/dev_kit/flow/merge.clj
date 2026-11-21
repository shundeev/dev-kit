(ns dev-kit.flow.merge
  "kit merge: once the owner approved, merges and closes the issue out. `merge-pr` is the recipe, merge! its edge."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.flow.wait :as wait]
   [dev-kit.problem :as problem]))


;; ------------------------------------------------------------------ kit merge


;; ------------------------------------------------------------------ the branch and its pull request


(defn- resolve-repo
  [{:keys [repo]}]
  {:repo (shell/resolve-repo repo)})


(defn- check-branch
  "The issue branch the run is on, and its issue number."
  [{:keys [base]}]
  (let [branch (github/head-branch)]
    (when (#{base "HEAD"} branch)
      (problem/fail!
       :merge/not-on-issue-branch
       (format "Run from the issue branch, not '%s'" branch)
       {:fix "check out the issue branch (kit start made it, named <number>-<slug>), then run kit merge again"}))
    {:branch branch
     :number (or (shell/issue-branch-number branch)
                 (problem/fail! :merge/not-an-issue-branch
                                (format "Branch '%s' is not <number>-<slug>" branch)
                                {:fix "check out the issue branch (kit start made it), then run kit merge again"}))}))


(defn- read-poll
  "The pause between two looks, in seconds. It is read before the first call about the pull request."
  [{:keys [merge-poll]}]
  {:poll (wait/poll-seconds merge-poll)})


(defn- find-pr
  "The pull request of the branch: the open one, else one GitHub merged by auto-merge after the owner's Approve (then
  only the closeout is left)."
  [{:keys [repo branch]}]
  (if-let [open-url (github/open-pr-url repo branch "open")]
    {:pr-url open-url :already-merged? false}
    (let
      [merged-url
       (or
        (github/open-pr-url repo branch "merged")
        (problem/fail!
         :merge/no-pr
         (format "No open or merged pull request for branch '%s' in %s" branch repo)
         {:fix "if that is the wrong repository, pass --repo owner/name; else open the pull request with kit finish"}))]
      (println (format "Already merged by GitHub: %s" merged-url))
      {:pr-url merged-url :already-merged? true})))


;; ------------------------------------------------------------------ waiting for the merge


(defn- read-pr
  [{:keys [repo pr-url]}]
  (github/read-pr pr-url repo #(problem/fail! :merge/pr-unreadable %1 {:fix %2})))


(defn- read-settled-pr
  "The pull request as read once GitHub has stopped recomputing it. While it does (UNKNOWN) its review decision can
  read empty for a moment: no stop reason is judged on it, so it is read again. The wait ends when the state settles
  (#109)."
  [{:keys [poll] :as facts}]
  (let [pr (read-pr facts)]
    (if (and (= "OPEN" (:state pr)) (= "UNKNOWN" (:merge-state pr)))
      (do (wait/sleep! poll) (recur facts))
      pr)))


(defn- update-branch!
  "gh pr update-branch: merges the base into the branch. The exit code, gh's own output on the terminal."
  [pr-url]
  (shell/run-on-terminal "gh" "pr" "update-branch" pr-url))


(defn- judge-pr
  "Ends the run when merging is impossible, and brings a branch that is behind up to date. Returns the pull request as
  last read."
  [{:keys [repo base branch pr-url] :as facts} first? pr]
  (let [merge-state (:merge-state pr)]
    (cond
      (= "MERGED" (:state pr)) pr

      (= "CLOSED" (:state pr))
      (problem/fail! :merge/closed
                     (format "The pull request was closed without merging: %s. Branch '%s' is kept." pr-url branch)
                     {:fix (format "reopen it (gh pr reopen %s) if it was closed by mistake, then run kit merge again."
                                   pr-url)})

      (not= "APPROVED" (:decision pr))
      (let [headline (if first? "Waiting for the owner's Approve of the latest commit" "The owner's approval is gone")
            review   (str/lower-case (or (:decision pr) "none"))]
        (problem/fail! :merge/not-approved
                       (format "%s (review: %s): %s" headline review pr-url)
                       {:fix "ask the owner to approve the latest commit, then run kit merge again."}))

      (= "DIRTY" merge-state)
      (problem/fail!
       :merge/conflict
       (format "The branch conflicts with %s: %s" base pr-url)
       {:fix
        (format
         "merge %s into the branch (git fetch origin && git merge origin/%s) and resolve the conflicts, then push; the push dismisses the owner's Approve, so ask for it again."
         base
         base)})

      :else
      (let [failed (when (= "BLOCKED" merge-state)
                     (wait/failed-checks
                      (github/required-checks pr-url repo #(problem/fail! :merge/checks-unreadable %1 {:fix %2}))))]
        (when (seq failed)
          (problem/fail!
           :merge/checks-failed
           (wait/failed-text failed (str "on " pr-url))
           {:fix
            "open the link and fix the cause, push, ask the owner to approve the new commit, then run kit merge again."}))
        (if (= "BEHIND" merge-state)
          ;; GitHub may have merged it since the last read, and then its branch is gone: look again first (#99).
          ;; A refused update is no end: the next look reads why (a conflict, a merge that went through).
          (let [again (read-pr facts)]
            (when (and (= "OPEN" (:state again)) (= "BEHIND" (:merge-state again)))
              (println (format "The branch is behind %s: merging %s into it; the approval stays." base base))
              (when-not (zero? (update-branch! pr-url))
                (shell/warn "The update was refused; looking again.")))
            again)
          pr)))))


(defn- squash-merge!
  "gh pr merge --squash: the exit code, gh's own output on the terminal."
  [pr-url]
  (shell/run-on-terminal "gh" "pr" "merge" pr-url "--squash"))


(defn- turn-on-the-merge
  "Auto-merge merges at once when everything is in place, or as soon as the checks pass. Without auto-merge in the
  repository, a plain squash merge works when nothing is pending."
  [{:keys [pr-url] :as facts}]
  (let [{:keys [exit out err]} (github/auto-merge! pr-url)]
    ;; A refusal is read against the PR's state first: one GitHub already merged needs no second try (#97).
    (when (and (not (zero? exit)) (not= "MERGED" (:state (read-pr facts))))
      (shell/warn (format "Auto-merge not available (%s); trying a plain squash merge." (str/trim (str out err))))
      (when-not (zero? (squash-merge! pr-url))
        (problem/fail!
         :merge/not-merged
         (format "Could not merge %s: auto-merge is off in this repository, so nothing merges by itself." pr-url)
         {:fix
          "if checks are still running, run this again when they pass; otherwise the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37)."})))))


(defn- wait-for-the-merge
  "Looks at the pull request until GitHub reports it MERGED: the first look judges it, then turns on the merge; each
  later look follows a pause. A line says what GitHub reports whenever that changes, which tells the person the wait is
  alive. Only a confirmed merge may delete the branch: cleaning up after a failed merge closes the pull request with
  the work still in it. A branch can fall behind while it waits, when another pull request merges first; auto-merge
  then waits for ever, since the base requires up-to-date branches, so every look updates it (#72). The wait ends only
  in MERGED, or in a look that finds merging impossible."
  [{:keys [pr-url poll already-merged?] :as facts}]
  (when-not already-merged?
    (loop [first? true
           last-printed-state nil]
      (let [pr    (read-settled-pr facts)
            state (str (:state pr) " " (:merge-state pr))]
        (when (not= state last-printed-state)
          (println (format "GitHub reports %s, %s: %s" (:state pr) (:merge-state pr) pr-url)))
        (when-not (= "MERGED" (:state (judge-pr facts first? pr)))
          (if first?
            (turn-on-the-merge facts)
            (wait/sleep! poll))
          (recur false state)))))
  {})


;; ------------------------------------------------------------------ the closeout


(defn- closing-issues
  "The issues the pull request closes, {:number :url} each; an unreadable answer goes to `fail`."
  [pr-url fail]
  (:closingIssuesReferences (github/gh-json fail "pr" "view" pr-url "--json" "closingIssuesReferences")))


(defn- pr-body
  "The pull request's description."
  [pr-url fail]
  (:body (github/gh-json fail "pr" "view" pr-url "--json" "body")))


(defn- decide-what-closes
  "Which issues the merge closes. An issue of size L (the issue of the branch, and each the pull request links) is
  closed only by a pull request whose body says so: GitHub lists the issue of a branch made by gh issue develop as a
  closing reference on some pull requests and not on others, so for L the body decides. :closing is what GitHub lists;
  :stays-open the numbers (as text) of the L issues this pull request does not close."
  [{:keys [repo number pr-url]}]
  (let [fail    #(problem/fail! :merge/closing-issues-unreadable %1 {:fix %2})
        closing (closing-issues pr-url fail)
        large   (filter #(= "L" (github/issue-size repo %)) (distinct (map str (cons number (map :number closing)))))
        body    (when (seq large)
                  (pr-body pr-url fail))]
    {:closing    closing
     :stays-open (set (remove #(github/closes-issue? body %) large))}))


(defn- mark-done-on-board
  "The card's Status to Done, unless its issue stays open (size L, not closed by this pull request). A failed board
  update ends the run with an error (#41), after the closeout: the merge is done, so the error is kept as a fact for
  the last step."
  [{:keys [repo number owner project-number status-field done-status stays-open]}]
  (if (or (some str/blank? [owner project-number]) (contains? stays-open number))
    {}
    (let [{:keys [exit out err]} (github/issue-url repo number)]
      (when-not (zero? exit)
        (problem/fail!
         :merge/issue-unreadable
         (if (str/blank? err)
           (format "gh issue view failed with exit code %s and printed no message" exit)
           (str/trim err))
         {:exit exit :fix "run the same call by hand to see why."}))
      (let [url (str/trim out)]
        (if-let [failure (board/board-set-field project-number owner url status-field done-status)]
          {:board-error (format "The board %s of #%s is not '%s': %s"
                                status-field
                                number
                                done-status
                                (board/board-reason failure))
           :board-fix   (str "set it alone: "
                             (board/board-set-command project-number owner url status-field done-status))}
          {})))))


(defn- fetch-base!
  "git fetch of the base branch; git's own exit code stops the step when it fails."
  [base]
  (shell/run-on-terminal! "git" "fetch" "-q" "origin" base))


(defn- worktree-branches
  "The branches checked out in the clone's worktrees."
  []
  (let [listing (shell/git-out "worktree" "list" "--porcelain")]
    (for [line  (str/split-lines listing)
          :when (str/starts-with? line "branch refs/heads/")]
      (str/replace-first line "branch refs/heads/" ""))))


(defn- detach-at-base!
  [base]
  (shell/run-on-terminal! "git" "switch" "-q" "--detach" (str "origin/" base)))


(defn- switch-to!
  [branch]
  (shell/run-on-terminal! "git" "switch" "-q" branch))


(defn- pull-ff-only!
  []
  (shell/run-on-terminal! "git" "pull" "-q" "--ff-only"))


(defn- leave-branch
  "Leaves the issue branch. When the base branch is checked out in another worktree, this one cannot switch to it; it
  stays detached on the merged base instead."
  [{:keys [base]}]
  (fetch-base! base)
  (if (some #{base} (worktree-branches))
    (detach-at-base! base)
    (do (switch-to! base)
        (pull-ff-only!)))
  {})


(defn- delete-local-branch!
  [branch]
  (shell/run-on-terminal! "git" "branch" "-D" branch))


(defn- remote-branch?
  [branch]
  (zero? (:exit (shell/run "git" "ls-remote" "--exit-code" "--heads" "origin" branch))))


(defn- delete-remote-branch!
  [branch]
  (shell/run-on-terminal! "git" "push" "origin" "--delete" branch))


(defn- delete-branches
  "The merged branch, here and on GitHub."
  [{:keys [branch]}]
  (delete-local-branch! branch)
  (when (remote-branch? branch)
    (delete-remote-branch! branch))
  {})


(defn- report-merged
  [{:keys [pr-url]}]
  (println (format "Merged: %s" pr-url))
  {})


(defn- issue-state
  [url]
  (shell/gh! "issue" "view" url
    "--json" "state"
    "--jq"   ".state"))


(defn- close-issue!
  "gh issue close as completed, with the note as its comment: {:exit :out :err}."
  [url note]
  (shell/run "gh" "issue" "close" url
    "--reason"  "completed"
    "--comment" note))


(defn- close-linked-issues
  "A pull request merged by auto-merge is merged by github-actions, and GitHub then closes none of its linked issues.
  Closes each one the PR names as closing that is still open, as completed, naming the PR; one line per issue."
  [{:keys [pr-url closing stays-open]}]
  (doseq [number (sort stays-open)]
    (println
     (format
      "#%s stays open: it is size L and this pull request says only \"Part of\". The last pull request closes it: kit finish --last."
      number)))
  (doseq [{:keys [number url]} (remove #(contains? stays-open (str (:number %))) closing)]
    (if (= "OPEN" (issue-state url))
      (let [closed (close-issue! url (format "Closed by %s, which merged." pr-url))]
        (if (zero? (:exit closed))
          (println (format "Closed #%s: %s" number url))
          (shell/warn (format "Warning: could not close #%s: %s. Fix: gh issue close %s --reason completed"
                              number
                              (str/trim (str (:out closed) (:err closed)))
                              url))))
      (println (format "#%s was closed already: %s" number url))))
  {})


(defn- report-board-error
  "Raises the board error `mark-done-on-board` kept, now that the closeout is done."
  [{:keys [board-error board-fix]}]
  (when board-error
    (problem/fail! :merge/board-refused board-error {:fix board-fix}))
  {})


(defn merge-pr
  "kit merge as a recipe, from the command line's input. The lines printed while it
  waits go to stdout, the warnings to stderr. A board error is kept as a fact and raised by the last step, after the
  closeout: the merge is done by then."
  [input]
  (steps/run input
    :resolve-repo        resolve-repo
    :check-branch        check-branch
    :read-poll           read-poll
    :find-pr             find-pr
    :wait-for-the-merge  wait-for-the-merge
    :decide-what-closes  decide-what-closes
    :mark-done-on-board  mark-done-on-board
    :leave-branch        leave-branch
    :delete-branches     delete-branches
    :report-merged       report-merged
    :close-linked-issues close-linked-issues
    :report-board-error  report-board-error))


;; ------------------------------------------------------------------ the edges


(defn read-input
  "The options of the command line and the GHWF_* defaults as one map; env is (fn [name default]), shell/env in a run."
  [opts env]
  (assoc opts
         :owner          (or (:owner opts) (env "GHWF_OWNER" nil))
         :project-number (or (:project-number opts) (env "GHWF_PROJECT_NUMBER" nil))
         :base           (or (:base opts) (env "GHWF_DEFAULT_BASE" "main"))
         :status-field   (or (:status-field opts) (env "GHWF_STATUS_FIELD" "Status"))
         :done-status    (or (:done-status opts) (env "GHWF_DONE_STATUS" "Done"))
         :merge-poll     (env "GHWF_MERGE_POLL" "10")))


(defn print-result
  "What the person reads last, once the run went through."
  [{:keys [done-status number stays-open]}]
  (when-not (contains? stays-open number)
    (println (format "Status: %s" done-status))
    (println (format "Next: post the retrospective: kit retro %s" number))))


(defn merge!
  "kit merge: run the recipe on the real GitHub for the parsed options, print what it did, or throw the refusal, which the
  flow's edge prints (a board error comes last, after the closeout)."
  [opts]
  (print-result (steps/complete! (merge-pr (read-input opts shell/env)))))
