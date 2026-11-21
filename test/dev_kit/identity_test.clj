(ns dev-kit.identity-test
  "kit identity: the agent's account written into a repository's local settings, told as stories on real git folders
  in a temporary directory (step 4 of #139)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.commands :as commands]
   [dev-kit.fake-project :as fake]))


(defn- local-settings [dir] (json/parse-string (slurp (str (fs/path dir ".claude" "settings.local.json")))))


(deftest identity-writes-the-agent-into-the-repository-and-a-second-run-changes-nothing
  (let [dir    (fake/new-folder)
        before (fake/with-project dir (fake/captured #(commands/identity! {:dir dir})))
        again  (fake/with-project dir (fake/captured #(commands/identity! {:dir dir})))]
    (is (= "u473t8-angel" (get-in (local-settings dir) ["env" "GIT_AUTHOR_NAME"])))
    (is (str/includes? (:out before) "wrote .claude/settings.local.json"))
    (is (str/includes? (:out before) "WARN  the agent's gh config"))
    (is (str/includes? (:out again) "nothing to change"))))


(deftest a-folder-that-is-not-a-repository-root-is-refused-with-its-fix
  (let [dir     (fake/new-folder)
        inside  (str (fs/create-dirs (fs/path dir "sub")))
        problem (fake/with-project dir (fake/thrown #(commands/identity! {:dir inside})))]
    (is (= :identity/not-a-root (:problem problem)))
    (is (str/ends-with? (:message problem) "is not the root of a git repository"))
    (is (str/includes? (:fix problem) "--dir"))))


(deftest without-an-agent-in-kit-edn-nothing-can-be-written
  (let [dir     (fake/new-folder)
        _ (spit (str (fs/path dir "kit.edn")) "{:agent nil}")
        problem (fake/with-project dir (fake/thrown #(commands/identity! {:dir dir})))]
    (is (= :identity/no-agent (:problem problem)))
    (is (str/includes? (:fix problem) ":gh-config-dir"))
    (is (not (fs/exists? (fs/path dir ".claude" "settings.local.json"))))))
