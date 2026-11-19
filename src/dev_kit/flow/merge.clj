(ns dev-kit.flow.merge
  "kit merge: once the owner approved, merges and closes the issue out. `merge-pr` is the recipe, merge! its edge."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.flow.wait :as wait]))


;; ------------------------------------------------------------------ kit merge


(def merge-usage
  "Usage: kit merge [options]

Run from the issue branch, as a background command: the agent is woken when it exits. When the pull
request is open, it needs the owner's Approve of the latest commit. A branch behind the base is
updated by merging the base into it, which keeps the approval. It turns on auto-merge (squash) and
waits until GitHub reports the pull request MERGED, or until merging became impossible (closed, a
required check failed, DIRTY, the approval gone); it then stops and names the reason.
When GitHub already merged it, only the closeout runs: board Status \"Done\", the branch deleted
locally and on GitHub, back to the base branch; each issue the PR closes that is still open is closed as completed
(auto-merge merges as github-actions, and GitHub then closes none). A pull request that did not merge keeps its branch.

Options:
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --done-status <option>        Default: Done
  --help                        Show this help

Environment: GHWF_MERGE_POLL, seconds between looks (default 10). Nothing else bounds the wait.")


(def merge-spec
  {"--repo"        [:repo :value]
   "--owner"       [:owner :value]
   "--project-number" [:project-number :value]
   "--base"        [:base :value]
   "--status-field" [:status-field :value]
   "--done-status" [:done-status :value]
   "--help"        [:help :flag]
   "-h"            [:help :flag]})


;; ------------------------------------------------------------------ the branch and its pull request


(defn- resolve-repo
  [{:keys [repo]}]
  {:repo (shell/resolve-repo repo)})


(defn- check-branch
  "The issue branch the run is on, and its issue number."
  [{:keys [base]}]
  (let [branch (github/head-branch)]
    (when (#{base "HEAD"} branch)
      (steps/fail! :merge/not-on-issue-branch (format "Run from the issue branch, not '%s'" branch)))
    {:branch branch
     :number (or (shell/issue-branch-number branch)
                 (steps/fail! :merge/not-an-issue-branch (format "Branch '%s' is not <number>-<slug>" branch)))}))


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
        (steps/fail!
         :merge/no-pr
         (format
          "No open or merged pull request for branch '%s' in %s; if that is the wrong repository, pass --repo owner/name"
          branch
          repo)))]
      (println (format "Already merged by GitHub: %s" merged-url))
      {:pr-url merged-url :already-merged? true})))


;; ------------------------------------------------------------------ waiting for the merge


(defn- read-pr
  [{:keys [repo pr-url]}]
  (github/read-pr pr-url repo #(steps/fail! :merge/pr-unreadable %)))


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
      (steps/fail!
       :merge/closed
       (format
        "The pull request was closed without merging: %s. Branch '%s' is kept. Fix: reopen it (gh pr reopen %s) if it was closed by mistake, then run kit merge again."
        pr-url
        branch
        pr-url))

      (not= "APPROVED" (:decision pr))
      (let [headline (if first? "Waiting for the owner's Approve of the latest commit" "The owner's approval is gone")
            review   (str/lower-case (or (:decision pr) "none"))]
        (steps/fail!
         :merge/not-approved
         (format "%s (review: %s): %s. Fix: ask the owner to approve the latest commit, then run kit merge again."
                 headline
                 review
                 pr-url)))

      (= "DIRTY" merge-state)
      (steps/fail!
       :merge/conflict
       (format
        "The branch conflicts with %s: %s. Fix: merge %s into the branch (git fetch origin && git merge origin/%s) and resolve the conflicts, then push; the push dismisses the owner's Approve, so ask for it again."
        base
        pr-url
        base
        base))

      :else
      (let [failed (when (= "BLOCKED" merge-state)
                     (wait/failed-checks
                      (github/required-checks pr-url repo #(steps/fail! :merge/checks-unreadable %))))]
        (when (seq failed)
          (steps/fail!
           :merge/checks-failed
           (format
            "%s\nFix: open the link and fix the cause, push, ask the owner to approve the new commit, then run kit merge again."
            (wait/failed-text failed (str "on " pr-url)))))
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
        (steps/fail!
         :merge/not-merged
         (format
          "Could not merge %s: auto-merge is off in this repository, so nothing merges by itself. Fix: if checks are still running, run this again when they pass; otherwise the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37)."
          pr-url))))))


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


(defn- mark-done-on-board
  "The card's Status to Done. A failed board update ends the run with an error (#41), after the closeout: the merge
  is done, so the error is kept as a fact for the last step."
  [{:keys [kit repo number owner project-number status-field done-status]}]
  (if (some str/blank? [owner project-number])
    {}
    (let [{:keys [exit out err]} (github/issue-url repo number)]
      (when-not (zero? exit)
        (steps/fail!
         :merge/issue-unreadable
         (if (str/blank? err)
           (format
            "Error: gh issue view failed with exit code %s and printed no message. Run the same call by hand to see why."
            exit)
           (str/trim err))
         {:exit exit :plain true}))
      (let [url (str/trim out)]
        (if-let [failure (board/board-set-field project-number owner url status-field done-status)]
          {:board-error (format "The board %s of #%s is not '%s': %s. Set it alone: %s"
                                status-field
                                number
                                done-status
                                (board/board-reason failure)
                                (board/board-set-command kit project-number owner url status-field done-status))}
          {})))))


(defn- fetch-base!
  "git fetch of the base branch; git's own exit code ends the run when it fails."
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


(defn- closing-issues
  "The issues the pull request closes, {:number :url} each; an unreadable answer goes to `fail`."
  [pr-url fail]
  (:closingIssuesReferences (github/gh-json fail "pr" "view" pr-url "--json" "closingIssuesReferences")))


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
  [{:keys [pr-url]}]
  (doseq [{:keys [number url]} (closing-issues pr-url #(steps/fail! :merge/closing-issues-unreadable %))]
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
  [{:keys [board-error]}]
  (when board-error
    (steps/fail! :merge/board-refused board-error))
  {})


(defn merge-pr
  "kit merge as a recipe, from the command line's input (with the path of bin/kit as :kit). The lines printed while it
  waits go to stdout, the warnings to stderr. A board error is kept as a fact and raised by the last step, after the
  closeout: the merge is done by then."
  [input]
  (steps/run input
    :resolve-repo        resolve-repo
    :check-branch        check-branch
    :read-poll           read-poll
    :find-pr             find-pr
    :wait-for-the-merge  wait-for-the-merge
    :mark-done-on-board  mark-done-on-board
    :leave-branch        leave-branch
    :delete-branches     delete-branches
    :report-merged       report-merged
    :close-linked-issues close-linked-issues
    :report-board-error  report-board-error))


;; ------------------------------------------------------------------ the edges


(defn read-input
  "The command line and the GHWF_* defaults as one map; env is (fn [name default]), shell/env in a run."
  [args env]
  (let [opts (shell/parse-opts merge-spec args)]
    (if (:help opts)
      opts
      (assoc opts
             :owner          (or (:owner opts) (env "GHWF_OWNER" nil))
             :project-number (or (:project-number opts) (env "GHWF_PROJECT_NUMBER" nil))
             :base           (or (:base opts) (env "GHWF_DEFAULT_BASE" "main"))
             :status-field   (or (:status-field opts) (env "GHWF_STATUS_FIELD" "Status"))
             :done-status    (or (:done-status opts) (env "GHWF_DONE_STATUS" "Done"))
             :merge-poll     (env "GHWF_MERGE_POLL" "10")))))


(defn print-result
  "What the person reads last, once the run went through."
  [{:keys [done-status number]}]
  (println (format "Status: %s" done-status))
  (println (format "Next: post the retrospective: kit retro %s" number)))


(defn merge!
  "kit merge: read the command line, run the recipe on the real GitHub, print what it did, or exit with the refusal
  (Error: and the reason, exit 1; a board error comes last, after the closeout)."
  [kit args]
  (let [input (read-input args shell/env)]
    (when (:help input)
      (println merge-usage)
      (System/exit 0))
    (let [result (merge-pr (assoc input :kit kit))]
      (when (= :fail (::steps/status result))
        (shell/exit-with! (steps/refusal result)))
      (print-result result))))
