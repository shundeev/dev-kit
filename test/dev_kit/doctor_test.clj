(ns dev-kit.doctor-test
  "What kit doctor tells the owner about a project's setup, told as stories on real git folders in a temporary
  directory (step 4 of #139): the findings, the exit code, and the machine format that kit itself reads."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.base :as base]
   [dev-kit.checks :as checks]
   [dev-kit.commands :as commands]
   [dev-kit.config :as config]
   [dev-kit.fake-project :as fake]
   [dev-kit.identity :as ident]
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
    (is (str/includes? out "FAIL  kit.edn is missing. Fix: "))
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
                       (str "FAIL  .github/CODEOWNERS: differs from what dev-kit generates. Fix: "
                            base/mount
                            "/bin/kit init")))))


(deftest a-warning-alone-leaves-doctor-green
  (with-redefs [checks/problems (constantly [(base/finding :warn "something to look at" "look at it")])]
    (let [{:keys [result out]} (fake/captured #(commands/doctor {:quiet true}))]
      (is (= 0 result))
      (is (str/includes? out "WARN  something to look at. Fix: look at it")))))


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


;; The GitHub side of the check, read as the agent (the selftest's doctor cases of #33, #139 step 8)


(def ^:private agent-config {:repo "shundeev/kit-selftest" :agent {:login "kit-agent"}})


(def ^:private good-ruleset
  {:id          7
   :enforcement "active"
   :current_user_can_bypass "never"
   :rules       [{:type "pull_request" :parameters {:required_approving_review_count 1}}]})


(defn- github-side
  "The findings about the GitHub side, with GitHub holding `ruleset` (nil: none) and giving the agent `permission`,
  and gh logged in as `logged-in-as`; {:findings :calls}. :offline makes every call fail."
  [{:keys [ruleset permission logged-in-as offline] :or {permission "write" logged-in-as "kit-agent"}}]
  (let [calls (atom [])
        gh    (fn [& args]
                (swap! calls conj (vec args))
                (when offline
                  (throw (ex-info "error connecting to api.github.com" {})))
                (let [path (second args)]
                  (cond
                    (str/includes? path "/rulesets?") (json/generate-string (if ruleset
                                                                              [{:id 7 :name "main"}]
                                                                              []))
                    (str/includes? path "/rulesets/") (json/generate-string ruleset)
                    :else                             (str permission "\n"))))
        found (with-redefs-fn {#'base/gh!           gh
                               #'base/origin-repo   (constantly nil)
                               #'ident/gh-login     (constantly logged-in-as)
                               #'ident/gh-config-dir-in-use (constantly "gh")
                               #'checks/shell-token (constantly nil)}
                #(checks/github-problems agent-config))]
    {:findings found :calls @calls}))


(defn- says
  "The findings as the lines doctor prints them."
  [{:keys [findings]}]
  (mapv #(str (str/upper-case (name (:level %))) " " (:message %) ". Fix: " (:fix %)) findings))


(deftest an-agent-on-a-repository-with-the-ruleset-and-write-has-nothing-to-fix
  (let [found (github-side {:ruleset good-ruleset})]
    (is (= [] (:findings found)))
    (is (some #(str/includes? (second %) "/rulesets") (:calls found)) "the ruleset was read")))


(deftest the-github-side-names-what-lets-the-agent-merge-alone-and-the-fix
  (doseq [[what held expected]
          [["a bypass for the agent" {:ruleset (assoc good-ruleset :current_user_can_bypass "always")}
            "can bypass the main ruleset (always)"]
           ["a ruleset without the review rule" {:ruleset (assoc good-ruleset :rules [])}
            "requires no approving review"]
           ["a repository without the ruleset" {:ruleset nil} "has no main ruleset"]
           ["an agent without Write" {:ruleset good-ruleset :permission "read"} "permission is read, not write"]]]
    (let [lines (says (github-side held))]
      (is (= 1 (count lines)) what)
      (is (and (str/starts-with? (first lines) "FAIL ") (str/includes? (first lines) expected)) (str what ": " lines))
      (is (str/ends-with? (first lines) "kit github") (str what ": the fix is kit github")))))


(deftest offline-the-github-side-is-a-warning-and-never-a-failure
  (let [lines (says (github-side {:offline true}))]
    (is (= 1 (count lines)))
    (is (str/starts-with? (first lines) "WARN "))
    (is (str/includes? (first lines) "could not be read"))))


(deftest the-github-side-is-judged-only-as-the-agent-and-not-in-ci
  (let [as-owner (github-side {:ruleset good-ruleset :logged-in-as "kit-owner"})]
    (is (empty? (:findings as-owner)))
    (is (empty? (:calls as-owner)) "as anyone but the agent, GitHub is not asked"))
  (let [asked (atom 0)
        dir   (initialised-project)]
    (fake/with-project dir
                       (with-redefs [checks/github-problems (fn [_] (swap! asked inc) [])]
                         (let [cfg (config/read-config)]
                           (doall (checks/problems cfg {:ci true}))
                           (is (zero? @asked) "doctor --ci skips it")
                           (doall (checks/problems cfg {}))
                           (is (= 1 @asked) "doctor without --ci asks"))))))


(deftest doctor-ci-fails-a-wrong-user-test-naming-the-key-and-passes-a-declared-one
  (let [doctor-ci (fn [user-test]
                    (let [dir (initialised-project)
                          cfg (edn/read-string (slurp (str (fs/path dir "kit.edn"))))]
                      (spit (str (fs/path dir "kit.edn")) (pr-str (assoc cfg :user-test user-test)))
                      (fake/with-project dir (fake/captured #(commands/doctor {:ci true})))))]
    (let [{:keys [result out]} (doctor-ci {:artifacts "out"})]
      (is (= 1 result))
      (is (str/includes? out "FAIL  kit.edn: :user-test must be nil")))
    (let [{:keys [result out]} (doctor-ci {:run "true" :artifacts "../elsewhere"})]
      (is (= 1 result))
      (is (str/includes? out "inside the project")))
    (is (= 0 (:result (doctor-ci {:run "mkdir -p out && echo visitor-ok" :artifacts "out"}))))))
