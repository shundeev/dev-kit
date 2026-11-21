(ns dev-kit.flow.merge-test
  "kit merge, told as the owner's stories (#139, ADR-0008). Each runs the command against an in-memory GitHub, a clock
  that plays what happens while it waits, and says what is left afterwards."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.merge :as merge-cmd]
   [dev-kit.flow.steps :as steps]))


(def ^:private issue-url "https://github.com/o/r/issues/1")


(defn- approved-pr
  "The pull request of branch 1-add-page: approved, nothing pending. It is number 3, after two issues."
  [& {:as more}]
  (merge {:branch "1-add-page" :decision "APPROVED" :merge-state "CLEAN" :closes [1 2]} more))


(defn- clone
  "A world where the clone is on its issue branch, with the issues the pull request closes (the second is closed
  already) and the pull requests given."
  [prs & {:as more}]
  (fake/new-world (merge {:issues [{:title "Add page" :on-board true} {:title "Fix typo" :state "CLOSED"}]
                          :prs prs
                          :current-branch "1-add-page"
                          :local-branches ["1-add-page" "main"]
                          :remote-branches ["1-add-page" "main"]}
                         more)))


(def ^:private variables {"GHWF_OWNER" "u473t8" "GHWF_PROJECT_NUMBER" "3" "GHWF_MERGE_POLL" "10"})


(defn- kit-merge
  "Runs kit merge with the command-line words and the variables against the world. Returns the result, with the lines
  it printed under :lines and the warnings under :warnings; what it printed last comes after the recipe, as in a run."
  [world env & args]
  (let [out    (java.io.StringWriter.)
        err    (java.io.StringWriter.)
        input  (merge-cmd/read-input (vec args) (fn [variable default] (get (merge variables env) variable default)))
        result (binding [*out* out
                         *err* err]
                 (let [result (fake/with-github world #(merge-cmd/merge-pr input))]
                   (when (= :ok (::steps/status result))
                     (merge-cmd/print-result result))
                   result))]
    (assoc result :lines (str/split-lines (str out)) :warnings (remove empty? (str/split-lines (str err))))))


(defn- said
  [result]
  (:text (steps/refusal result)))


(defn- left-alone?
  "Nothing of the closeout happened: the clone is on its branch, which is kept here and on GitHub."
  [world]
  (and (= "1-add-page" (fake/checked-out world))
       (contains? (fake/local-branches world) "1-add-page")
       (contains? (fake/remote-branches world) "1-add-page")))


(defn- status-of-card [world] (get-in (first (fake/cards world)) [:fields "Status"]))


(defn- issue-states [world] (mapv :state (fake/issues world)))


(deftest an-approved-pull-request-is-waited-for-and-then-closed-out
  (let [world  (clone [(approved-pr)] :script [(fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)) "the merge goes through")
    (is (= [10] (fake/slept world)) "it waited one pause, GHWF_MERGE_POLL seconds long")
    (is (:auto-merge (first (fake/prs world))) "auto-merge was turned on")
    (is (= ["GitHub reports OPEN, CLEAN: https://github.com/o/r/pull/3"
            "GitHub reports MERGED, CLEAN: https://github.com/o/r/pull/3"
            "Merged: https://github.com/o/r/pull/3"
            "Closed #1: https://github.com/o/r/issues/1"
            "#2 was closed already: https://github.com/o/r/issues/2"
            "Status: Done"
            "Next: post the retrospective: kit retro 1"]
           (:lines result))
        "it says what it sees while waiting, then the closeout, in this order")
    (is (= "main" (fake/checked-out world)) "the clone is back on main")
    (is (= #{"main"} (fake/local-branches world)) "the branch is gone here")
    (is (= #{"main"} (fake/remote-branches world)) "and on GitHub")
    (is (= "Done" (status-of-card world)) "the card is Done")
    (is (= ["CLOSED" "CLOSED"] (issue-states world)) "the issue it closes is closed")
    (is (= "Closed by https://github.com/o/r/pull/3, which merged." (:closed-with (first (fake/issues world))))
        "naming the pull request")))


(deftest a-pull-request-that-github-merged-already-gets-only-its-closeout
  (let [world  (clone [(approved-pr :state "MERGED")])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= "Already merged by GitHub: https://github.com/o/r/pull/3" (first (:lines result))))
    (is (= [] (fake/slept world)) "no waiting")
    (is (not (:auto-merge (first (fake/prs world)))) "no merge asked for")
    (is (= "Done" (status-of-card world)))
    (is (= ["CLOSED" "CLOSED"] (issue-states world)))
    (is (= "main" (fake/checked-out world)))
    (is (= #{"main"} (fake/local-branches world)))))


(deftest a-pull-request-merged-while-it-waited-is-not-merged-again
  (let [world  (clone [(approved-pr)] :script [(fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (nil? (:squashed (first (fake/prs world)))) "no plain merge on top of GitHub's")
    (is (nil? (:updates (first (fake/prs world)))) "no update of the branch")))


(def ^:private stops
  [{:story  "without the owner's Approve it stops, names the review and changes nothing"
    :pr     (approved-pr :decision nil)
    :script []
    :says   ["Waiting for the owner's Approve of the latest commit (review: none): https://github.com/o/r/pull/3"
             "Fix: ask the owner to approve the latest commit, then run kit merge again."]}
   {:story  "a request for changes is named in the review"
    :pr     (approved-pr :decision "CHANGES_REQUESTED")
    :script []
    :says   ["Waiting for the owner's Approve of the latest commit (review: changes_requested)"]}
   {:story  "the approval lost while waiting stops with the approval gone"
    :pr     (approved-pr)
    :script [(fake/pr-becomes 3 {:decision nil})]
    :says   ["The owner's approval is gone (review: none): https://github.com/o/r/pull/3"]}
   {:story
    "a conflict that appears while waiting stops, says how to merge main in and that the push dismisses the Approve"
    :pr (approved-pr)
    :script [(fake/pr-becomes 3 {:merge-state "DIRTY"})]
    :says ["The branch conflicts with main: https://github.com/o/r/pull/3"
           "git merge origin/main"
           "the push dismisses the owner's Approve"]}
   {:story  "a failed required check stops and names the check and its link"
    :pr     (approved-pr)
    :script [(fake/pr-becomes 3 {:merge-state "BLOCKED" :checks [{:name "lint" :bucket "fail" :link "https://ci/1"}]})]
    :says   ["1 required check failed on https://github.com/o/r/pull/3:\n  lint: https://ci/1\nFix: open the link"]}
   {:story  "a pull request closed while waiting stops and names the kept branch"
    :pr     (approved-pr)
    :script [(fake/pr-becomes 3 {:state "CLOSED"})]
    :says   ["The pull request was closed without merging: https://github.com/o/r/pull/3. Branch '1-add-page' is kept."
             "gh pr reopen https://github.com/o/r/pull/3"]}])


(deftest every-stop-names-its-reason-and-keeps-the-branch
  (doseq [{:keys [story pr script says]} stops]
    (let [world  (clone [pr] :script script)
          result (kit-merge world {})
          text   (:text (steps/refusal result))]
      (is (= :fail (::steps/status result)) story)
      (is (= 1 (:exit (steps/refusal result))) story)
      (doseq [part says]
        (is (str/includes? text part) (format "%s: says %s" story part)))
      (is (left-alone? world) (format "%s: the branch is kept here and on GitHub" story))
      (is (nil? (status-of-card world)) (format "%s: the card is not moved" story))
      (is (= ["OPEN" "CLOSED"] (issue-states world)) (format "%s: no issue is closed" story)))))


(deftest a-branch-that-falls-behind-is-updated-once-and-the-wait-goes-on
  (let [world  (clone [(approved-pr :merge-state "BEHIND")] :script [(fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= 1 (:updates (first (fake/prs world)))) "the branch was updated once")
    (is (some #{"The branch is behind main: merging main into it; the approval stays."} (:lines result)))
    (is (= [10] (fake/slept world)))))


(deftest a-refused-update-is-no-end-of-the-wait
  (let [world  (clone [(approved-pr :merge-state "BEHIND" :refuse-update true)]
                      :script
                      [(fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= ["The update was refused; looking again." "The update was refused; looking again."] (:warnings result))
        "it says so at each look, and looks again")
    (is (nil? (:updates (first (fake/prs world)))))))


(deftest a-state-that-github-still-computes-is-read-again-not-taken-for-a-lost-approval
  (let [world  (clone [(approved-pr :decision nil :merge-state "UNKNOWN")]
                      :script
                      [(fake/pr-becomes 3 {:decision "APPROVED" :merge-state "CLEAN"})
                       (fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= [10 10] (fake/slept world)) "one pause for the recomputing, one for the merge")))


(deftest without-auto-merge-in-the-repository-a-plain-squash-merge-is-tried
  (let [world  (clone [(approved-pr :refuse-auto-merge "Auto merge is not allowed for this repository")])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= ["Auto-merge not available (Auto merge is not allowed for this repository); trying a plain squash merge."]
           (:warnings result)))
    (is (:squashed (first (fake/prs world))) "the pull request was squash-merged")
    (is (= [] (fake/slept world)))))


(deftest when-both-merges-are-refused-it-names-the-fix
  (let [world  (clone [(approved-pr :refuse-auto-merge "no" :refuse-squash true)])
        result (kit-merge world {})]
    (is (= :fail (::steps/status result)))
    (is (str/includes? (said result) "Could not merge https://github.com/o/r/pull/3: auto-merge is off"))
    (is (str/includes? (said result) "Allow auto-merge"))
    (is (left-alone? world))))


(deftest a-merge-that-github-did-meanwhile-needs-no-warning-and-no-second-merge
  (let [world  (clone [(approved-pr :refuse-auto-merge "already merged" :merges-meanwhile true)])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= [] (:warnings result)))
    (is (nil? (:squashed (first (fake/prs world)))))))


(deftest an-issue-that-is-left-open-is-warned-about-with-the-command-that-closes-it
  (let [world  (clone [(approved-pr :state "MERGED")] :unclosable [1])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)) "the merge is done, so the run goes on")
    (is
     (=
      ["Warning: could not close #1: GraphQL: the fake GitHub refuses to close it. Fix: gh issue close https://github.com/o/r/issues/1 --reason completed"]
      (:warnings result)))))


(deftest a-board-that-refuses-done-fails-last-after-the-merge-and-the-closeout
  (let [world  (clone [(approved-pr :state "MERGED")] :refuse-field "Status")
        result (kit-merge world {})]
    (is (= :fail (::steps/status result)))
    (is (= :report-board-error (::steps/stopped-at result)) "it is the last step that fails")
    (is (= 1 (:exit (steps/refusal result))))
    (is (str/starts-with? (:text (steps/refusal result)) "The board Status of #1 is not 'Done': "))
    (is (str/includes?
         (said result)
         (format "Fix: set it alone: bb %s board set 3 u473t8 %s Status Done" board/kit-script issue-url)))
    (is (= "main" (fake/checked-out world)) "the clone left the branch")
    (is (= #{"main"} (fake/local-branches world)) "the branch is gone")
    (is (= ["CLOSED" "CLOSED"] (issue-states world)) "the issues are closed")
    (is (not-any? #(str/starts-with? % "Status:") (:lines result)) "no Status line is printed")))


(deftest without-a-board-the-closeout-still-says-done
  (let [world  (clone [(approved-pr :state "MERGED")])
        result (kit-merge world {"GHWF_OWNER" ""})]
    (is (= :ok (::steps/status result)))
    (is (nil? (status-of-card world)) "the card is not touched")
    (is (= ["Status: Done" "Next: post the retrospective: kit retro 1"] (take-last 2 (:lines result))))))


(deftest a-branch-with-neither-an-open-nor-a-merged-pull-request-names-the-repository
  (let [world  (clone [(approved-pr :branch "5-other")])
        result (kit-merge world {})]
    (is (= :fail (::steps/status result)))
    (is (str/includes? (said result) "No open or merged pull request for branch '1-add-page' in o/r"))
    (is (str/includes? (said result) "pass --repo owner/name"))
    (is (left-alone? world))))


(deftest an-issue-that-does-not-exist-ends-the-run-with-ghs-own-words
  (let [world   (clone [(approved-pr :branch "9-ghost" :state "MERGED")] :current-branch "9-ghost")
        result  (kit-merge world {})
        refusal (steps/refusal result)]
    (is (= :fail (::steps/status result)))
    (is (= {:text "Could not resolve to an issue with the number of 9. Fix: run the same call by hand to see why."
            :exit 1}
           refusal)
        "gh's words, without Error:, and its exit code")
    (is (= "9-ghost" (fake/checked-out world)) "nothing of the closeout ran")))


(deftest the-base-branch-checked-out-in-another-worktree-leaves-this-one-detached
  (let [world  (clone [(approved-pr :state "MERGED")] :other-worktrees ["main"])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= "HEAD" (fake/checked-out world)) "detached on the merged base")
    (is (= #{"main"} (fake/local-branches world)) "the issue branch is gone")))


(deftest a-run-from-the-wrong-branch-stops-before-asking-github
  (doseq [[branch says] [["main" "Run from the issue branch, not 'main'"]
                         ["HEAD" "Run from the issue branch, not 'HEAD'"]
                         ["fix-typo" "Branch 'fix-typo' is not <number>-<slug>"]]]
    (let [world  (clone [(approved-pr)] :current-branch branch)
          result (kit-merge world {})]
      (is (= :fail (::steps/status result)) branch)
      (is (str/starts-with? (:text (steps/refusal result)) (format "%s. Fix: check out the issue branch" says))
          branch)
      (is (not (:auto-merge (first (fake/prs world)))) (format "%s: nothing was asked of the pull request" branch)))))


(deftest a-bad-poll-stops-before-any-pull-request-is-looked-at
  (let [world  (clone [(approved-pr)])
        result (kit-merge world {"GHWF_MERGE_POLL" "soon"})]
    (is (= :fail (::steps/status result)))
    (is (= (str "GHWF_MERGE_POLL is 'soon': give it a whole number of seconds, or unset it for 10. "
                "Fix: set GHWF_MERGE_POLL to a whole number of seconds (0 or more), or unset it")
           (:text (steps/refusal result))))
    (is (= :read-poll (::steps/stopped-at result)))
    (is (not (:auto-merge (first (fake/prs world)))))
    (is (left-alone? world))))


;; L issues: one issue, several pull requests; only the one that says "Closes" closes it (#139, step 8)


(defn- size-world
  "A world where issue #1 has the size and the pull request has the body; GitHub lists #1 as closed by it, as it does
  for a branch made by gh issue develop."
  [size body]
  (clone [(approved-pr :body body :closes [1])]
         :issues [{:title "Add page" :on-board true :labels [(str "size:" size)]}]
         :script [(fake/pr-becomes 2 {:state "MERGED"})]))


(def ^:private stays-open-line
  "#1 stays open: it is size L and this pull request says only \"Part of\". The last pull request closes it: kit finish --last.")


(deftest an-l-issue-stays-open-and-in-progress-when-one-of-its-pull-requests-merges
  (let [world  (size-world "L" "Part of #1\n\nOne step.")
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)) "the merge goes through")
    (is (= ["OPEN"] (issue-states world)) "the issue is not closed")
    (is (nil? (status-of-card world)) "its card keeps its Status, not Done")
    (is (= [stays-open-line] (filter #(str/includes? % "stays open") (:lines result)))
        "and the line says how to close it")
    (is (not-any? #(str/starts-with? % "Status:") (:lines result)) "no Done is announced")
    (is (= #{"main"} (fake/local-branches world)) "the branch is cleaned up all the same")))


(deftest the-pull-request-that-says-closes-closes-the-l-issue-and-marks-it-done
  (let [world  (size-world "L" "Closes #1\n\nThe last step.")
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= ["CLOSED"] (issue-states world)) "the issue is closed")
    (is (= "Done" (status-of-card world)) "its card is Done")
    (is (some #{"Closed #1: https://github.com/o/r/issues/1"} (:lines result)))))


(deftest an-l-issue-the-branch-links-only-by-name-still-follows-the-body
  (let [world  (clone [(approved-pr :body "Part of #1" :closes [])]
                      :issues [{:title "Add page" :on-board true :labels ["size:L"]}]
                      :script [(fake/pr-becomes 2 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= ["OPEN"] (issue-states world)) "no closing reference listed, and the body does not close it")
    (is (nil? (status-of-card world)))
    (is (some #(str/includes? % "#1 stays open") (:lines result)))))


(deftest an-m-issue-is-closed-by-its-pull-request-as-before
  (let [world  (size-world "M" "Part of #1\n\nNo closing keyword, but GitHub links it.")
        result (kit-merge world {})]
    (is (= ["CLOSED"] (issue-states world)) "only L waits for the body")
    (is (= "Done" (status-of-card world)))
    (is (= ["Status: Done" "Next: post the retrospective: kit retro 1"] (take-last 2 (:lines result))))))


;; Stories moved from the selftest's flow sections (#139 step 8).


(deftest a-conflict-seen-at-the-first-look-stops-at-once-and-asks-for-no-merge
  (let [world  (clone [(approved-pr :merge-state "DIRTY")])
        result (kit-merge world {})]
    (is (= :fail (::steps/status result)))
    (is (str/includes? (said result) "git merge origin/main") "it says to merge the base in")
    (is (str/includes? (said result) "dismisses") "and that the push dismisses the Approve")
    (is (not (:auto-merge (first (fake/prs world)))) "no merge is asked for")
    (is (= [] (fake/slept world)) "it did not wait")
    (is (left-alone? world) "the branch is kept")))


(deftest a-state-that-github-recomputes-while-waiting-after-auto-merge-is-on-is-read-again
  (let [world  (clone [(approved-pr :merge-state "BLOCKED")]
                      :script
                      [(fake/pr-becomes 3 {:decision nil :merge-state "UNKNOWN"})
                       (fake/pr-becomes 3 {:decision "APPROVED" :merge-state "BLOCKED"})
                       (fake/pr-becomes 3 {:state "MERGED"})])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)) "the merge goes through, with no word of a lost approval")
    (is (= [10 10 10] (fake/slept world)))))


(deftest a-pull-request-with-no-closing-reference-closes-no-issue
  (let [world  (clone [(approved-pr :state "MERGED" :closes [])])
        result (kit-merge world {})]
    (is (= :ok (::steps/status result)))
    (is (= ["OPEN" "CLOSED"] (issue-states world)) "no issue is closed")
    (is (not-any? #(str/starts-with? % "Closed #") (:lines result)) "and none is reported closed")
    (is (= "Done" (status-of-card world)) "the card is Done all the same")))


(defn- merged-by-the-second-look
  "kit merge against the world, where GitHub merges the pull request between its first look and its second."
  [world]
  (let [reads (atom 0)
        input (merge-cmd/read-input [] (fn [variable default] (get variables variable default)))]
    (binding [*out* (java.io.StringWriter.)]
      (fake/with-github world
                        #(let [read-pr github/read-pr]
                           (with-redefs [github/read-pr (fn [& args]
                                                          (let [pr (apply read-pr args)]
                                                            (if (< 1 (swap! reads inc))
                                                              (assoc pr :state "MERGED" :merge-state "CLEAN")
                                                              pr)))]
                             (merge-cmd/merge-pr input)))))))


(deftest a-pull-request-github-merged-while-the-branch-was-to-be-updated-goes-to-the-closeout
  (let [world  (clone [(approved-pr :merge-state "BEHIND")])
        result (merged-by-the-second-look world)]
    (is (= :ok (::steps/status result)))
    (is (nil? (:updates (first (fake/prs world)))) "no update of the merged pull request's branch")
    (is (nil? (:squashed (first (fake/prs world)))) "no second merge")
    (is (= "Done" (status-of-card world)) "it is closed out")
    (is (= #{"main"} (fake/local-branches world)) "and the local branch is gone")))
