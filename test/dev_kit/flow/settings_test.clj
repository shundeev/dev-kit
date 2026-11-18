(ns dev-kit.flow.settings-test
  "Where the flow takes a project's board, repository, base, Showcase and reviewers from, told as the owner's stories
  (#139): the kit.edn of the folder it runs in, not the session's environment. Folders are real git folders in a
  temporary directory; the session's environment is a map."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.config :as config]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.start :as start]
   [dev-kit.flow.steps :as steps]))


(defn- git [folder & args] (apply p/shell {:dir (str folder) :out :string :err :string} "git" args))


(defn- folder
  "A git folder; kit-edn, when given, is its kit.edn text."
  [kit-edn]
  (let [dir (str (fs/create-temp-dir {:prefix "settings-story-"}))]
    (git dir "init" "-q" "-b" "main")
    (when kit-edn
      (spit (str (fs/path dir "kit.edn")) kit-edn))
    dir))


(def ^:private dev-kit-edn
  "{:repo \"shundeev/dev-kit\" :showcase \"Process\" :modules #{} :owners [\"shundeev/owners\"]}")


(def ^:private site-session
  "A session opened in the Site project."
  {"GHWF_OWNER"          "site-owner"
   "GHWF_PROJECT_NUMBER" "9"
   "GHWF_REPO"           "site-owner/site"
   "GHWF_DEFAULT_BASE"   "trunk"
   "GHWF_SHOWCASE"       "Site"
   "GHWF_REVIEWERS"      "site-owner/reviewers"})


(defn- lookup-in
  "The flow's (fn [variable default]) for a folder, in a session whose environment is session."
  [dir session]
  (let [cfg (config/read-config-at dir)]
    (fn [variable default] (config/setting cfg session variable default))))


(defn- kit-start
  "kit start run in dir, in a session with that environment, against an empty GitHub; the world afterwards."
  [dir session & args]
  (let [world (fake/new-world {})
        ctx   {:github       (fake/github world)
               :warn         (fn [& _])
               :kit          "bin/kit"
               :resolve-repo (fn [explicit] (or explicit "o/r"))}
        input (start/read-input (vec args) (lookup-in dir session))
        done  (start/start ctx input)]
    {:world world :result done}))


(deftest a-dev-kit-issue-filed-from-another-projects-session-gets-dev-kits-showcase
  (let [dir (folder dev-kit-edn)
        {:keys [world result]} (kit-start dir site-session "--title" "Fix the guard" "--priority" "High")]
    (is (= :ok (::steps/status result)) "the issue is filed")
    (is (= ["Process"] (map #(get-in % [:fields "Showcase"]) (fake/cards world)))
        "its card has dev-kit's Showcase, not the one of the project the session was opened in")))


(deftest kit-edn-alone-decides-the-board-the-base-and-the-reviewers
  (let
    [dir
     (folder
      "{:repo \"me/app\" :showcase \"App\" :base \"develop\" :board {:owner \"me\" :number 5}
                          :owners [\"me/team\" \"you\"]}")
     lookup (lookup-in dir {})]
    (is (= ["me" "5" "develop" "App" "me/team,you" "me/app"]
           (map #(lookup % nil)
                ["GHWF_OWNER" "GHWF_PROJECT_NUMBER" "GHWF_DEFAULT_BASE" "GHWF_SHOWCASE" "GHWF_REVIEWERS" "GHWF_REPO"]))
        "owner, number, base, Showcase, reviewers and repository come from the file")
    (is (= "5" (:project-number (start/read-input ["--title" "x"] lookup))) "kit start takes the board from it")))


(deftest a-board-missing-from-kit-edn-is-the-default-board
  (let [lookup (lookup-in (folder dev-kit-edn) {})]
    (is (= ["u473t8" "3" "main"] (map #(lookup % nil) ["GHWF_OWNER" "GHWF_PROJECT_NUMBER" "GHWF_DEFAULT_BASE"])))))


(deftest a-flag-beats-kit-edn
  (let [dir   (folder dev-kit-edn)
        input (start/read-input ["--title" "x" "--showcase" "Site" "--base" "next" "--owner" "someone"]
                                (lookup-in dir {}))]
    (is (= ["Site" "next" "someone"] ((juxt :showcase :base :owner) input)))))


(deftest a-folder-without-kit-edn-takes-the-environment
  (let [dir    (folder nil)
        lookup (lookup-in dir site-session)]
    (is (nil? (config/read-config-at dir)) "kit has no settings for this folder")
    (is (= ["site-owner" "9" "trunk" "Site" "site-owner/reviewers"]
           (map #(lookup % nil)
                ["GHWF_OWNER" "GHWF_PROJECT_NUMBER" "GHWF_DEFAULT_BASE" "GHWF_SHOWCASE" "GHWF_REVIEWERS"]))
        "the session's variables fill in")))


(deftest a-folder-without-kit-edn-and-without-variables-gets-no-board-and-is-told-what-to-do
  (let [dir {:folder (folder nil)}
        {:keys [result]} (kit-start (:folder dir) {} "--title" "x" "--priority" "High")]
    (is (= :start/no-owner (get-in result [::steps/problem :problem])))
    (is (str/includes? (get-in result [::steps/problem :message]) "kit.edn")
        "the refusal says what is missing and to add kit.edn (kit init) or pass the flag")))


(deftest an-unreadable-kit-edn-names-the-file-and-the-line
  (let [dir (folder "{:repo \"a/b\"\n :x ]\n}\n")
        why (try (config/read-config-at dir) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (str/includes? (str why) (str (fs/path (fs/canonicalize dir) "kit.edn") " line 2")))
    (is (str/includes? (str why) "fix it by hand"))))


(deftest a-session-in-one-project-working-in-anothers-worktree-takes-the-others-settings
  (let [project-b (folder "{:repo \"me/b\" :showcase \"B\" :board {:owner \"me\" :number 2}}")
        _ (do (git project-b "add" "kit.edn")
              (git project-b "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "init"))
        worktree  (str (fs/path (fs/create-temp-dir {:prefix "settings-story-wt-"}) "wt"))
        _ (git project-b "worktree" "add" "-q" worktree)
        lookup    (lookup-in worktree site-session)]
    (is (= ["me" "2" "B" "me/b"] (map #(lookup % nil) ["GHWF_OWNER" "GHWF_PROJECT_NUMBER" "GHWF_SHOWCASE" "GHWF_REPO"]))
        "the board, Showcase and repository are project B's, whatever the session's project is")))


(deftest the-settings-kit-init-writes-carry-no-ghwf-variables-and-keep-the-projects-own
  (let [text     (config/settings-text (assoc (config/merge-config (config/read-defaults)
                                                                   {:repo "me/app" :showcase "App"})
                                              :claude-settings
                                              {:env {"MY_OWN" "1"}}))
        settings (json/parse-string text)]
    (is (= {"MY_OWN" "1"} (get settings "env")) "only the project's own variable is there")
    (is (not (str/includes? text "GHWF_")))))
