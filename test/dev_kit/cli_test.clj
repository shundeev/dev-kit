(ns dev-kit.cli-test
  "The command line of every command in the command tree (step 5 of #139), told as what a person typing it sees:
  help, a mistake with the help of the command it was made in, and the options a command receives. Help wraps at the
  terminal's width, so a story matches pieces of text, never a whole help."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.cli :as cli]
   [dev-kit.fake-project :as fake]
   [dev-kit.gate :as gate]
   [dev-kit.main :as main]))


(def ^:private leaves
  "The commands that run something: every entry of the tree that is no group of other commands."
  (let [groups (set (mapcat #(butlast (reductions conj [] (:cmds %))) cli/tree))]
    (remove #(contains? groups (:cmds %)) cli/tree)))


(defn- mistake
  "The problem a command line is refused with, nil when it is accepted."
  [& argv]
  (fake/thrown #(main/command-line (vec argv))))


(defn- opts-of
  "The options a command receives from a command line."
  [& argv]
  (:opts (main/command-line (vec argv))))


(defn- help-of
  "What a command line prints on stdout: its help, with the exit code kit ends with."
  [& argv]
  (let [{:keys [result out err]} (fake/captured #(main/run (vec argv)))]
    {:exit result :out out :err err}))


(defn- printed-help
  "What the tree prints for a command line that asks for help, read as kit reads it before it runs a command: the help,
  and that nothing was run. (kit merge in dev-kit's own checkout first asks whether the base holds newer code, which
  looks at the repository's remote, so it is not run here.)"
  [& argv]
  (let [{:keys [result out err]} (fake/captured #(main/command-line (vec argv)))]
    {:exit (if (nil? result) 0 1) :out out :err err}))


(deftest every-command-refuses-a-flag-it-does-not-have-and-shows-its-help
  (doseq [{:keys [cmds]} leaves]
    (testing (str "kit " (str/join " " cmds))
      (let [problem (apply mistake (concat cmds ["--bogus"]))]
        (is (= :usage/command-line (:problem problem)))
        (is (str/starts-with? (:message problem) "Unknown option: --bogus\n\nUsage: kit "))
        (is (str/includes? (:message problem) (str "kit " (str/join " " cmds))) "the help is the command's own")
        (is (str/includes? (:fix problem) (str "kit " (str/join " " cmds) " --help")))))))


(deftest every-command-refuses-a-word-it-does-not-take
  (doseq [{:keys [cmds spec]} leaves
          :let [positional (count (filter (comp :positional second) spec))]]
    (testing (str "kit " (str/join " " cmds))
      (let [words   (concat cmds (repeat positional "x") ["extra"])
            problem (apply mistake words)]
        (is (some? problem) "the extra word is refused")
        (is (str/starts-with? (str (:message problem)) "Unexpected argument: extra"))))))


(deftest every-flag-and-argument-of-a-command-is-in-its-help
  (doseq [{:keys [cmds spec]} leaves]
    (testing (str "kit " (str/join " " cmds))
      (let [{:keys [exit out err]} (apply printed-help (concat cmds ["--help"]))]
        (is (= 0 exit))
        (is (= "" err))
        (is (str/starts-with? out (str "Usage: kit " (str/join " " cmds))))
        (is (str/includes? out "-h, --help"))
        (doseq [[k {:keys [positional desc]}] spec]
          (is (string? desc) (str k " has a description"))
          (is (str/includes? out
                             (if positional
                               (str "<" (name k) ">")
                               (str "--" (name k))))
              (str (name k) " is in the help")))))))


(deftest help-comes-before-a-mistake-and-with-the-short-flag
  (doseq [argv [["start" "--help" "--bogus"] ["start" "-h"] ["start" "--title" "x" "--help"] ["hook" "-h"]]]
    (let [{:keys [exit out err]} (apply printed-help argv)]
      (is (= 0 exit) (str argv))
      (is (str/includes? out (str "Usage: kit " (first argv))) (str argv))
      (is (= "" err) (str argv)))))


(deftest kit-alone-lists-the-commands-and-exits-1-and-kit-help-lists-them-and-exits-0
  (let [{:keys [exit out err]} (help-of)]
    (is (= 1 exit))
    (is (= "" out))
    (is (str/starts-with? err "No command given.\n\nUsage: kit <command>"))
    (is (str/includes? err "\nFix: pick one of the commands above")))
  (doseq [flag ["--help" "-h"]]
    (let [{:keys [exit out err]} (help-of flag)]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/starts-with? out "Usage: kit"))
      (doseq [{:keys [cmds]} cli/tree
              :when (= 1 (count cmds))]
        (is (str/includes? out (str "  " (first cmds) " ")) (str "kit lists " (first cmds)))))))


(deftest an-unknown-command-and-a-group-without-its-command-are-refused-with-the-list
  (let [problem (mistake "frobnicate")]
    (is (str/starts-with? (:message problem) "Unknown command: frobnicate\n\nUsage: kit <command>"))
    (is (= "pick one of the commands above" (:fix problem))))
  (let [problem (mistake "board")]
    (is (str/starts-with? (:message problem) "No command given.\n\nUsage: kit board <command>"))
    (is (str/includes? (:message problem) "  show "))
    (is (str/includes? (:message problem) "  set "))))


(deftest a-flag-that-takes-a-value-is-refused-without-one
  (doseq [[argv flag] [[["start" "--title"] "--title"]
                       [["start" "--title" "x" "--body"] "--body"]
                       [["start" "--title" "x" "--priority"] "--priority"]
                       [["gate" "--pr"] "--pr"]
                       [["init" "--showcase"] "--showcase"]
                       [["retro" "5" "--days"] "--days"]
                       [["start" "--title" "x" "--body" "-x"] "--body"]]]
    (let [problem (apply mistake argv)]
      (is (str/starts-with? (str (:message problem)) (str "Missing value for option " flag)) (str argv)))))


(deftest a-flag-that-may-repeat-collects-its-values-and-needs-one-each-time
  (is (= {:add ["a" "b"]} (opts-of "finish" "--add" "a" "--add" "b")))
  (is (str/starts-with? (:message (mistake "finish" "--add" "a" "--add")) "Missing value for option --add")))


(deftest the-types-of-a-subject-are-the-types-the-gate-accepts
  (let [[_ {:keys [enum]}] (->> cli/tree
                                (filter #(= ["finish"] (:cmds %)))
                                first
                                :spec
                                (filter #(= :type (first %)))
                                first)]
    (is (= (set gate/commit-types) (set enum)))
    (is (str/starts-with? (:message (mistake "finish" "--type" "feature"))
                          "Invalid value for option --type: feature. Expected one of: feat, fix"))))


(deftest an-empty-value-is-a-value
  (is (= {:modules ""} (opts-of "init" "--modules" ""))))


(deftest a-value-off-the-choices-is-refused-naming-them
  (is (str/starts-with? (:message (mistake "start" "--title" "x" "--priority" "Huge"))
                        "Invalid value for option --priority: Huge. Expected one of: Urgent, High, Medium, Low"))
  (is (str/starts-with? (:message (mistake "start" "--title" "x" "--mode" "other"))
                        "Invalid value for option --mode: other. Expected one of: issue, draft-convert")))


(deftest a-required-flag-and-the-required-arguments-are-asked-for
  (is (str/starts-with? (:message (mistake "start")) "Required option: --title"))
  (is (str/starts-with? (:message (mistake "hook")) "Required argument: <command>"))
  (is (some? (mistake "board" "set" "3" "o" "url" "field")) "four of the five arguments of board set")
  (is (some? (mistake "board" "set")) "none of them"))


(deftest the-arguments-of-a-command-arrive-as-its-options
  (is (= {:command "git reset --hard"} (opts-of "hook" "git reset --hard")))
  (is (= {:command "5"} (opts-of "hook" "5")) "an argument stays the text it is")
  (is (= {:issue "55"} (opts-of "retro" "55")))
  (is (=
       {:project-number "3" :owner "u473t8" :issue-url "https://github.com/o/r/issues/1" :field "Status" :option "Done"}
       (opts-of "board" "set" "3" "u473t8" "https://github.com/o/r/issues/1" "Status" "Done"))))


(deftest the-flags-of-each-command-arrive-as-the-options-its-code-reads
  (doseq [[argv expected]
          [[["init"] {}]
           [["init" "--repo" "o/n" "--showcase" "Site" "--modules" "clojure" "--scaffold"]
            {:repo "o/n" :showcase "Site" :modules "clojure" :scaffold true}]
           [["init" "--local"] {:local true}]
           [["doctor" "--ci" "--quiet"] {:ci true :quiet true}]
           [["doctor" "--edn"] {:edn true}]
           [["modules"] {}]
           [["user-test"] {}]
           [["sync" "--after-git"] {:after-git true}]
           [["sync"] {}]
           [["identity" "--dir" "/tmp/x"] {:dir "/tmp/x"}]
           [["github" "--check" "checks" "--yes" "--repo" "o/n"] {:check "checks" :yes true :repo "o/n"}]
           [["gate" "--branch" "26-x" "--pr" "9"] {:branch "26-x" :pr "9"}]
           [["gate" "--g1" "7"] {:g1 "7"}]
           [["gate" "--hook"] {:hook true}]
           [["gate" "--shape" "--branch" "26-x" "--title" "feat(GH-26): x" "--body-file" "b.md" "--repo" "o/n"]
            {:shape true :branch "26-x" :title "feat(GH-26): x" :body-file "b.md" :repo "o/n"}]
           [["retro" "12"] {:issue "12"}]
           [["retro" "--missing" "--repo" "o/n"] {:missing true :repo "o/n"}]
           [["retro" "12" "--repo" "o/n" "--messages" "--days" "30" "--now" "2026-11-20T10:00:00Z"]
            {:issue "12" :repo "o/n" :messages true :days "30" :now "2026-11-20T10:00:00Z"}]
           [["board" "show"] {}]
           [["start" "--title" "Add landing page" "--priority" "High"] {:title "Add landing page" :priority "High"}]
           [["start" "--title" "t" "--body" "b" "--body-file" "f" "--labels" "a,b" "--assignees" "@me" "--issue" "7"
             "--showcase" "Site" "--priority" "Low" "--size" "S" "--milestone" "v1" "--status" "Backlog"
             "--mode" "draft-convert" "--owner" "o" "--repo" "o/n" "--project-number" "3" "--showcase-field" "Sc"
             "--priority-field" "Pr" "--size-field" "Si" "--status-field" "St" "--base" "next" "--branch-name" "x"
             "--no-reuse-existing" "--skip-branch" "--skip-retro"]
            {:title             "t"
             :body              "b"
             :body-file         "f"
             :labels            "a,b"
             :assignees         "@me"
             :issue             "7"
             :showcase          "Site"
             :priority          "Low"
             :size              "S"
             :milestone         "v1"
             :status            "Backlog"
             :mode              "draft-convert"
             :owner             "o"
             :repo              "o/n"
             :project-number    "3"
             :showcase-field    "Sc"
             :priority-field    "Pr"
             :size-field        "Si"
             :status-field      "St"
             :base              "next"
             :branch-name       "x"
             :no-reuse-existing true
             :skip-branch       true
             :skip-retro        true}]
           [["start" "--title" "t" "--priority" "High" "--no-checkout"]
            {:title "t" :priority "High" :no-checkout true}]
           [["finish"] {}]
           [["finish" "--add" "a" "--add" "b" "--no-commit" "--hold"] {:add ["a" "b"] :no-commit true :hold true}]
           [["finish" "--type" "fix" "--summary" "s" "--commit-message-file" "m.txt" "--pr-body-file" "b.md" "--last"
             "--repo" "o/n" "--owner" "o" "--project-number" "3" "--base" "next" "--status-field" "St"
             "--review-status" "Looked at"]
            {:type          "fix"
             :summary       "s"
             :commit-message-file "m.txt"
             :pr-body-file  "b.md"
             :last          true
             :repo          "o/n"
             :owner         "o"
             :project-number "3"
             :base          "next"
             :status-field  "St"
             :review-status "Looked at"}]
           [["release"] {}]
           [["release" "--pr" "https://github.com/o/r/pull/4" "--repo" "o/n"]
            {:pr "https://github.com/o/r/pull/4" :repo "o/n"}]
           [["merge"] {}]
           [["merge" "--repo" "o/n" "--owner" "o" "--project-number" "3" "--base" "next" "--status-field" "St"
             "--done-status" "Shipped"]
            {:repo "o/n" :owner "o" :project-number "3" :base "next" :status-field "St" :done-status "Shipped"}]
           [["checks"] {}]
           [["checks" "--pr" "4" "--repo" "o/n"] {:pr "4" :repo "o/n"}]
           [["review"] {}]
           [["review" "--pr" "4" "--repo" "o/n" "--wait"] {:pr "4" :repo "o/n" :wait true}]]]
    (is (= expected (apply opts-of argv)) (str/join " " argv))))
