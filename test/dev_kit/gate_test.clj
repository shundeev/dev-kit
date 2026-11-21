(ns dev-kit.gate-test
  "The gate, told as stories (#139, step 4): what a pull request's author reads when the shape is wrong, what the
  owner's design approval must look like, what the user-level test section must hold, and what the edit hook says
  when it refuses. The verdicts are data; a story reads the data and the sentence a person gets."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.base :as base]
   [dev-kit.gate :as gate]))


(def ^:private good-body
  "## Review\n\nread the diff\n\n## User-level test\n\nran kit start\n\nADR: none")


(defn- problems-of
  "The problem keywords the shape check names."
  [branch title body pr]
  (mapv :problem (gate/shape-problems branch title body pr)))


(deftest a-pull-request-of-the-right-shape-passes
  (is (= [] (problems-of "12-fix-it" "fix(GH-12): fix it" good-body 7))))


(deftest a-pull-request-of-the-wrong-shape-names-each-fault-and-its-fix
  (doseq [[branch title body pr expected fix-part]
          [["12-x" "fix it" good-body 7 [:gate/bad-title] "gh pr edit 7 --title '<type>(GH-12): <summary>'"]
           ["12-x" "fix(GH-9): fix it" good-body nil [:gate/bad-title] "pass --type from that list"]
           ["12-x" "wip(GH-12): x" good-body 7 [:gate/bad-title] "gh pr edit 7"]
           ["12-x" "fix(GH-12): x" "## Review\n\nr\n\n## User-level test\n\nt\n" 7 [:gate/no-adr-line]
            "add one (adr skill)"]
           ["12-x" "fix(GH-12): x" "## User-level test\n\nt\n\nADR: none" 7 [:gate/no-section]
            "add the section from the PR template"]
           ["12-x" "fix(GH-12): x" "## Review\n\n<!-- hint -->\n\n## User-level test\n\nt\n\nADR: none" 7
            [:gate/empty-section] "fill in ## Review; CI reruns when the body is edited"]
           ["main" "fix(GH-12): x" good-body 7 [:gate/bad-branch] "open the issue with kit start"]]]
    (let [found (gate/shape-problems branch title body pr)]
      (is (= expected (mapv :problem found)) (str branch " " title))
      (is (every? #(= :fail (:level %)) found))
      (is (every? #(str/includes? (gate/verdict-text %) fix-part) found)
          "the sentence carries the fix, after what is wrong"))))


(deftest the-user-level-test-section-must-say-something
  (testing "missing and empty sections are different faults; hint comments do not count as text"
    (is (= [:gate/no-section] (problems-of "12-x" "fix(GH-12): x" "## Review\n\nr\n\nADR: none" 7)))
    (is (= [:gate/empty-section]
           (problems-of "12-x" "fix(GH-12): x" "## Review\n\nr\n\n## User-level test\n\n\nADR: none" 7)))
    (is (= [:gate/empty-section]
           (problems-of "12-x" "fix(GH-12): x"
                        "## Review\n\nr\n\n## User-level test\n\n<!-- what you ran -->\n\nADR: none" 7))))
  (testing "a section with text passes, hint comments aside"
    (is (= []
           (problems-of "12-x" "fix(GH-12): x"
                        "## Review\n\nr\n\n## User-level test\n\n<!-- hint -->\nran it\n\nADR: none" 7))))
  (testing "an offline check with no body checks title and branch only"
    (is (= [] (problems-of "12-x" "fix(GH-12): x" nil nil)))))


(deftest a-verdict-reads-like-a-refusal-the-text-then-what-to-do
  (is (= (str
          "the title 'x' is not '<type>(GH-1): <summary>' with a type from build, chore, ci, docs, feat, fix, perf, "
          "refactor, test. Fix: pass --type from that list, or fix the first line of the commit message")
         (gate/verdict-text (first (gate/shape-problems "1-a" "x" nil nil)))))
  (is (= "branch 'main' is not <issue>-<slug>. Fix: open the issue with kit start and move the work to its branch"
         (gate/verdict-text (first (gate/shape-problems "main" "x" nil nil))))))


(deftest accepted-adrs-are-not-edited
  (let [found (gate/adr-problems
               [{"filename" "adr/0003-x.md" "status" "modified"}
                {"filename" "adr/0009-new.md" "status" "added"}
                {"filename" "adr/0004-y.md" "status" "renamed" "previous_filename" "adr/0004-old.md"}])]
    (is (= [:gate/adr-changed :gate/adr-changed] (mapv :problem found)))
    (is (str/includes? (gate/verdict-text (first found))
                       "revert it, and record the change as a new ADR with 'Supersedes: ADR-0003' (adr skill)"))))


(deftest git-name-status-gives-the-files-github-gives
  (is (= [{"filename" "adr/0003-x.md" "status" "modified"}
          {"filename" "adr/0009-new.md" "status" "added"}
          {"filename" "adr/0005-z.md" "status" "removed"}
          {"filename" "adr/0004-y.md" "status" "renamed" "previous_filename" "adr/0004-old.md"}]
         (gate/name-status-files
          "M\tadr/0003-x.md\nA\tadr/0009-new.md\nD\tadr/0005-z.md\nR087\tadr/0004-old.md\tadr/0004-y.md\n")))
  (is (empty? (gate/name-status-files ""))))


(deftest an-m-pull-request-states-its-size
  (is (= [:gate/no-size-line] (mapv :problem (gate/size-problems "M" "no size here"))))
  (is (= [:gate/no-size-line] (mapv :problem (gate/size-problems nil "no size here"))) "no label counts as M")
  (is (nil? (gate/size-problems "M" "Size: 164 → 150 lines after simplify")))
  (is (nil? (gate/size-problems "S" "no size here")) "an S pull request needs none"))


(defn- approved-issue
  "An issue as the gate reads it from GitHub: approved by `by` at `approved-at`, its body last edited at `edited-at`."
  [{:keys [by approved-at edited-at labels comments]
    :or   {by "owner" approved-at "2026-11-10T10:00:00Z" labels ["size:M" "design-approved"]}}]
  {"state"         "OPEN"
   "lastEditedAt"  edited-at
   "labels"        {"nodes" (mapv #(hash-map "name" %) labels)}
   "timelineItems" {"nodes" [{"actor" {"login" by} "label" {"name" "design-approved"} "createdAt" approved-at}]}
   "comments"      {"nodes" (or comments [])}})


(defn- verdict
  "g1-verdict for the issue, with GitHub answering that the approver's permission is `permission`."
  [issue permission]
  (with-redefs-fn {#'base/gh! (fn [& _] (str permission "\n"))}
    #(gate/g1-verdict "o/r" 5 issue "the-agent")))


(deftest the-design-approval-is-an-owners-and-is-the-latest
  (testing "an owner with write access approved after the design: ok"
    (let [v (verdict (approved-issue {}) "write")]
      (is (= :ok (:level v)))
      (is (= "G1: design of #5 approved by owner at 2026-11-10T10:00:00Z" (:message v)))))
  (doseq [[what an-issue permission expected]
          [["no label at all" (approved-issue {:labels ["size:M"]}) "write" :gate/no-approval]
           ["the agent approved" (approved-issue {:by "the-agent"}) "write" :gate/approved-by-agent]
           ["the approver cannot write" (approved-issue {}) "read" :gate/approver-cannot-write]
           ["the body changed after" (approved-issue {:edited-at "2026-11-11T10:00:00Z"}) "write" :gate/body-edited]
           ["the design comment changed after"
            (approved-issue {:comments [{"body"         "## Design\n\nx"
                                         "createdAt"    "2026-11-09T10:00:00Z"
                                         "lastEditedAt" "2026-11-12T10:00:00Z"}]})
            "write" :gate/design-edited]]]
    (testing what
      (let [v (verdict an-issue permission)]
        (is (= expected (:problem v)))
        (is (= :fail (:level v)))
        (is (some? (:fix v)) "a refusal says what to do")))))


(deftest a-lost-approval-says-how-to-get-it-again
  (is (= (str
          "`design-approved` on #5 was set by the agent (the-agent); only a person with write access approves. Fix: "
          "the owner approves again: remove `design-approved` from #5 and add it back")
         (gate/verdict-text (verdict (approved-issue {:by "the-agent"}) "write")))))


(deftest the-issue-behind-a-pull-request-decides-between-size-and-approval
  (let [cfg    {:agent {:login "the-agent"}}
        judged (fn [issue files]
                 (with-redefs-fn {#'gate/managed-paths (fn [_] #{})
                                  #'base/gh! (fn [& _] "write\n")}
                   #(gate/issue-verdict "o/r" 5 issue files cfg)))
        small  {"state" "OPEN" "labels" {"nodes" [{"name" "size:S"}]}}]
    (is (= :ok (:level (judged small [{"filename" "a.clj" "additions" 20 "deletions" 5}])))
        "a small S needs no approval")
    (is (= :gate/size-over (:problem (judged small [{"filename" "a.clj" "additions" 300 "deletions" 0}]))))
    (is (= :gate/issue-closed (:problem (judged (assoc small "state" "CLOSED") []))))
    (is (= :gate/not-an-issue (:problem (judged {"error" "not found"} []))))
    (is (str/starts-with? (:message (judged (approved-issue {}) [])) "size M; G1: design of #5 approved by owner")
        "the approval verdict says which size it judged")))


(deftest the-gate-prints-ok-and-fail-lines-and-asks-for-exit-1-on-a-fail
  (let [code (atom :unset)
        out  (with-out-str
               (reset! code
                 (gate/print-verdicts! [{:level :ok :message "size S: fine"}
                                        {:level :fail :message "branch 'x' is bad" :fix "fix it"}])))]
    (is (= "  ok    size S: fine\n  FAIL  branch 'x' is bad. Fix: fix it\n" out))
    (is (= 1 @code)))
  (let [code (atom :unset)
        out  (with-out-str (reset! code (gate/print-verdicts! [{:level :ok :message "fine"}])))]
    (is (= "  ok    fine\n" out))
    (is (nil? @code) "all well: no exit code")))


(defn- hook-answer
  "What the edit hook prints for an edit of a file in a repository on branch 5-x whose root holds `files`, when the
  design approval is judged by `g1`: the refusal's reason, or nil when there is none."
  [files g1]
  (let [dir (str (fs/create-temp-dir))]
    (try (doseq [args [["init" "-q" "-b" "5-x"]
                       ["-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "i"]]]
           (apply p/shell {:dir dir :out :string :err :string} "git" args))
         (doseq [[file text] files]
           (spit (str (fs/path dir file)) text))
         (let [input  (json/generate-string {:tool_input {:file_path (str (fs/path dir "a.txt"))}})
               output (with-redefs-fn {#'gate/gate-g1 g1}
                        #(with-out-str (binding [*in* (java.io.StringReader. input)]
                                         (gate/gate-hook! {}))))]
           (some-> (not-empty output)
                   json/parse-string
                   (get-in ["hookSpecificOutput" "permissionDecisionReason"])))
         (finally (fs/delete-tree dir)))))


(def ^:private a-project {"kit-defaults.edn" "{}" "kit.edn" "{:repo \"o/r\"}"})


(deftest the-edit-hook-names-the-rule-it-refuses-by
  (testing "no approval: the reason says what to do and ends with the rule"
    (is (= "Refused: #5 has no `design-approved`. Fix: wait\n\nRule: gate.design-approval"
           (hook-answer a-project
                        (fn [_ _] {:level :fail :message "#5 has no `design-approved`" :fix "wait"})))))
  (testing "GitHub cannot be asked: refused, with its own rule"
    (let [reason (hook-answer a-project (fn [_ _] (throw (ex-info "no network" {}))))]
      (is (str/starts-with? reason "Refused: could not check the design approval of #5 (no network)"))
      (is (str/ends-with? reason "\n\nRule: gate.approval-unreadable"))))
  (testing "no repository to ask"
    (let [reason (hook-answer {"kit-defaults.edn" "{}"} (fn [_ _] (throw (AssertionError. "not asked"))))]
      (is (str/starts-with? reason "Refused: cannot tell which GitHub repository holds #5"))
      (is (str/ends-with? reason "\n\nRule: gate.no-repository"))))
  (testing "an approved design: no answer"
    (is (nil? (hook-answer a-project (fn [_ _] {:level :ok :message "fine"}))))))
