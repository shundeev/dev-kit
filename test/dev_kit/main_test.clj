(ns dev-kit.main-test
  "kit's entry and the commands it runs without a story of their own elsewhere (step 4 of #139): what a usage error
  says and exits with, kit hook's verdicts, kit sync's refusals and kit user-test's passthrough code. Folders are
  real git folders in a temporary directory."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.base :as base]
   [dev-kit.fake-project :as fake]
   [dev-kit.main :as main]))


(deftest a-usage-error-says-what-to-do-on-stderr-and-exits-1
  (testing "an unknown option"
    (let [{:keys [result out err]} (fake/captured #(main/run ["github" "--bogus"]))]
      (is (= 1 result))
      (is (= "" out))
      (is (str/starts-with? err "unknown option --bogus. Fix: "))))
  (testing "an unknown command, after the usage"
    (let [{:keys [result out err]} (fake/captured #(main/run ["frobnicate"]))]
      (is (= 1 result))
      (is (str/includes? out "kit doctor"))
      (is (str/starts-with? err "unknown command frobnicate. Fix: "))))
  (testing "kit hook with no command"
    (let [{:keys [result err]} (fake/captured #(main/run ["hook"]))]
      (is (= 1 result))
      (is (str/includes? err "kit hook '<command>'"))))
  (testing "kit retro with no issue"
    (let [{:keys [result out err]} (fake/captured #(main/run ["retro"]))]
      (is (= 1 result))
      (is (= "" out))
      (is (= "retro: name the issue. Fix: e.g. kit retro 55, or kit retro --missing\n" err))))
  (testing "kit gate with no pull request"
    (let [{:keys [result out err]} (fake/captured #(main/run ["gate" "--repo" "u473t8/fake" "--branch" "26-x"]))]
      (is (= 1 result))
      (is (= "" out))
      (is (str/starts-with? err "gate: pass --pr. Fix: name the pull request")))))


(deftest a-refusal-of-a-command-with-its-own-code-prints-the-same-form-on-stderr
  (let [dir (fake/new-project)
        {:keys [result out err]} (fake/with-project dir (fake/captured #(main/run ["user-test"])))]
    (is (= 1 result))
    (is (= "" out))
    (is (str/starts-with? err "no kit.edn. Fix: "))))


(defn- entry-hook
  "A project whose entry hook is the given shell script."
  [script]
  (let [dir (fake/new-project)
        f   (fs/path dir ".claude" "hooks" "pretooluse.sh")]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str "cat >/dev/null\n" script "\n"))
    dir))


(deftest kit-hook-prints-the-verdict-and-returns-its-code
  (doseq [[what script code said]
          [["allows" "exit 0" 0 "allowed"]
           ["refuses by deny"
            "echo '{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"no way\"}}'"
            2 "refused: no way"]
           ["refuses by exit" "echo nope >&2; exit 2" 2 "refused: nope"]
           ["asks"
            "echo '{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\",\"permissionDecisionReason\":\"sure?\"}}'" 3
            "asks: sure?"]]]
    (let [dir (entry-hook script)
          {:keys [result out]} (fake/with-project dir (fake/captured #(main/run ["hook" "ls"])))]
      (is (= code result) what)
      (is (= (str said "\n") out) what))))


(deftest kit-hook-without-the-entry-hook-says-to-run-init
  (let [dir     (fake/new-project)
        problem (fake/with-project dir (fake/thrown #(main/hook! ["ls"])))]
    (is (= :hook/no-entry (:problem problem)))
    (is (str/includes? (:fix problem) "bin/kit init"))))


(deftest kit-sync-on-the-wrong-branch-says-which-branch-to-switch-to
  (let [dir     (fake/new-project)
        _ (fake/git dir "switch" "-q" "-c" "work")
        problem (fake/with-project dir (fake/thrown main/sync!))]
    (is (= :sync/wrong-branch (:problem problem)))
    (is (= "sync: the checkout is on work, not on main" (:message problem)))
    (is (= "switch to main first (git switch main)" (:fix problem)))))


(deftest kit-sync-with-uncommitted-changes-says-to-commit-or-stash
  (let [dir     (fake/new-project)
        _ (spit (str (fs/path dir "README.md")) "edited\n")
        problem (fake/with-project dir (fake/thrown main/sync!))]
    (is (= :sync/dirty (:problem problem)))
    (is (str/includes? (:message problem) "README.md"))
    (is (= "commit or stash them first" (:fix problem)))))


(deftest kit-sync-that-cannot-fast-forward-leaves-the-checkout-as-it-was
  (let [origin  (fake/new-folder)
        clone   (str (fs/real-path (fs/create-temp-dir {:prefix "kit-story-clone-"})))
        _ (fake/git origin "config" "receive.denyCurrentBranch" "ignore")
        _ (fake/git clone "clone" "-q" origin ".")
        _ (fake/git clone "config" "user.email" "story@example.com")
        _ (fake/git clone "config" "user.name" "Story")
        _ (spit (str (fs/path origin "other.md")) "on origin\n")
        _ (fake/git origin "add" ".")
        _ (fake/git origin "commit" "-q" "-m" "origin moves")
        _ (spit (str (fs/path clone "mine.md")) "mine\n")
        _ (fake/git clone "add" ".")
        _ (fake/git clone "commit" "-q" "-m" "local commit")
        before  (fake/git clone "rev-parse" "HEAD")
        problem (fake/with-project clone (fake/thrown main/sync!))]
    (is (= :sync/cannot-fast-forward (:problem problem)))
    (is (= "look at them with git log origin/main..HEAD" (:fix problem)))
    (is (= before (fake/git clone "rev-parse" "HEAD")))))


(deftest kit-user-test-returns-the-code-of-the-projects-own-test
  (let [dir (fake/new-project)
        _ (spit (str (fs/path dir "kit.edn"))
                (pr-str {:repo "shundeev/site" :showcase "Site" :modules #{} :user-test {:run "exit 3"}}))
        {:keys [result out]} (fake/with-project dir (fake/captured #(main/run ["user-test"])))]
    (is (= 3 result))
    (is (str/includes? out "user-level test: exit 3"))))


(deftest kit-doctor-in-a-folder-that-is-not-the-projects-root-says-where-to-run-it
  (let [dir (fake/new-project)
        sub (str (fs/create-dirs (fs/path dir "sub")))
        {:keys [result err]} (fake/with-project sub (fake/captured #(main/run ["doctor"])))]
    (is (= 1 result))
    (is (str/includes? err (str "dev-kit must be mounted at <project>/" base/mount)))))


;; The user-level test commands (the selftest's cases of #27, #139 step 8)


(defn- project-with
  "A project that declares `user-test` in its kit.edn."
  [user-test]
  (let [dir (fake/new-project)]
    (spit (str (fs/path dir "kit.edn"))
          (pr-str (cond-> {:repo "shundeev/site" :showcase "Site" :modules #{}}
                    user-test (assoc :user-test user-test))))
    dir))


(deftest modules-tells-ci-whether-and-where-the-user-level-test-runs
  (doseq [[user-test expected] [[nil ["user_test=false" "user_test_artifacts="]]
                                [{:run "true" :artifacts "out"} ["user_test=true" "user_test_artifacts=out"]]
                                [{:run "true"} ["user_test=true" "user_test_artifacts="]]]]
    (let [{:keys [result out]} (fake/with-project (project-with user-test) (fake/captured #(main/run ["modules"])))
          lines (set (str/split-lines out))]
      (is (zero? result))
      (is (every? lines expected) (pr-str user-test)))))


(deftest a-project-without-a-user-test-is-told-what-to-add-and-an-invalid-one-is-refused
  (let [none (fake/with-project (project-with nil) (fake/captured #(main/run ["user-test"])))]
    (is (= 1 (:result none)))
    (is (str/includes? (:err none) ":user-test {:run")))
  (let [bad (fake/with-project (project-with {:artifacts "out"}) (fake/captured #(main/run ["user-test"])))]
    (is (= 1 (:result bad)))
    (is (str/includes? (:err bad) ":user-test") "refused with the same reason doctor gives")))


(deftest the-user-level-test-runs-from-the-projects-root
  (let [dir (project-with {:run "mkdir -p out && echo seen > out/shot.txt"})
        {:keys [result]} (fake/with-project dir (fake/captured #(main/run ["user-test"])))]
    (is (zero? result))
    (is (= "seen\n" (slurp (str (fs/path dir "out" "shot.txt")))))))
