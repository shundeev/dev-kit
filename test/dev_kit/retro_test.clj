(ns dev-kit.retro-test
  "kit retro, told as stories (#139, step 4): the report a task's retrospective starts from, read off a friction log
  fixture, and the gate that asks for a retrospective before new work. The fixture is test/fixtures/retro/friction:
  one session on a task, 55-count-friction, and another's."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.retro.log :as log]
   [dev-kit.retro.missing :as missing]
   [dev-kit.retro.report :as report]))


(def ^:private now "2026-11-12T17:00:00Z")


(defn- report-of
  "The friction part of the report for an issue, read off the fixture log as lines."
  [issue fixture-dir]
  (with-redefs-fn {#'log/log-dir (fn [] fixture-dir)}
    #(vec (report/log-lines {:repo "u473t8/fake" :issue issue :now (log/parse-time "--now" now)}))))


(deftest the-report-of-a-task-counts-its-friction-from-the-log
  (let [lines (report-of 55 "test/fixtures/retro/friction")]
    (is (= (str "Friction log on this machine: 25 events of the task, 2026-11-12 10:00 → 2026-11-12 15:00 UTC. "
                "The 14-day column counts every task and project on this machine.")
           (first lines)))
    (testing "a row says what happened, how often in this task and in 14 days on the machine"
      (is (some #{"| Confirmation: yes | ask rule: Bash(gh api *) | 4 | 7 |"} lines))
      (is (some #{"| Refused by a hook | gh issue create | 1 | 1 |"} lines)))
    (testing "each refusal of the task is listed, for the retrospective's verdict"
      (is (some #{"- 2026-11-12 11:01 Refused by a hook · gh issue create"} lines))
      (is (some #{"- 2026-11-12 11:02 Refused by the classifier · git push — git_destructive"} lines)))
    (testing "where the time went, and the owner's marks"
      (is (some #{"- waiting on the owner: 7 confirmation windows, 5m; 2 questions, 24m"} lines))
      (is (some #{"- 2026-11-12 11:30 трение: ссылка открылась в двух браузерах"} lines)))))


(deftest a-task-the-log-never-saw-has-no-events
  (is (= "Friction log on this machine: no events of this task."
         (first (report-of 999 "test/fixtures/retro/friction")))))


(deftest a-machine-without-a-log-says-so
  (is (= ["Friction log: not available — the recorder (dev-kit #55) has written nothing on this machine yet."]
         (report-of 55 "test/fixtures/retro/no-such-log"))))


(defn- graphql-answer
  "What GitHub says to the retrospective gate's query: issues with the comments they carry and whether a pull
  request merged for them."
  [issues]
  (fn [& _]
    (json/generate-string
     {"data" {"search" {"nodes" (for [[n title note merged] issues]
                                  {"number"   n
                                   "title"    title
                                   "comments" {"nodes" (if note
                                                         [{"body" note}]
                                                         [])}
                                   "closedByPullRequestsReferences" {"nodes" (if merged
                                                                               [{"mergedAt" "2026-11-11T10:00:00Z"}]
                                                                               [])}})}}})))


(defn- run-missing
  "run-missing against the fake gh: {:out what it printed :err what it printed to stderr :code its exit code}."
  [gh]
  (let [err  (java.io.StringWriter.)
        code (atom :unset)
        out  (with-out-str (binding [*err* err]
                             (reset! code (missing/run-missing {:repo "u473t8/fake" :gh gh :now now}))))]
    {:out out :err (str err) :code @code}))


(deftest new-work-waits-for-the-retrospective-of-a-merged-task
  (let [{:keys [out err code]} (run-missing (graphql-answer [[31 "Has its retro" "## Retrospective\n\nx" true]
                                                             [32 "Lacks its retro" "done" true]
                                                             [33 "Never merged" nil false]]))]
    (is (= 1 code))
    (is (= "" err))
    (is
     (=
      (str
       "Merged in the last 14 days without a `## Retrospective` comment: #32 Lacks its retro.\n"
       "Post each one's retrospective before new work (repo-task-delivery, stage 11); its data comes from kit retro <n>, "
       "e.g. kit retro 32.\n"
       "Only the owner can waive this: the same start command with --skip-retro asks the owner first.\n")
      out)
     "the list and the fix go to stdout, where kit start and the session read them")))


(deftest every-merged-task-with-its-retrospective-is-silence
  (let [{:keys [out err code]} (run-missing (graphql-answer [[31 "Has its retro" "## Retrospective\n\nx" true]]))]
    (is (nil? code))
    (is (= "" out))
    (is (= "" err))))


(deftest when-github-cannot-be-asked-there-is-no-verdict-and-the-exit-code-says-so
  (let [{:keys [out err code]} (run-missing (fn [& _] (throw (ex-info "no network" {}))))]
    (is (= 2 code))
    (is (= "" out))
    (is (= (str "kit: retro: GitHub not available: no network; "
                "check gh auth status and the network, then run kit retro --missing again\n")
           err))))


(deftest a-now-that-is-not-a-time-names-the-format
  (let [err  (java.io.StringWriter.)
        code (binding [*err* err]
               (missing/run-missing {:repo "u473t8/fake" :gh (fn [& _] "") :now "yesterday"}))]
    (is (= 1 code))
    (is (str/starts-with? (str err) "kit: retro: --now yesterday : not a time; write it as 2026-11-12T10:00:00Z"))))
