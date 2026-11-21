(ns dev-kit.retro-test
  "kit retro, told as stories (#139, step 4): the report a task's retrospective starts from, read off a friction log
  fixture, and the gate that asks for a retrospective before new work. The fixture is test/fixtures/retro/friction:
  one session on a task, 55-count-friction, and another's."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.base :as base]
   [dev-kit.fake-project :as fake]
   [dev-kit.main :as main]
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
     {"data" {"search" {"nodes" (for [[number title note merged] issues]
                                  {"number"   number
                                   "title"    title
                                   "comments" {"nodes" (if note
                                                         [{"body" note}]
                                                         [])}
                                   "closedByPullRequestsReferences" {"nodes" (if merged
                                                                               [{"mergedAt" "2026-11-11T10:00:00Z"}]
                                                                               [])}})}}})))


(defn- at-the-edge
  "`action` as kit runs it: {:out what it printed :err what it printed to stderr :code its exit code}, a problem it throws
  printed by kit's edge."
  [action]
  (let [{:keys [result out err]} (fake/captured (fn []
                                                  (try (action)
                                                       (catch clojure.lang.ExceptionInfo exception
                                                         (main/report-problem (ex-data exception))
                                                         (or (:exit (ex-data exception)) 1)))))]
    {:out out :err err :code result}))


(defn- run-missing
  "run-missing against the fake gh, at the edge."
  [gh]
  (with-redefs-fn {#'base/gh! gh}
    #(at-the-edge (fn [] (missing/run-missing {:repo "u473t8/fake" :now now})))))


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
    (is (= (str "retro: GitHub not available: no network. Fix: "
                "check gh auth status and the network, then run kit retro --missing again\n")
           err))))


(deftest a-now-that-is-not-a-time-names-the-format
  (let [{:keys [code err]} (at-the-edge #(missing/run-missing {:repo "u473t8/fake" :now "yesterday"}))]
    (is (= 1 code))
    (is (str/starts-with? err "retro: --now yesterday : not a time. Fix: write it as 2026-11-12T10:00:00Z"))))


(defn- rule-block
  "The refusals-by-rule block of the report for issue 55, read off the rules fixture, for a window of `days` days."
  [days]
  (let [lines (with-redefs-fn {#'log/log-dir (fn [] "test/fixtures/retro-rules/friction")}
                #(vec (report/log-lines {:repo "u473t8/fake" :issue 55 :days days :now (log/parse-time "--now" now)})))]
    (->> lines
         (drop-while #(not (str/starts-with? % "| Rule |")))
         (take-while #(str/starts-with? % "|"))
         vec)))


(deftest a-rule-that-refused-three-times-is-counted-three-times
  (testing "the task's count and the count of every task on this machine in the last 14 days, most refusals first"
    (is (= ["| Rule | This task | 14 days |"
            "|---|---|---|"
            "| git.reset-hard | 2 | 3 |"
            "| foreground-wait | 1 | 2 |"
            "| gate.design-approval | 1 | 1 |"]
           (rule-block nil)))
    (is (= "| git.reset-hard | 2 | 3 |" (nth (rule-block 14) 2))
        "the refusals of 20 October are outside 14 days"))
  (testing "--days widens the window; the task's own count does not depend on it"
    (is (= ["| Rule | This task | 30 days |"
            "|---|---|---|"
            "| git.reset-hard | 2 | 5 |"
            "| foreground-wait | 1 | 2 |"
            "| gate.design-approval | 1 | 1 |"]
           (rule-block 30)))))


(deftest a-refusal-that-names-no-rule-is-listed-but-not-counted-by-rule
  (let [lines (with-redefs-fn {#'log/log-dir (fn [] "test/fixtures/retro-rules/friction")}
                #(vec (report/log-lines {:repo "u473t8/fake" :issue 55 :now (log/parse-time "--now" now)})))]
    (is (some #{"- 2026-11-12 10:05 Refused by a hook · gh issue create"} lines) "it is one of the task's refusals")
    (is (= 3 (count (filter #(re-matches #"\| (git|gate|foreground)\S* \| \d+ \| \d+ \|" %) lines)))
        "the task had five refusals; the table has a row for each of the three rules and none for this one")))


(deftest a-task-with-no-rule-refusals-says-so
  (let [lines (with-redefs-fn {#'log/log-dir (fn [] "test/fixtures/retro/friction")}
                #(vec (report/log-lines {:repo "u473t8/fake" :issue 55 :now (log/parse-time "--now" now)})))]
    (is (some #{"Refusals by rule: none named its rule, in this task or in the last 14 days."} lines))))


(deftest a-days-that-is-not-a-number-names-the-fix
  (let [{:keys [code err]} (at-the-edge #(report/run
                                          {:repo "u473t8/fake" :issue 55 :days "many" :now now}))]
    (is (= 1 code))
    (is (= "retro: --days many : not a number of days. Fix: write a whole number above 0, e.g. --days 30\n" err))))


;; The data of a retrospective, whole (the selftest's cases of #55, #65, #95, #107, #139 step 8)


(def ^:private friction-dir "test/fixtures/retro/friction")


(defn- issue-55
  "Issue #55 of u473t8/fake as GitHub answers the retrospective's query: design comment 10:00, approved 10:20, PR #58
  opened 12:30, approved 13:30, merged 14:00; `agent` wrote the design and one review comment."
  [agent]
  {"data"
   {"repository"
    {"issue"
     {"number"        55
      "createdAt"     "2026-11-12T09:30:00Z"
      "closedAt"      "2026-11-12T14:00:00Z"
      "labels"        {"nodes" [{"name" "design-approved"} {"name" "size:M"}]}
      "timelineItems" {"nodes" [{"label"     {"name" "design-approved"}
                                 "actor"     {"login" "the-owner"}
                                 "createdAt" "2026-11-12T10:20:00Z"}]}
      "comments"      {"nodes" [{"author" {"login" agent} "body" "## Design\n\nx" "createdAt" "2026-11-12T10:00:00Z"}]}
      "closedByPullRequestsReferences"
      {"nodes" [{"number"        58
                 "headRefName"   "55-count-friction"
                 "createdAt"     "2026-11-12T12:30:00Z"
                 "mergedAt"      "2026-11-12T14:00:00Z"
                 "additions"     120
                 "deletions"     10
                 "changedFiles"  4
                 "commits"       {"nodes" [{"commit" {"authoredDate"      "2026-11-12T11:00:00Z"
                                                      "statusCheckRollup" {"state" "SUCCESS"}}}
                                           {"commit" {"authoredDate"      "2026-11-12T12:25:00Z"
                                                      "statusCheckRollup" {"state" "FAILURE"}}}]}
                 "reviews"       {"nodes" [{"author"      {"login" "the-owner"}
                                            "state"       "COMMENTED"
                                            "submittedAt" "2026-11-12T13:00:00Z"}
                                           {"author"      {"login" agent}
                                            "state"       "COMMENTED"
                                            "submittedAt" "2026-11-12T13:10:00Z"}
                                           {"author"      {"login" "the-owner"}
                                            "state"       "APPROVED"
                                            "submittedAt" "2026-11-12T13:30:00Z"}]}
                 "timelineItems" {"nodes" []}
                 "comments"      {"nodes"
                                  [{"author" {"login" agent}
                                    "createdAt" "2026-11-12T12:40:00Z"
                                    "body"
                                    "## Что сделать руками (владелец)\n\n1. Открой\n2. Нажми\n3. Проверь"}]}}]}}}}})


(defn- retro-run
  "kit retro for an issue as the owner reads it: {:out :err :code}; GitHub answers as `github` says (a function of the
  gh arguments, or nil for unreachable), the friction log is in `log-dir`."
  [opts & {:keys [github log-dir] :or {log-dir friction-dir}}]
  (let [gh (or github (fn [& _] (throw (ex-info "no network" {}))))]
    (with-redefs-fn {#'base/gh!    gh
                     #'log/log-dir (fn [] log-dir)}
      #(at-the-edge (fn [] (report/run (merge {:repo "u473t8/fake" :agent "the-agent" :now now} opts)))))))


(defn- answering-issue-55
  []
  (fn [& _] (json/generate-string (issue-55 "the-agent"))))


(deftest the-friction-data-of-a-merged-task-reads-its-log-and-its-github-side
  (let [{:keys [out code]} (retro-run {:issue 55} :github (answering-issue-55))]
    (is (nil? code))
    (doseq [[what phrase]
            [["the span of the task's events, its later messages included" "2026-11-12 10:00 → 2026-11-12 15:00 UTC"]
             ["windows by their ask rule: own branch, own turn, own subagent; not another task's"
              "| Confirmation: yes | ask rule: Bash(gh api *) | 4 | 7 |"]
             ["a Read rule matches by path, printed without it" "| Confirmation: yes | ask rule: Read(…) | 1 | 1 |"]
             ["a window no ask rule explains" "| Confirmation: yes | Read (no ask rule matches) | 1 | 1 |"]
             ["a window and no run: the owner said no" "| Confirmation: no | ask rule: Bash(rm *) | 2 | 2 |"]
             ["a question" "| Question to the owner | AskUserQuestion | 1 | 1 |"]
             ["needs input at the end of a turn" "| Question to the owner | needs input in chat | 1 | 1 |"]
             ["a plan that never ran" "| Plan for approval | declined | 1 | 1 |"]
             ["a turn without its Stop, followed by another: Esc" "| Owner interrupted (Esc) | — | 1 | 1 |"]
             ["a hook's refusal" "| Refused by a hook | gh issue create | 1 | 1 |"]
             ["the classifier's refusal, with its category"
              "| Refused by the classifier | git push — git_destructive | 1 | 1 |"]
             ["no run in a turn that ended normally: a deny rule" "| Refused by a rule | echo | 1 | 1 |"]
             ["each refusal of the task listed for a verdict" "Refusals of the task, each needs a verdict"]
             ["the classifier's refusal in that list" "Refused by the classifier · git push — git_destructive"]
             ["a failed command" "| Tool error | bb kit gate | 1 | 1 |"]
             ["gh's flag values stay out of the shape" "| Tool error | gh pr view | 1 | 1 |"]
             ["inline code stays out of the shape" "| Tool error | python3 -c | 1 | 1 |"]
             ["the same command within minutes" "| Same command 3+ times | gh pr view | 3 | 3 |"]
             ["a stale identical call from an earlier turn keeps no window of a later one"
              "| Refused by a rule | gh api (REST) | 1 | 1 |"]
             ["a rule naming a URL prints without it" "| Confirmation: yes | ask rule: Bash(…) | 1 | 1 |"]
             ["a turn that failed is no Esc" "| Turn failed (API error) | — | 1 | 1 |"]
             ["the repository's case does not matter" "| Tool error | make | 1 | 1 |"]
             ["recurring: all tasks and projects of the last 14 days, not October"
              "Confirmation: yes · ask rule: Bash(gh api *): 7"]
             ["another project's friction counts in the 14 days" "Tool error · npm: 3"]
             ["the owner's mark, on one line" "трение: ссылка открылась в двух браузерах"]
             ["time: the task's turns" "Time of the task: 10 turns, 42m in all"]
             ["time: waiting on the owner" "waiting on the owner: 7 confirmation windows, 5m; 2 questions, 24m"]
             ["time: commands, and the longest" "commands: 2m; the longest: bb kit gate 1m, git log 1m"]
             ["the owner's messages: the mid-turn one, and those of turns naming no task go to the last task"
              "Owner messages: 10"]
             ["GitHub: the design approval" "Design approval: 1, 20m after the first `## Design` comment"]
             ["GitHub: the diff" "PR #58: +120 −10 in 4 files, 2 commits; Size M"]
             ["GitHub: reviews by the owner" "Review rounds with comments: 1; approvals: 1; dismissed: 0"]
             ["GitHub: waiting on the owner" "PR opened → Approve 1h 00m; Approve → merged 30m"]
             ["GitHub: CI" "CI: 1 of 2 commits failed"]
             ["GitHub: the owner's manual steps" "Owner steps: 1 comment, 3 steps"]]]
      (is (str/includes? out phrase) what))
    (is (not (str/includes? out "git status")) "repeats are runs within 10 minutes: a command 30 min apart is none")
    (is (not (re-find #"почему так|secret|import|/w/|/tmp/|host/tok" out))
        "no messages, paths, flag values or code are printed")))


(deftest a-task-worked-on-only-in-a-turn-that-served-two-tasks-says-its-turn-time-is-not-split
  (let [{:keys [out]} (retro-run {:issue 62} :github (answering-issue-55))]
    (is (str/includes? out "its turns also served other tasks"))
    (is (str/includes? out "commands: 1m; the longest: make 1m") "its commands are still counted")
    (is (str/includes? out "waiting on the owner: 0 confirmation windows, 0s") "under a minute reads in seconds")))


(deftest the-owners-messages-are-listed-only-on-request-and-only-locally
  (let [{:keys [out]} (retro-run {:issue 55 :messages true} :github (answering-issue-55))]
    (is (str/includes? out "я про план 55") "the mid-turn one too")
    (is (str/includes? out "Owner messages (local only, never publish):"))))


(deftest a-machine-without-a-log-still-prints-the-github-side
  (let [{:keys [out code]}
        (retro-run {:issue 55} :github (answering-issue-55) :log-dir "test/fixtures/retro/no-such-log")]
    (is (nil? code))
    (is (str/includes? out "Friction log: not available"))
    (is (str/includes? out "PR #58") "GitHub's part")))


(deftest an-unreachable-github-is-said-and-the-log-still-counts
  (let [{:keys [out code]} (retro-run {:issue 55})]
    (is (nil? code))
    (is (str/includes? out "GitHub: not available"))
    (is (str/includes? out "| Tool error | bb kit gate | 1 | 1 |"))))


(deftest a-now-that-is-not-a-time-names-the-format-in-the-data-too
  (let [{:keys [code err]} (retro-run {:issue 55 :now "yesterday"})]
    (is (= 1 code))
    (is (str/includes? err "2026-11-12T10:00:00Z"))))


(deftest a-silent-gh-failure-names-the-call-and-the-exit-code
  (is (= "gh api graphql failed with exit code 3 and printed no message; run the same call by hand to see why"
         (ex-message (base/gh-failure ["api" "graphql" "-f" "query=x"] 3 "")))))
