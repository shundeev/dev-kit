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
        input   (checks/read-input (vec args) (fn [variable default] (get variables variable default)))
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
    (is (= (str "Error: 1 required check failed on aaa1111:\n"
                "  selftest: https://ci/job/selftest\n"
                "Fix: open the link, fix the cause, push; then run kit checks again for the new commit.")
           (:text result)))
    (is (= [] (fake/slept world)) "it did not wait for the others")))


(deftest the-pull-request-ending-or-changing-while-it-waits-ends-it-with-no-result
  (doseq
    [[what event text]
     [["closes" {:state "CLOSED"} (format "Ended with no result: the pull request is closed, %s." pr-url)]
      ["merges" {:state "MERGED"} (format "The pull request merged, so there is nothing to wait for: %s" pr-url)]
      ["gets a push" {:head "bbb2222"}
       "Ended with no result: the head moved from aaa1111 to bbb2222 (a push). Fix: run kit checks again for the new commit."]]]
    (let [world  (on-the-issue-branch {:checks [(check "selftest" "pending")]} :script [(fake/pr-becomes 1 event)])
          result (kit-checks world {})]
      (is (= 2 (:exit result)) (str "the pull request " what))
      (is (= text (:text result)) "said plainly, without \"Error: \""))))


(deftest github-that-cannot-be-read-ends-it-with-gh's-message
  (doseq [[what pr text]
          [["the pull request" {:unreadable "HTTP 502"} "gh pr view failed: HTTP 502. Fix: run it by hand"]
           ["its checks" {:checks-unreadable "HTTP 502"} "gh pr checks failed: HTTP 502. Fix: run `gh pr checks"]]]
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
  (doseq [[what world variables args text]
          [["main, with no pull request named"
            (on-the-issue-branch {} :current-branch "main") {} []
            "Error: Run from the issue branch, not 'main', or name the pull request: kit checks --pr <number>"]
           ["the configured base, with no pull request named"
            (on-the-issue-branch {} :current-branch "trunk") {"GHWF_DEFAULT_BASE" "trunk"} []
            "Error: Run from the issue branch, not 'trunk', or name the pull request: kit checks --pr <number>"]
           ["a detached head"
            (on-the-issue-branch {} :current-branch "HEAD") {} []
            "Error: Run from the issue branch, not 'HEAD', or name the pull request: kit checks --pr <number>"]
           ["a branch with no open pull request"
            (on-the-issue-branch {} :current-branch "8-other") {} []
            (str "Error: No open pull request for branch '8-other' in o/r. "
                 "Fix: open it with kit finish, or name it: kit checks --pr <number>")]
           ["a pull request that is not a number"
            (on-the-issue-branch {}) {} ["--pr" "abc"]
            "Error: --pr is 'abc': give the pull request's number"]
           ["a poll period that is not a number"
            (on-the-issue-branch {}) {"GHWF_MERGE_POLL" "soon"} []
            "Error: GHWF_MERGE_POLL is 'soon': give it a whole number of seconds, or unset it for 10"]]]
    (let [result (apply kit-checks world variables args)]
      (is (= 1 (:exit result)) what)
      (is (= text (:text result)) what))))
