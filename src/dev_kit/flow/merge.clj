(ns dev-kit.flow.merge
  "kit merge: once the owner approved, merges and closes the issue out."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.shell :as shell]
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


(defn- dirty-die
  [url base]
  (shell/die (str "The branch conflicts with "
                  base
                  ": "
                  url
                  ". Fix: merge "
                  base
                  " into the branch (git fetch origin && "
                  "git merge origin/" base
                  ") and resolve the conflicts, then push; the push dismisses the owner's Approve, "
                  "so ask for it again.")))


(defn- close-linked-issues!
  "A pull request merged by auto-merge is merged by github-actions, and GitHub then closes none of its linked issues.
  Closes each one the PR names as closing that is still open, as completed, naming the PR; one line per issue."
  [pr-url]
  (doseq [{:keys [number url]} (:closingIssuesReferences
                                (wait/gh-json shell/die "pr" "view" pr-url "--json" "closingIssuesReferences"))]
    (if (= "OPEN" (shell/gh! "issue" "view" url "--json" "state" "--jq" ".state"))
      (let [r (shell/run "gh"        "issue"
                         "close"     url
                         "--reason"  "completed"
                         "--comment" (str "Closed by " pr-url ", which merged."))]
        (if (zero? (:exit r))
          (println (str "Closed #" number ": " url))
          (shell/warn (str "Warning: could not close #"
                           number
                           ": "
                           (str/trim (str (:out r) (:err r)))
                           ". Fix: gh issue close "
                           url
                           " --reason completed"))))
      (println (str "#" number " was closed already: " url)))))


(defn merge!
  [kit args]
  (let [o (shell/parse-opts merge-spec args)
        _ (when (:help o) (println merge-usage) (System/exit 0))
        owner (or (:owner o) (shell/env "GHWF_OWNER" nil))
        project-number (or (:project-number o) (shell/env "GHWF_PROJECT_NUMBER" nil))
        base (or (:base o) (shell/env "GHWF_DEFAULT_BASE" "main"))
        status-field (or (:status-field o) (shell/env "GHWF_STATUS_FIELD" "Status"))
        done-status (or (:done-status o) (shell/env "GHWF_DONE_STATUS" "Done"))
        repo (shell/resolve-repo (:repo o))
        branch (finish/git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (#{base "HEAD"} branch) (shell/die (str "Run from the issue branch, not '" branch "'")))
        number (or (shell/issue-branch-number branch) (shell/die (str "Branch '" branch "' is not <number>-<slug>")))
        poll (wait/pause)
        pr-list
        #(not-empty
          (shell/gh! "pr" "list" "-R" repo "--head" branch "--state" % "--json" "url" "--jq" ".[0].url // empty"))
        open-url (pr-list "open")
        shown (atom nil)
        ;; One look at the pull request. It ends the run when merging is impossible, and brings a branch that is behind
        ;; up to date. It returns the pull request as last read.
        look!
        (fn [url first?]
          (let [;; While GitHub recomputes the PR (UNKNOWN) its review decision can read empty for a moment: no stop
                ;; reason is judged on it, so it is read again. The wait ends when the state settles (#109).
                pr   (loop []
                       (let [p (wait/read-pr url repo shell/die)]
                         (if (and (= "OPEN" (:state p)) (= "UNKNOWN" (:merge-state p)))
                           (do (Thread/sleep (* 1000 poll)) (recur))
                           p)))
                ms   (:merge-state pr)
                seen (str (:state pr) " " ms)]
            (when (not= seen @shown) (println (str "GitHub reports " (:state pr) ", " ms ": " url)) (reset! shown seen))
            (cond
              (= "MERGED" (:state pr)) pr

              (= "CLOSED" (:state pr))
              (shell/die (str "The pull request was closed without merging: "
                              url
                              ". Branch '"
                              branch
                              "' is kept. Fix: reopen it "
                              "(gh pr reopen "
                              url
                              ") if it was closed by mistake, then run kit merge again."))

              (not= "APPROVED" (:decision pr))
              (shell/die
               (str (if first? "Waiting for the owner's Approve of the latest commit" "The owner's approval is gone")
                    " (review: " (str/lower-case (or (:decision pr) "none"))
                    "): "        url
                    ". Fix: ask the owner to approve the "
                    "latest commit, then run kit merge again."))

              (= "DIRTY" ms) (dirty-die url base)

              :else
              (let [failed (when (= "BLOCKED" ms) (wait/failed-checks (wait/required-checks url repo shell/die)))]
                (when (seq failed)
                  (shell/die (str
                              (wait/failed-text failed (str "on " url))
                              "\nFix: open the link and fix the cause, push, ask the owner to approve the new commit, "
                              "then run kit merge again.")))
                (if (= "BEHIND" ms)
                  ;; GitHub may have merged it since the last read, and then its branch is gone: look again first (#99).
                  ;; A refused update is no end: the next look reads why (a conflict, a merge that went through).
                  (let [again (wait/read-pr url repo shell/die)]
                    (when (and (= "OPEN" (:state again)) (= "BEHIND" (:merge-state again)))
                      (println (str "The branch is behind " base ": merging " base " into it; the approval stays."))
                      (when-not (zero? (shell/shell "gh" "pr" "update-branch" url))
                        (shell/warn "The update was refused; looking again.")))
                    again)
                  pr)))))
        pr-url
        (if-not open-url
          ;; Merged by GitHub's auto-merge after the owner's Approve: only the closeout is left.
          (let [u (or (pr-list "merged")
                      (shell/die (str "No open or merged pull request for branch '"
                                      branch
                                      "' in "
                                      repo
                                      "; if that is the wrong repository, pass --repo owner/name")))]
            (println (str "Already merged by GitHub: " u))
            u)
          (do
            (when-not (= "MERGED" (:state (look! open-url true)))
              ;; Auto-merge merges at once when everything is in place, or as soon as the checks pass. Without
              ;; auto-merge in the repository, a plain squash merge works when nothing is pending.
              (let [r (shell/run "gh" "pr" "merge" open-url "--auto" "--squash")]
                ;; A refusal is read against the PR's state first: one GitHub already merged needs no second try (#97).
                (when (and (not (zero? (:exit r)))
                           (not= "MERGED" (:state (wait/read-pr open-url repo shell/die))))
                  (shell/warn (str "Auto-merge not available ("
                                   (str/trim (str (:out r) (:err r)))
                                   "); trying a plain squash merge."))
                  (when-not (zero? (shell/shell "gh" "pr" "merge" open-url "--squash"))
                    (shell/die
                     (str
                      "Could not merge "
                      open-url
                      ": auto-merge is off in this repository, so nothing merges by itself. "
                      "Fix: if checks are still running, run this again when they pass; otherwise the owner turns on "
                      "Settings > General > 'Allow auto-merge' (dev-kit#37).")))))
              ;; Only a confirmed merge may delete the branch: cleaning up after a failed merge closes the pull request with
              ;; the work still in it. A branch can fall behind while it waits, when another pull request merges first;
              ;; auto-merge then waits for ever, since the base requires up-to-date branches, so every look updates it (#72).
              ;; The wait ends only in MERGED, or in a look that finds merging impossible (it ends the run).
              (loop []
                (when-not (= "MERGED" (:state (look! open-url false)))
                  (Thread/sleep (* 1000 poll))
                  (recur))))
            open-url))
        ;; A failed board update ends the run with an error (#41), after the closeout below: the merge is done.
        board-error
        (when (not-any? str/blank? [owner project-number])
          (let [url (shell/gh! "issue" "view" number "-R" repo "--json" "url" "--jq" ".url")]
            (when-let [e (board/board-set-field project-number owner url status-field done-status)]
              (str "The board " status-field
                   " of #"      number
                   " is not '"  done-status
                   "': "        (board/board-reason e)
                   ". Set it alone: "
                   (board/board-set-command kit project-number owner url status-field done-status)))))]
    ;; Leave the issue branch. When the base branch is checked out in another worktree, this one cannot switch to
    ;; it; it stays detached on the merged base instead.
    (shell/ok! (shell/shell "git" "fetch" "-q" "origin" base))
    (if (some #{(str "branch refs/heads/" base)} (str/split-lines (finish/git-out "worktree" "list" "--porcelain")))
      (shell/ok! (shell/shell "git" "switch" "-q" "--detach" (str "origin/" base)))
      (do (shell/ok! (shell/shell "git" "switch" "-q" base))
          (shell/ok! (shell/shell "git" "pull" "-q" "--ff-only"))))
    (shell/ok! (shell/shell "git" "branch" "-D" branch))
    (when (zero? (:exit (shell/run "git" "ls-remote" "--exit-code" "--heads" "origin" branch)))
      (shell/ok! (shell/shell "git" "push" "origin" "--delete" branch)))
    (println (str "Merged: " pr-url))
    (close-linked-issues! pr-url)
    (when board-error (shell/die board-error))
    (println (str "Status: " done-status))
    (println (str "Next: post the retrospective: kit retro " number))))
