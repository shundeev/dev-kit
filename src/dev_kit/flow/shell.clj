(ns dev-kit.flow.shell
  "Running git and gh for the flow commands, reading their output, and failing with a message."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [dev-kit.config :as config]))


(defn die
  [& msg]
  (binding [*out* *err*] (println (str "Error: " (str/join " " msg))))
  (System/exit 1))


(defn warn
  [& msg]
  (binding [*out* *err*] (println (str/join " " msg))))


(defn run
  "A command's {:exit :out :err}, its output captured."
  [& args]
  (apply p/sh {:continue true} args))


(defn git-out
  "git's trimmed stdout; a failure gives what git printed on stdout, often nothing."
  [& args]
  (some-> (apply run "git" args) :out str/trim))


(defn shell
  "A command whose output goes to ours, as in a terminal; its exit code."
  [& args]
  (:exit @(apply p/process {:inherit true} args)))


(defn gh-out
  "gh's trimmed stdout; nil when it fails."
  [& args]
  (let [{:keys [exit out]} (apply run "gh" args)]
    (when (zero? exit)
      (str/trim out))))


(defn exit-with!
  "Ends the run as a stopped recipe says: its text on stderr, its exit code."
  [{:keys [text exit]}]
  (binding [*out* *err*] (println text))
  (System/exit exit))


(defn ok!
  "Ends the run with a failed command's own exit code, as the scripts did under `set -e`."
  [exit]
  (when-not (zero? exit)
    (System/exit exit)))


(defn gh!
  "gh's trimmed stdout, as `$(gh …)` read it: its messages go to our stderr, and a failure ends the run with gh's exit
  code. A failure that says nothing is named here: the command and its exit code."
  [& args]
  (let [{:keys [exit out err]} @(apply p/process {:out :string :err :string} "gh" args)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (print err)
        (flush)))
    (when (and (not (zero? exit)) (str/blank? err))
      (warn (str "Error: gh " (str/join " " (take 2 args))
                 " failed with exit code " exit
                 " and printed no message. "
                 "Run the same call by hand to see why.")))
    (ok! exit)
    (str/trim out)))


(defn file-text
  "A file as `$(cat file)` reads it: without its trailing newlines."
  [path]
  (str/replace (slurp path) #"\n+$" ""))


(defn last-line [s] (some-> s str/split-lines last (str/replace "\r" "") str/trim))


(defn shell-quote
  "A word as bash's printf %q would write it."
  [s]
  (if (re-matches #"[A-Za-z0-9_@%+=:,./-]+" s)
    s
    (str "'" (str/replace s "'" "'\\''") "'")))


(defn parse-opts
  "Options against a spec: {\"--flag\" [:key :value|:flag|:many]}. Unknown ones end the run."
  [spec args]
  (loop [[a & more] args
         opts       {}]
    (if (nil? a)
      opts
      (let [[k kind] (spec a)]
        (case kind
          :value (recur (rest more) (assoc opts k (first more)))
          :many  (recur (rest more) (update opts k (fnil conj []) (first more)))
          ;; --help answers at once, as the scripts' `case` did, whatever follows it.
          :flag  (if (= k :help)
                   {:help true}
                   (recur more (assoc opts k true)))
          (die "Unknown argument:" a))))))


(def ^:private folder-config
  "The kit.edn of the folder the flow runs in over the defaults, read once; nil when the folder has none."
  (delay (config/read-config-at (System/getProperty "user.dir"))))


(def ^:private warned-no-kit-edn (atom false))


(defn env
  "A setting of the flow: the folder's kit.edn, then the environment, then the default. A flag, which comes first,
  is the caller's. The session's own variables come after the folder, so a session opened in one project does not
  decide for another's folder (dev-kit#139). Without a kit.edn and a board in the environment it warns, once."
  [k default]
  (let [v (config/setting @folder-config #(System/getenv %) k default)]
    (when (and (nil? v)
               (#{"GHWF_OWNER" "GHWF_PROJECT_NUMBER"} k)
               (not @folder-config)
               (compare-and-set! warned-no-kit-edn false true))
      (warn
       "Warning: this folder has no kit.edn, and"
       k
       "is not set: add a kit.edn (run kit init, or write {:repo \"owner/name\" :showcase \"...\"}), or pass the flags."))
    v))


(defn resolve-repo
  "owner/name: --repo, then the folder's GitHub origin, then the folder's kit.edn :repo, then GHWF_REPO (one
  lookup, `env`). The folder comes before the session (#81): a session opened in one project may work in another repository's worktree."
  [repo]
  (or
   (not-empty repo)
   (let [url (some-> (run "git" "remote" "get-url" "origin")
                     :out
                     str/trim
                     (str/replace #"/$" "")
                     (str/replace #"\.git$" ""))]
     (second (re-find #"github\.com[:/]([^/]+/[^/]+)$" (or url ""))))
   (env "GHWF_REPO" nil)
   (do
     (binding [*out* *err*]
       (println
        "Error: no repository: this folder has no GitHub origin, its kit.edn has no :repo and GHWF_REPO is not set; run inside the clone, or pass --repo owner/name"))
     (System/exit 1))))


(defn issue-branch-number [branch] (second (re-matches #"(\d+)-.*" branch)))
