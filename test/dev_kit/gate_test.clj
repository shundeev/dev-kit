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


(deftest an-m-pull-request-states-its-size
  (let [m-issue {"labels" {"nodes" [{"name" "size:M"}]}}]
    (is (= [:gate/no-size-line] (mapv :problem (gate/size-problems m-issue "no size here"))))
    (is (nil? (gate/size-problems m-issue "Size: 164 → 150 lines after simplify")))
    (is (nil? (gate/size-problems {"labels" {"nodes" [{"name" "size:S"}]}} "no size here"))
        "an S pull request needs none")))


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


;; The gate in CI, from what GitHub says (the selftest's cases of #26, #21, #117, #139 step 8)


(def ^:private t0 "2026-11-12T10:00:00Z")


(def ^:private t1 "2026-11-12T11:00:00Z")


(def ^:private t2 "2026-11-12T12:00:00Z")


(def ^:private full-body
  (str "## What changed\n- x\nSize: 400 → 300 lines after simplify\n\n## Review\n<!-- hint -->\nSelf-review: fine."
       "\n\n## User-level test\nRan it.\n\nADR: none"))


(defn- file-of
  [filename additions & [extra]]
  (merge {"filename" filename "status" "modified" "additions" additions "deletions" 0} extra))


(defn- plain-files
  "The files of a pull request that changes `n` lines of code and a lock file that is not counted."
  [n]
  [(file-of "src/a.clj" n) (file-of "package-lock.json" 900)])


(defn- design-comment
  "The issue's `## Design` comment, written at t0 and edited at `edited-at`."
  [edited-at]
  {"body" "## Design (proposed)" "createdAt" t0 "lastEditedAt" edited-at})


(defn- designed-issue
  "An approved-issue with its design comment."
  [{:keys [design-edited] :as options}]
  (approved-issue (assoc options :approved-at t1 :comments [(design-comment design-edited)])))


(defn- github-says
  "A base/gh! that answers as GitHub does for the gate: the issue, the pull request (title, body), its files as the
  lines gh prints them, and the permission of everyone named in `permissions`. It records its calls in `calls`."
  [{:keys [issue title body files permissions unreachable calls]
    :or   {permissions {"owner" "admin" "the-agent" "write"}}}]
  (fn [& args]
    (swap! calls conj (vec args))
    (when unreachable
      (throw (ex-info "error connecting to api.github.com" {})))
    (let [path (second args)]
      (cond
        (= "graphql" path) (json/generate-string {"data" {"repository" {"issue" issue}}})
        (str/includes? path "/files") (str/join "\n" (map json/generate-string files))
        (str/ends-with? path "/permission") (get permissions (second (re-find #"collaborators/([^/]+)/" path)))
        :else (json/generate-string {"title" title "body" body})))))


(defn- gate-says
  "What `kit gate --branch <branch> --pr 7` prints and the exit code it asks for, for a pull request on GitHub as
  `says` tells; and the calls it made."
  [{:keys [branch] :or {branch "26-x"} :as says}]
  (let [calls (atom [])
        code  (atom nil)
        out   (with-redefs-fn {#'base/gh! (github-says (assoc says :calls calls))
                               #'gate/gate-config (constantly {:agent {:login "the-agent"}})
                               #'gate/managed-paths (constantly #{})}
                #(with-out-str (reset! code (gate/gate! {:repo "u473t8/fake" :branch branch :pr "7"}))))]
    {:out out :code @code :calls @calls}))


(defn- pull-request
  "The says-map of a clean pull request of issue 26: a small S issue, a title and body that pass."
  [& {:as changes}]
  (merge {:issue (assoc (approved-issue {:labels ["size:S"]}) "lastEditedAt" nil)
          :title "feat(GH-26): add x"
          :body  full-body
          :files (plain-files 10)}
         changes))


(deftest the-design-approval-gate-says-what-the-issue-lacks-and-what-to-do
  (doseq [[what says code lines]
          [["an approved M issue passes, and names who approved"
            (pull-request :issue (designed-issue {:by "owner"})) nil ["approved by owner"]]
           ["an M issue without design-approved fails and names the label"
            (pull-request :issue (designed-issue {:labels ["size:M"]})) 1 ["design-approved"]]
           ["a label set by the agent does not count"
            (pull-request :issue (designed-issue {:by "the-agent"})) 1 ["set by the agent"]]
           ["an issue body edited after the approval is stale"
            (pull-request :issue (designed-issue {:edited-at t2})) 1 ["after the approval"]]
           ["a design comment edited after the approval is stale"
            (pull-request :issue (designed-issue {:design-edited t2})) 1 ["a `## Design` comment on #26 changed"]]
           ["no size label counts as M, and says so"
            (pull-request :issue (designed-issue {:labels ["design-approved"]})) nil ["no size label"]]
           ["an S pull request within the threshold passes without approval, lock files not counted"
            (pull-request :files (plain-files 150)) nil ["150 lines"]]
           ["an S pull request over the threshold fails and says to resize"
            (pull-request :files (plain-files 250)) 1 ["size:M"]]]]
    (let [{:keys [out] :as says-out} (gate-says says)]
      (is (= code (:code says-out)) (str what ": " out))
      (doseq [line lines]
        (is (str/includes? out line) (str what ", names: " line))))))


(deftest the-pull-request-gate-lists-what-is-wrong-with-the-shape-and-the-fix
  (doseq [[what says code lines]
          [["a well-formed S pull request passes" (pull-request) nil []]
           ["a branch without an issue number fails and names kit start"
            (pull-request :branch "feature-x") 1 ["kit start"]]
           ["a closed issue fails"
            (pull-request :issue (assoc (:issue (pull-request)) "state" "CLOSED")) 1 ["#26 is closed"]]
           ["a title without type(GH-n) fails, with the command that renames it"
            (pull-request :title "add x") 1 ["gh pr edit 7 --title"]]
           ["a title naming another issue fails" (pull-request :title "feat(GH-25): add x") 1 ["GH-26"]]
           ["a type outside Conventional Commits fails" (pull-request :title "update(GH-26): add x") 1 ["refactor"]]
           ["a body without an ADR line fails and names the line"
            (pull-request :body (str/replace full-body "ADR: none" "")) 1 ["ADR: none"]]
           ["an ADR line with a reason after it passes"
            (pull-request :body (str/replace full-body "ADR: none" "ADR: none — a process change, decided in ADR-0001"))
            nil []]
           ["an ADR line that names no ADR fails"
            (pull-request :body (str/replace full-body "ADR: none" "ADR: maybe")) 1 ["ADR: none"]]
           ["a Review section with only the hint fails and names the section"
            (pull-request :body (str/replace full-body "Self-review: fine." "")) 1 ["Review"]]
           ["a missing User-level test section fails and names it"
            (pull-request :body (str/replace full-body "## User-level test" "## Testing")) 1 ["User-level test"]]
           ["a branch number that is no issue fails and says so"
            (pull-request :issue nil) 1 ["#26 is not an issue"]]
           ["101 files are counted in full"
            (pull-request :files
                          (vec (for [i (range 101)]
                                 (file-of (str "src/f" i ".clj") 2)))) 1 ["202 lines"]]]]
    (let [{:keys [out] :as says-out} (gate-says says)]
      (is (= code (:code says-out)) (str what ": " out))
      (doseq [line lines]
        (is (str/includes? out line) (str what ", names: " line))))))


(deftest the-gate-reads-every-page-of-the-files-of-a-pull-request
  (is (some #(and (= "api" (first %)) (some #{"--paginate"} %) (str/includes? (second %) "/pulls/7/files"))
            (:calls (gate-says (pull-request))))))


(deftest an-m-pull-request-says-how-much-the-simplify-pass-removed
  (doseq [[labels line passes?]
          [[["design-approved" "size:M"] nil false]
           [["design-approved" "size:M"] "Size: M → M after simplify" false]
           [["design-approved" "size:M"] "Size: 164 → 164 lines after simplify" true]
           [["design-approved" "size:M"] "Size: 164 -> 150" true]
           [["design-approved" "size:M"] :as-written true]
           [["design-approved"] nil false]
           [["size:S"] nil true]]]
    (let [body  (condp = line
                  :as-written full-body
                  (str/replace full-body "Size: 400 → 300 lines after simplify" (or line "")))
          issue (if (= ["size:S"] labels)
                  (:issue (pull-request))
                  (designed-issue {:labels labels}))
          {:keys [out code]} (gate-says (pull-request :issue issue :body body))]
      (is (= passes? (nil? code)) (str labels " " (pr-str line) ": " out))))
  (testing "a line without numbers, and a missing line, are named with their fix"
    (doseq [line ["Size: M → M after simplify" ""]]
      (let [{:keys [out]} (gate-says (pull-request :issue (designed-issue {:labels ["size:M"]})
                                                   :body  (str/replace full-body
                                                                       "Size: 400 → 300 lines after simplify"
                                                                       line)))]
        (is (str/includes? out "Size: 164 → 150 lines after simplify") (pr-str line))))))


(deftest every-problem-of-a-pull-request-is-listed-not-only-the-first
  (let [{:keys [out code]} (gate-says (pull-request :title "add x" :body "no sections"))]
    (is (= 1 code))
    (is (>= (count (re-seq #"(?m)^  FAIL  " out)) 4) out)))


(deftest a-rule-line-removed-from-the-shared-rules-is-quoted-in-the-body
  (let [files [(file-of "rules/shared.md"
                        1
                        {"deletions" 1 "patch" "@@ -1,3 +1,3 @@\n keep\n-old rule\n-  that wraps\n+new rule\n"})]]
    (let [{:keys [out code]} (gate-says (pull-request :files files))]
      (is (= 1 code))
      (is (str/includes? out "'old rule'. Fix: quote the old line in the PR body, next to the new one")))
    (is (nil? (:code (gate-says (pull-request
                                 :files files
                                 :body  (str/replace full-body "- x" "- was: old rule that wraps, now: new rule")))))
        "quoted on one line, the wrapped line passes")
    (is (nil? (:code (gate-says (pull-request
                                 :files
                                 [(file-of "rules/shared.md" 1 {"patch" "@@ -1,2 +1,3 @@\n keep\n+new rule\n"})]))))
        "a line only added passes")))


(deftest accepted-adrs-are-judged-on-the-files-of-the-pull-request
  (doseq [[what file code]
          [["a new ADR passes" (file-of "adr/0005-new.md" 1 {"status" "added"}) nil]
           ["an edited accepted ADR fails" (file-of "adr/0001-a.md" 1) 1]
           ["a deleted accepted ADR fails" (file-of "adr/0001-a.md" 1 {"status" "removed"}) 1]
           ["a renamed accepted ADR fails"
            (file-of "adr/0001-b.md" 1 {"status" "renamed" "previous_filename" "adr/0001-a.md"}) 1]
           ["the ADR index is not an ADR" (file-of "adr/README.md" 1) nil]]]
    (let [{:keys [out] :as says-out} (gate-says (pull-request :files [file]))]
      (is (= code (:code says-out)) (str what ": " out))
      (when code
        (is (str/includes? out "Supersedes: ADR-0001") what)))))


(deftest a-github-that-cannot-be-read-fails-the-gate-and-says-to-rerun
  (let [{:keys [out code]} (gate-says (pull-request :unreachable true))]
    (is (= 1 code))
    (is (str/includes? out "rerun the job"))
    (is (not (re-find #"Exception|----- Error" out)) "no stack trace")))


;; The edit hook on real repositories, with GitHub's answers told (the selftest's three hook sections, #99, #139 step 8)


(defn- new-repo
  "A repository on `branch` (made from main): kit.edn or kit-defaults.edn at its root as `root-file` names it (nil for
  neither), and an origin when `origin` is given."
  [& {:keys [branch root-file origin] :or {branch "26-x" root-file "kit.edn"}}]
  (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "kit-hook-"})))
        git #(apply p/shell {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@t" %&)]
    (git "init" "-q" "-b" "main")
    (when root-file
      (spit (str (fs/path dir root-file)) "{}")
      (git "add" root-file))
    (git "commit" "-q" "--allow-empty" "-m" "base")
    (when branch
      (git "switch" "-q" "-c" branch))
    (when origin
      (git "remote" "add" "origin" origin))
    dir))


(defn- detached-repo
  "A repository with kit.edn on a detached HEAD, as the .shared submodule is."
  []
  (let [dir (new-repo :branch nil)]
    (p/shell {:dir dir :out :string :err :string} "git" "switch" "-q" "--detach")
    dir))


(defn- forget-verdicts!
  "Removes the verdict cache of a repository."
  [dir]
  (fs/delete-tree (fs/path dir ".git" "kit")))


(defn- hook-says
  "What the edit hook prints for the input, with GitHub as `says` tells (one `github-says` map; a missing :issue is
  an unapproved M issue); {:out the hook's output :calls what it asked GitHub}."
  [input says]
  (let [calls (atom [])
        issue (or (:issue says) (designed-issue {:labels ["size:M"]}))
        out   (with-redefs-fn {#'base/gh! (github-says (assoc says :issue issue :calls calls))}
                #(with-out-str (binding [*in* (java.io.StringReader. input)]
                                 (gate/gate-hook! {}))))]
    {:out out :calls @calls}))


(defn- edit-of
  "The hook's input for an edit of `file`; with `cwd` it names the session's folder."
  [file & [cwd]]
  (json/generate-string (cond-> {:tool_name "Edit" :tool_input {:file_path file}}
                          cwd (assoc :cwd cwd))))


(defn- refused?
  [{:keys [out]}]
  (str/includes? out "\"permissionDecision\":\"deny\""))


(def ^:private approved (designed-issue {:labels ["design-approved" "size:M"] :by "owner"}))


(def ^:private github-origin "https://github.com/u473t8/fake.git")


(deftest the-edit-hook-refuses-an-unapproved-m-branch-and-asks-github-once-in-ten-minutes
  (let [dir (new-repo :origin github-origin)
        at  #(str dir "/" %)]
    (is (refused? (hook-says (edit-of (at "src/a.clj")) {})) "an edit on an unapproved M branch is refused")
    (is (= "" (:out (hook-says (edit-of (at "adr/0004-x.md")) {}))) "adr/ may be written before the approval")
    (let [again (hook-says (edit-of (at "src/b.clj")) {})]
      (is (refused? again) "the refusal stays")
      (is (empty? (:calls again)) "from the cache: GitHub is not asked again"))
    (forget-verdicts! dir)
    (is (= "" (:out (hook-says (edit-of (at "src/a.clj")) {:issue approved}))) "an approved branch may be edited")
    (forget-verdicts! dir)
    (let [unreachable (hook-says (edit-of (at "src/a.clj")) {:unreachable true})]
      (is (refused? unreachable) "GitHub unreachable means refused")
      (is (str/includes? (:out unreachable) "gate --g1 26") "with the retry"))
    (p/shell {:dir dir :out :string :err :string} "git" "switch" "-q" "main")
    (is (= "" (:out (hook-says (edit-of (at "src/a.clj")) {}))) "a branch without an issue is not gated")))


(deftest the-edit-hook-judges-the-repository-of-the-edited-file-not-the-sessions
  (let [session  (new-repo :branch "26-gate" :origin github-origin)
        other    (new-repo :branch "26-other" :origin github-origin)
        in-other #(str other "/" %)
        here     #(edit-of % session)]
    (is (refused? (hook-says (here (in-other "src/a.clj")) {})) "another repository on an unapproved M branch")
    (forget-verdicts! other)
    (is (refused? (hook-says (here (in-other "new/deep/dir/a.clj")) {}))
        "a new file in a directory that does not exist yet is refused too")
    (fs/create-dirs (fs/path other "src"))
    (spit (in-other "src/a.clj") "")
    (let [folder-link (str (fs/path (fs/create-temp-dir) "skill-dir"))
          file-link   (str (fs/path session "link.clj"))]
      (fs/create-sym-link folder-link (fs/path other "src"))
      (fs/create-sym-link file-link (fs/path other "src" "a.clj"))
      (forget-verdicts! other)
      (is (refused? (hook-says (here (str folder-link "/a.clj")) {}))
          "a file reached through a symlinked folder is judged by its repository")
      (forget-verdicts! other)
      (is (refused? (hook-says (here file-link) {}))
          "a symlinked file is judged by the repository it points into"))
    (forget-verdicts! other)
    (is (= "" (:out (hook-says (here (in-other "src/a.clj")) {:issue approved}))) "with the approval it may be edited")
    (forget-verdicts! other)
    (let [unreachable (hook-says (here (in-other "src/a.clj")) {:unreachable true})]
      (is (refused? unreachable))
      (is (str/includes? (:out unreachable) "gate --g1 26") "GitHub unreachable: refused, with the retry"))
    (testing "what is not on dev-kit, or not on a branch, is let through without asking GitHub"
      (let [plain    (str (fs/create-temp-dir))
            no-kit   (new-repo :root-file nil)
            detached (detached-repo)
            results  (for [file [(str plain "/notes.txt") (str no-kit "/src/a.clj") (str detached "/src/a.clj")
                                 (in-other "adr/0009-x.md")]]
                       (hook-says (here file) {}))]
        (is
         (every? #(= "" (:out %)) results)
         "outside any repository, in a repository without kit.edn, on a detached HEAD, in adr/ of another repository")
        (is (every? #(empty? (:calls %)) results) "none of these asked GitHub")))
    (testing "dev-kit itself has kit-defaults.edn at its root, not kit.edn, and is gated"
      (let [kit (new-repo :branch "26-k" :root-file "kit-defaults.edn" :origin github-origin)]
        (is (refused? (hook-says (here (str kit "/bin/a.clj")) {})))))))


(deftest an-edit-with-no-path-is-judged-by-the-folder-the-input-names
  (let [session (new-repo :branch "26-gate" :origin github-origin)]
    (doseq [[what input] [["an unknown field name" {:tool_name "MultiEdit" :tool_input {:path "X"} :cwd session}]
                          ["an empty tool input" {:tool_name "Edit" :tool_input {} :cwd session}]
                          ["no tool input" {:tool_name "Edit" :cwd session}]]]
      (forget-verdicts! session)
      (is (refused? (hook-says (json/generate-string input) {})) (str what ": it must not open the gate")))))


(deftest the-edit-hook-asks-the-repository-of-the-edited-file
  (let [no-remote (new-repo)
        ask       #(hook-says (edit-of (str %1 "/src/a.clj")) %2)]
    (testing "no GitHub origin and no :repo: refused, naming the fix, and GitHub is not asked"
      (let [said (ask no-remote {})]
        (is (refused? said))
        (is (str/includes? (:out said) ":repo in that repository"))
        (is (empty? (:calls said)))))
    (testing "a gitlab origin counts as none"
      (p/shell {:dir no-remote :out :string :err :string}
               "git"
               "remote" "add"
               "origin" "https://gitlab.com/someone/o-repo.git")
      (forget-verdicts! no-remote)
      (is (str/includes? (:out (ask no-remote {})) ":repo in that repository")))
    (testing "the :repo of its kit.edn is used, and that repository is asked"
      (spit (str (fs/path no-remote "kit.edn")) "{:repo \"u473t8/from-kit-edn\"}")
      (forget-verdicts! no-remote)
      (let [said (ask no-remote {})]
        (is (refused? said))
        (is (some #(some #{"n=from-kit-edn"} %) (:calls said)))))
    (testing "its origin comes before :repo"
      (p/shell {:dir no-remote :out :string :err :string} "git" "remote" "remove" "origin")
      (p/shell {:dir no-remote :out :string :err :string}
               "git"
               "remote" "add"
               "origin" "https://github.com/u473t8/o-repo.git")
      (forget-verdicts! no-remote)
      (let [said (ask no-remote {})]
        (is (some #(some #{"n=o-repo"} %) (:calls said)))
        (is (not-any? #(some #{"n=from-kit-edn"} %) (:calls said)))))))


(deftest two-repositories-with-the-same-issue-number-keep-separate-verdicts
  (let [one (new-repo :origin github-origin)
        two (new-repo :origin github-origin)
        ask #(hook-says (edit-of (str %1 "/src/a.clj")) %2)]
    (is (refused? (ask one {})))
    (is (= "" (:out (ask two {:issue approved}))) "the cached refusal does not reach another repository's #26")
    (is (refused? (ask one {:issue approved})) "nor does the other's approval reach it")))
