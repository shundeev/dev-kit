(ns dev-kit.flow.checks-test
  "kit checks, told as the owner's stories (#139, ADR-0008): each runs the command against an in-memory GitHub whose
  script says what happens while it waits, and reports what the owner reads and how the command ends."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.checks :as checks]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.steps :as steps]))


(def ^:private pr-url "https://github.com/o/r/pull/1")


(defn- check
  [check-name bucket]
  {:name check-name :bucket bucket :link (str "https://ci/job/" check-name)})


(defn- passed [& names] (mapv #(check % "pass") names))


(defn- kit-checks
  "Runs kit checks with the command-line words against the world. Returns what the owner reads (:printed, in order,
  those during the wait and the end) and how it ended: :exit and :text of a refusal (0 and nil when it passed)."
  [world variables & args]
  (let [out     (java.io.StringWriter.)
        input   (checks/read-input (fake/options-of "checks" args)
                                   (fn [variable default] (get variables variable default)))
        result  (binding [*out* out]
                  (fake/with-github world
                                    (fn []
                                      (let [result (checks/checks input)]
                                        (checks/print-result result)
                                        result))))
        refusal (when (= :fail (::steps/status result))
                  (steps/refusal result))]
    {:printed (str/trim-newline (str out))
     :exit    (get refusal :exit 0)
     :text    (:text refusal)}))


(defn- on-the-issue-branch
  "A world with the issue branch checked out and its pull request (number 1), in the options."
  [pr & {:as options}]
  (fake/new-world (merge {:current-branch "7-fix"
                          :required-contexts ["selftest" "lint"]
                          :prs [(merge {:branch "7-fix" :head "aaa1111" :checks (passed "selftest" "lint")} pr)]}
                         options)))


(deftest checks-that-have-passed-end-it-at-once
  (let [world  (on-the-issue-branch {})
        result (kit-checks world {})]
    (is (= 0 (:exit result)))
    (is (= (format "Waiting for selftest, lint on aaa1111 (%s).\nAll 2 required checks passed on aaa1111: %s"
                   pr-url
                   pr-url)
           (:printed result)))
    (is (= [] (fake/slept world)) "it never paused")))


(deftest checks-not-registered-then-pending-then-passed-tell-each-state-once-and-end-well
  (let [world  (on-the-issue-branch
                {:checks []}
                :script
                [fake/nothing-happens
                 (fake/pr-becomes 1 {:checks [(check "selftest" "pending") (check "lint" "pending")]})
                 fake/nothing-happens
                 (fake/pr-becomes 1 {:checks (passed "selftest" "lint")})])
        result (kit-checks world {"GHWF_MERGE_POLL" "30"})]
    (is (= 0 (:exit result)))
    (is (= [(format "Waiting for selftest, lint on aaa1111 (%s)." pr-url)
            "Not registered yet: selftest, lint."
            "2 of 2 required checks have not finished: selftest, lint"
            (format "All 2 required checks passed on aaa1111: %s" pr-url)]
           (str/split-lines (:printed result)))
        "a status that has not changed is not said again")
    (is (= [30 30 30 30] (fake/slept world)) "it looked every poll period")))


(deftest one-registered-check-is-not-all-of-them
  (let [world  (on-the-issue-branch
                {:checks (passed "selftest")}
                :script
                [(fake/pr-becomes 1 {:checks (passed "selftest" "lint")})])
        result (kit-checks world {})]
    (is (str/includes? (:printed result) "Not registered yet: lint."))
    (is (str/includes? (:printed result) "All 2 required checks passed"))))


(deftest a-failed-check-ends-it-with-its-link-at-once
  (let [world  (on-the-issue-branch {:checks [(check "selftest" "fail") (check "lint" "pending")]})
        result (kit-checks world {})]
    (is (= 1 (:exit result)))
    (is (= (str "1 required check failed on aaa1111:\n"
                "  selftest: https://ci/job/selftest\n"
                "Fix: open the link, fix the cause, push; then run kit checks again for the new commit.")
           (:text result)))
    (is (= [] (fake/slept world)) "it did not wait for the others")))


(deftest the-pull-request-ending-or-changing-while-it-waits-ends-it-with-no-result
  (doseq
    [[what event text]
     [["closes" {:state "CLOSED"}
       (format
        "Ended with no result: the pull request is closed, %s. Fix: name an open pull request: kit checks --pr <number>"
        pr-url)]
      ["merges" {:state "MERGED"}
       (format
        "The pull request merged, so there is nothing to wait for: %s. Fix: nothing to do for this pull request; kit merge closes the work out"
        pr-url)]
      ["gets a push" {:head "bbb2222"}
       "Ended with no result: the head moved from aaa1111 to bbb2222 (a push). Fix: run kit checks again for the new commit."]]]
    (let [world  (on-the-issue-branch {:checks [(check "selftest" "pending")]} :script [(fake/pr-becomes 1 event)])
          result (kit-checks world {})]
      (is (= 2 (:exit result)) (str "the pull request " what))
      (is (= text (:text result)) "the text as the one form of a refusal"))))


(deftest github-that-cannot-be-read-ends-it-with-gh's-message
  (doseq [[what pr text]
          [["the pull request" {:unreadable "HTTP 403"} "gh pr view failed: HTTP 403. Fix: run it by hand"]
           ["its checks" {:checks-unreadable "HTTP 403"} "gh pr checks failed: HTTP 403. Fix: run `gh pr checks"]]]
    (let [result (kit-checks (on-the-issue-branch pr) {})]
      (is (= 2 (:exit result)) (str "reading " what " failed"))
      (is (str/starts-with? (:text result) text)))))


(deftest rules-that-cannot-be-read-end-it-with-gh's-message
  (let [result (kit-checks (on-the-issue-branch {} :rules-unreadable "HTTP 403") {})]
    (is (= 2 (:exit result)))
    (is (str/starts-with? (:text result) "gh api repos/o/r/rules/branches/main failed: HTTP 403"))))


(deftest a-base-that-requires-no-checks-ends-it-at-once
  (let [world  (on-the-issue-branch {} :required-contexts [])
        result (kit-checks world {})]
    (is (= 0 (:exit result)))
    (is (= (format "No required checks on main; nothing to wait for: %s" pr-url) (:printed result)))))


(def ^:private run-log "ready\tMark\t2026-11-14T10:00:00Z CI run id: 400, success on 7-fix at aaa1111\n")


(deftest where-the-base-has-the-ready-workflow-it-reports-the-state-the-workflow-left
  (doseq
    [[what pr runs said]
     [["marked ready" {:owners ["owners"] :auto-merge true} [{:databaseId 501 :status "completed" :log run-log}]
       (format "Ready: %s is marked ready; owners asked: owners; auto-merge on." pr-url)]
      ["held" {:draft true :labels ["hold"]} []
       (format
        "Held: %s carries the label hold and stays a draft. Fix: kit release, once what it waited for has merged."
        pr-url)]
      ["left a draft" {:draft true}
       [{:databaseId 501
         :status     "completed"
         :log        (str
                      run-log
                      "ready\tMark\t2026-11-14T10:00:01Z decision: #9 carries the label hold, left a draft\n")}]
       "Still a draft: the ready workflow ran for aaa1111 and left it. decision: #9 carries the label hold, left a draft Fix: see its run; kit release once every required check is green."]]]
    (let [world  (on-the-issue-branch pr :ready-workflow true :ready-runs runs)
          result (kit-checks world {})]
      (is (= 0 (:exit result)) what)
      (is (= [(format "Waiting for selftest, lint on aaa1111 (%s)." pr-url)
              (format "All 2 required checks passed on aaa1111: %s" pr-url)
              said]
             (str/split-lines (:printed result)))
          what))))


(deftest a-pull-request-that-left-draft-before-its-owners-were-asked-waits-for-the-run
  (let [world  (on-the-issue-branch
                {:draft false}
                :ready-workflow true
                :ready-runs     []
                :script         [(fn [w]
                                   (-> w
                                       (assoc :ready-runs [{:databaseId 501 :status "completed" :log run-log}])
                                       (update-in [:prs 1] merge {:owners ["owners"] :auto-merge true})))])
        result (kit-checks world {})]
    (is (= 0 (:exit result)))
    (is (= ["Waiting for the ready workflow's run for aaa1111. If GitHub never delivers it, kit release is the way out."
            (format "All 2 required checks passed on aaa1111: %s" pr-url)
            (format "Ready: %s is marked ready; owners asked: owners; auto-merge on." pr-url)]
           (rest (str/split-lines (:printed result)))))))


(deftest --pr-names-the-pull-request-even-from-main
  (let [world  (on-the-issue-branch {} :current-branch "main")
        result (kit-checks world {} "--pr" "1")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:printed result) "All 2 required checks passed"))))


(deftest what-it-refuses-says-what-to-run-instead
  (doseq
    [[what world variables args text]
     [["main, with no pull request named"
       (on-the-issue-branch {} :current-branch "main") {} []
       "'main' is not an issue branch. Fix: run from the issue branch, or name the pull request: kit checks --pr <number>"]
      ["the configured base, with no pull request named"
       (on-the-issue-branch {} :current-branch "trunk") {"GHWF_DEFAULT_BASE" "trunk"} []
       "'trunk' is not an issue branch. Fix: run from the issue branch, or name the pull request: kit checks --pr <number>"]
      ["a detached head"
       (on-the-issue-branch {} :current-branch "HEAD") {} []
       "'HEAD' is not an issue branch. Fix: run from the issue branch, or name the pull request: kit checks --pr <number>"]
      ["a branch with no open pull request"
       (on-the-issue-branch {} :current-branch "8-other") {} []
       (str "No open pull request for branch '8-other' in o/r. "
            "Fix: open it with kit finish, or name it: kit checks --pr <number>")]
      ["a pull request that is not a number"
       (on-the-issue-branch {}) {} ["--pr" "abc"]
       "--pr is 'abc'. Fix: give the pull request's number, e.g. --pr 12"]
      ["a poll period below zero"
       (on-the-issue-branch {}) {"GHWF_MERGE_POLL" "-1"} []
       (str "GHWF_MERGE_POLL is '-1': give it a whole number of seconds, or unset it for 10. "
            "Fix: set GHWF_MERGE_POLL to a whole number of seconds (0 or more), or unset it")]
      ["a poll period that is not a number"
       (on-the-issue-branch {}) {"GHWF_MERGE_POLL" "soon"} []
       (str "GHWF_MERGE_POLL is 'soon': give it a whole number of seconds, or unset it for 10. "
            "Fix: set GHWF_MERGE_POLL to a whole number of seconds (0 or more), or unset it")]]]
    (let [result (apply kit-checks world variables args)]
      (is (= 1 (:exit result)) what)
      (is (= text (:text result)) what))))


;; a passing GitHub error in the middle of a wait (#139, step 8)


(def ^:private waiting-line (format "Waiting for selftest, lint on aaa1111 (%s)." pr-url))


(deftest a-passing-github-error-in-a-checks-wait-is-asked-again-and-the-wait-ends-with-the-verdict
  (let [world  (on-the-issue-branch {:checks [(check "selftest" "pending") (check "lint" "pass")]}
                                    :script
                                    [(fake/github-fails "gh: No server is currently available (HTTP 503)")
                                     fake/github-recovers
                                     (fn [w] (assoc-in w [:prs 1 :checks] (passed "selftest" "lint")))])
        result (kit-checks world {"GHWF_MERGE_POLL" "20"})]
    (is (= 0 (:exit result)))
    (is (= [20 20 20] (fake/slept world)))
    (is (= [waiting-line
            "1 of 2 required checks have not finished: selftest"
            "GitHub answered HTTP 503; asking again in 20 s"
            (format "All 2 required checks passed on aaa1111: %s" pr-url)]
           (str/split-lines (:printed result))))))


(deftest three-passing-github-errors-in-a-row-end-a-checks-wait-with-what-to-do
  (let [world  (on-the-issue-branch {:checks [(check "selftest" "pending") (check "lint" "pass")]}
                                    :script
                                    [(fake/github-fails "HTTP 503") fake/nothing-happens fake/nothing-happens])
        result (kit-checks world {})]
    (is (= 2 (:exit result)))
    (is (= (str "GitHub answered with an error 3 times in a row; the last: gh pr checks failed: HTTP 503. "
                "Fix: check https://www.githubstatus.com, then run the same command again")
           (:text result)))
    (is (= 2 (count (filter #(str/starts-with? % "GitHub answered HTTP 503") (str/split-lines (:printed result))))))))


(deftest an-error-that-does-not-pass-ends-a-checks-wait-at-once
  (let [world  (on-the-issue-branch {:checks [(check "selftest" "pending") (check "lint" "pass")]}
                                    :script
                                    [(fake/github-fails "HTTP 404: Not Found") fake/nothing-happens])
        result (kit-checks world {})]
    (is (= 2 (:exit result)))
    (is (str/starts-with? (:text result) "gh pr checks failed: HTTP 404"))
    (is (= [10] (fake/slept world)) "it did not ask again")))
