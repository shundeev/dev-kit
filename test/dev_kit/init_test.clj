(ns dev-kit.init-test
  "What kit init does to a project, told as stories on real git folders in a temporary directory (step 4 of #139):
  it writes nothing until it is sure, brings everything in place, and every refusal says what to do."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.commands :as commands]
   [dev-kit.fake-project :as fake]))


(def ^:private flags {:repo "shundeev/site" :showcase "Site" :modules ""})


(defn- exists? [dir & parts] (fs/exists? (apply fs/path dir parts)))


(deftest a-new-project-gets-its-files-and-a-second-init-changes-nothing
  (let [dir    (fake/new-project)
        before (fake/with-project dir (fake/captured #(commands/init flags)))
        again  (fake/with-project dir (fake/captured #(commands/init flags)))]
    (is (= 0 (:result before)))
    (is (str/includes? (:out before) "wrote .claude/settings.json"))
    (is (= {:repo "shundeev/site" :showcase "Site" :modules #{}}
           (select-keys (edn/read-string (slurp (str (fs/path dir "kit.edn")))) [:repo :showcase :modules])))
    (is (exists? dir "AGENTS.md"))
    (is (= 0 (:result again)))
    (is (str/includes? (:out again) "nothing to change"))))


(deftest a-kit-edn-that-is-wrong-stops-init-before-it-writes-anything
  (let [dir (fake/new-project)
        _ (spit (str (fs/path dir "kit.edn")) "{:repo \"not-a-repo\" :modules #{}}")
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/init {})))]
    (is (= 1 result))
    (is (str/includes? out "FAIL  :repo is \"not-a-repo\", not \"owner/name\"  →  set it in kit.edn"))
    (is (str/includes? out "FAIL  :showcase is missing  →  set it in kit.edn"))
    (is (str/includes? out "nothing was written"))
    (is (not (exists? dir "AGENTS.md")))))


(deftest a-file-of-the-projects-own-where-dev-kit-writes-stops-init-and-stays-untouched
  (let [dir (fake/new-project)
        _ (fs/create-dirs (fs/path dir ".github"))
        _ (spit (str (fs/path dir ".github" "CODEOWNERS")) "* @someone\n")
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/init flags)))]
    (is (= 1 result))
    (is (str/includes? out
                       (str "FAIL  .github/CODEOWNERS holds a file dev-kit did not write  →  "
                            "move it away, or merge what it needs elsewhere; then run kit init again")))
    (is (= "* @someone\n" (slurp (str (fs/path dir ".github" "CODEOWNERS")))))
    (is (not (exists? dir "AGENTS.md")))))


(deftest one-item-that-cannot-be-written-fails-alone-with-its-fix
  (let [dir (fake/new-project)
        _ (fs/create-dirs (fs/path dir ".clj-kondo"))
        _ (spit (str (fs/path dir ".clj-kondo" "config.edn")) "[1 2]")
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/init (assoc flags :modules "clojure"))))]
    (is (= 1 result))
    (is (str/includes? out
                       (str "FAIL  .clj-kondo/config.edn: .clj-kondo/config.edn is not a map: "
                            "make it a map, e.g. {:config-paths")))
    (is (str/includes? out "  →  make it a map by hand, then run kit init again"))
    (is (exists? dir "AGENTS.md") "the other items are done")))


(deftest a-project-without-a-github-origin-is-asked-for-the-repository
  (let [dir     (fake/new-project)
        problem (fake/with-project dir (fake/thrown #(commands/init {:showcase "Site"})))]
    (is (= :init/no-origin (:problem problem)))
    (is (= "pass --repo owner/name" (:fix problem)))
    (is (not (exists? dir "kit.edn")))))
