(ns dev-kit.doctor-test
  "What kit doctor tells the owner about a project's setup, told as stories on real git folders in a temporary
  directory (step 4 of #139): the findings, the exit code, and the machine format that kit itself reads."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.base :as base]
   [dev-kit.checks :as checks]
   [dev-kit.commands :as commands]
   [dev-kit.fake-project :as fake]
   [dev-kit.main :as main]))


(def ^:private init-flags {:repo "shundeev/site" :showcase "Site" :modules ""})


(defn- initialised-project
  "A project that kit init has set up."
  []
  (let [dir (fake/new-project)]
    (fake/with-project dir (fake/captured #(commands/init init-flags)))
    dir))


(deftest a-project-without-kit-edn-is-told-to-run-init
  (let [dir (fake/new-project)
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true})))]
    (is (= 1 result))
    (is (str/includes? out "FAIL  kit.edn is missing  →  "))
    (is (str/includes? out "/bin/kit init --repo <owner/repo> --showcase <name>"))))


(deftest a-project-that-init-set-up-has-nothing-to-fix
  (let [dir (initialised-project)
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true})))]
    (is (= 0 result))
    (is (str/includes? out "ok    everything in place"))))


(deftest a-hand-edit-of-a-managed-file-fails-doctor-and-names-the-fix
  (let [dir (initialised-project)
        _ (spit (str (fs/path dir ".github" "CODEOWNERS")) "* @someone\n")
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true})))]
    (is (= 1 result))
    (is (str/includes? out
                       (str "FAIL  .github/CODEOWNERS: differs from what dev-kit generates  →  "
                            base/mount
                            "/bin/kit init")))))


(deftest a-warning-alone-leaves-doctor-green
  (with-redefs [checks/problems (constantly [(base/finding :warn "something to look at" "look at it")])]
    (let [{:keys [result out]} (fake/captured #(commands/doctor {:quiet true}))]
      (is (= 0 result))
      (is (str/includes? out "WARN  something to look at  →  look at it")))))


(deftest a-quiet-doctor-prints-nothing-when-all-is-well
  (let [dir (initialised-project)
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true :quiet true})))]
    (is (= 0 result))
    (is (= "" out))))


(deftest a-submodule-in-conflict-names-the-path-to-add
  (with-redefs [base/mount "vendor/kit"
                base/sh    (fn [& args]
                             (condp = (vec args)
                               ["git" "config" "-f" ".gitmodules" "--get-regexp" "path"] "submodule.k.path vendor/kit"
                               ["git" "submodule" "status" "vendor/kit"] "U1234567 vendor/kit"
                               nil))]
    (is (= [(base/finding :fail "vendor/kit has a merge conflict" "resolve it, then git add vendor/kit")]
           (checks/submodule-problems)))))


(deftest local-changes-in-the-submodule-name-its-own-folder-in-the-fix
  (with-redefs [base/mount "vendor/kit"
                base/sh    (fn [& args]
                             (condp = (vec args)
                               ["git" "config" "-f" ".gitmodules" "--get-regexp" "path"] "submodule.k.path vendor/kit"
                               ["git" "-C" "vendor/kit" "status" "--porcelain"] " M README.md"
                               nil))]
    (is (= [(base/finding :warn
                          "vendor/kit has local changes"
                          "commit them in dev-kit, or git -C vendor/kit checkout .")]
           (checks/submodule-problems)))))


(deftest every-finding-says-what-is-wrong-and-what-to-do
  (let [dir      (fake/new-project)
        _ (spit (str (fs/path dir "kit.edn")) "{:repo \"x\" :modules [1] :board 3 :agent 5}")
        findings (fake/with-project dir
                                    (checks/problems (edn/read-string (slurp (str (fs/path dir "kit.edn"))))
                                                     {:ci true}))]
    (is (seq findings))
    (doseq [finding findings]
      (is (= #{:level :message :fix} (set (keys finding))) (pr-str finding))
      (is (every? #(not (str/blank? (get finding %))) [:message :fix]) (pr-str finding)))))


(deftest doctor-edn-prints-only-the-findings-for-the-program-that-reads-them
  (let [dir      (fake/new-project)
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true :edn true})))
        findings (edn/read-string out)]
    (is (= 1 result))
    (is (= 1 (count findings)))
    (is (= {:level :fail :message "kit.edn is missing"} (select-keys (first findings) [:level :message])))
    (is (= 1 (main/doctor-problem-count out)) "the count after-git! reports is read from the data")))


(deftest doctor-edn-of-a-healthy-project-is-an-empty-list
  (let [dir (initialised-project)
        {:keys [result out]} (fake/with-project dir (fake/captured #(commands/doctor {:ci true :edn true})))]
    (is (= 0 result))
    (is (= [] (edn/read-string out)))
    (is (= 0 (main/doctor-problem-count out)))))
