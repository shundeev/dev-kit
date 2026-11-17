(ns dev-kit.flow.shell
  "Running git and gh for the flow commands, reading their output, and failing with a message."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))


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


(defn shell
  "A command whose output goes to ours, as in a terminal; its exit code."
  [& args]
  (:exit @(apply p/process {:inherit true} args)))


(defn gh-out
  "gh's trimmed stdout; nil when it fails."
  [& args]
  (let [{:keys [exit out]} (apply run "gh" args)]
    (when (zero? exit) (str/trim out))))


(defn ok!
  "Ends the run with a failed command's own exit code, as the scripts did under `set -e`."
  [exit]
  (when-not (zero? exit) (System/exit exit)))


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


(defn env [k default] (or (not-empty (System/getenv k)) default))


(defn resolve-repo
  "owner/name: --repo, then the folder's GitHub origin, then GHWF_REPO. The folder comes before the session (#81):
  a session opened in one project may work in another repository's worktree."
  [repo]
  (or
   (not-empty repo)
   (let [url (some-> (run "git" "remote" "get-url" "origin")
                     :out
                     str/trim
                     (str/replace #"/$" "")
                     (str/replace #"\.git$" ""))]
     (second (re-find #"github\.com[:/]([^/]+/[^/]+)$" (or url ""))))
   (not-empty (System/getenv "GHWF_REPO"))
   (do
     (binding [*out* *err*]
       (println
        "Error: no repository: this folder has no GitHub origin and GHWF_REPO is not set; run inside the clone, or pass --repo owner/name"))
     (System/exit 1))))


(defn issue-branch-number [branch] (second (re-matches #"(\d+)-.*" branch)))
