(ns dev-kit.flow.main-test
  "The flow's one edge (step 4 of #139): a command that is refused prints its refusal on stderr, as the person reads it,
  and the edge returns the exit code for kit's entry to end the process with."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.main :as flow]
   [dev-kit.flow.shell :as shell]))


(defn- run
  "A flow command run through the edge: {:exit :out :err}."
  [env cmd & args]
  (let [out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)
        exit (binding [*out* out
                       *err* err]
               (with-redefs [shell/env (fn [variable default] (get env variable default))]
                 (flow/main cmd (vec args) "bin/kit")))]
    {:exit exit :out (str out) :err (str err)}))


(deftest a-command-that-went-through-exits-0-and-a-help-prints-its-usage
  (let [{:keys [exit out err]} (run {} "checks" "--help")]
    (is (= 0 exit))
    (is (str/starts-with? out "Usage: kit checks"))
    (is (= "" err))))


(deftest a-refusal-is-printed-once-as-error-and-its-fix-with-the-exit-code-1
  (let [{:keys [exit out err]} (run {} "merge" "--bogus")]
    (is (= 1 exit))
    (is (= "" out))
    (is (= "Error: Unknown argument: --bogus. Fix: run the command with --help to see its options\n" err))))


(deftest board-show-without-a-board-says-so-and-what-to-add
  (let [{:keys [exit out err]} (run {} "board" "show")]
    (is (= 1 exit))
    (is (= "" out))
    (is (str/includes? err "no board"))
    (is (str/includes? err "Fix: add :board to kit.edn"))))


(deftest board-show-prints-the-boards-json
  (let [{:keys [exit out]} (run {"GHWF_OWNER" "o" "GHWF_PROJECT_NUMBER" "3"} "board" "show")]
    (is (= 0 exit))
    (is (= "{\"owner\":\"o\",\"number\":\"3\"}\n" out))))


(deftest board-set-without-its-six-words-shows-the-usage-with-exit-2
  (let [{:keys [exit err]} (run {} "board" "set" "3")]
    (is (= 2 exit))
    (is (str/starts-with? err "Usage: kit board set <project-number> <owner> <issue-url> <field> <option>"))
    (is (str/includes? err "\nFix: give the arguments as shown"))))
