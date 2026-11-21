(ns dev-kit.fake-project
  "A project to run kit's core commands in: a real git folder in a temporary directory, with kit's idea of where the
  project is (base/root, kit.edn) pointed at it. What depends on the machine (tools, logins, GitHub) is switched off,
  so a story sees only the project."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.checks :as checks]
   [dev-kit.config :as config]
   [dev-kit.identity :as ident]))


(defn git
  "Runs git in dir; stdout, trimmed. A failing git fails the story."
  [dir & args]
  (let [{:keys [out]} (apply p/shell {:dir (str dir) :out :string :err :string} "git" args)]
    (str/trim out)))


(defn new-folder
  "A git folder on main with one commit."
  []
  (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "kit-story-"})))]
    (git dir "init" "-q" "-b" "main")
    (git dir "config" "user.email" "story@example.com")
    (git dir "config" "user.name" "Story")
    (spit (str (fs/path dir "README.md")) "a project\n")
    (git dir "add" ".")
    (git dir "commit" "-q" "-m" "first")
    dir))


(defn new-project
  "A git folder that declares dev-kit as its submodule, as kit expects: the mount is a repository of its own, holding
  the empty folders the skill links point into. It has no kit.edn yet."
  []
  (let [dir (new-folder)]
    (fs/create-dirs (fs/path dir base/mount))
    (git (fs/path dir base/mount) "init" "-q")
    (doseq [skill (fs/list-dir (fs/path base/kit-dir "claude" "skills"))
            :when (fs/directory? skill)]
      (fs/create-dirs (fs/path dir base/mount "claude" "skills" (fs/file-name skill))))
    (spit (str (fs/path dir ".gitmodules")) (str "[submodule \"kit\"]\n\tpath = " base/mount "\n\turl = ./kit\n"))
    dir))


(defn with-project*
  "Calls f with kit pointed at dir, and nothing of the machine in the way: no tools, GitHub or login checks, and no
  terminal to ask."
  [dir f]
  (with-redefs [base/root (fs/path dir)
                config/config-file (str (fs/path dir "kit.edn"))
                checks/tool-problems (constantly nil)
                checks/claude-version-problems (constantly nil)
                checks/bb-version-problems (constantly nil)
                checks/github-problems (constantly nil)
                ident/identity-problems (constantly nil)
                ident/gh-login (constantly nil)
                ident/interactive? (constantly false)]
    (f)))


(defmacro with-project
  [dir & body]
  `(with-project* ~dir (fn [] ~@body)))


(defn captured
  "Runs f and returns {:result what f returned, :out stdout, :err stderr}."
  [f]
  (let [out    (java.io.StringWriter.)
        err    (java.io.StringWriter.)
        result (binding [*out* out
                         *err* err]
                 (f))]
    {:result result :out (str out) :err (str err)}))


(defn thrown
  "The data of the problem f throws, or nil when it throws none."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
