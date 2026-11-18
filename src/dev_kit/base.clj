(ns dev-kit.base
  "What every part of kit stands on: where dev-kit and the project are, running a command, failing, and gh."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))


(def kit-dir
  "dev-kit's root: above the bin/kit that runs, as it was invoked (a mount keeps its name), or above src/ when no
  bin/kit runs (bb test)."
  (let [entry (System/getProperty "babashka.file")]
    (if (and entry (= "kit" (str (fs/file-name entry))))
      (-> entry fs/absolutize fs/normalize fs/parent fs/parent)
      (-> *file* fs/absolutize fs/normalize fs/parent fs/parent fs/parent))))


(def mount (str (fs/file-name kit-dir)))


(def root (fs/parent kit-dir))


(defn die
  [& msg]
  (binding [*out* *err*] (println (str "kit: " (str/join " " msg))))
  (System/exit 2))


(defn sh-in
  "Runs a command in dir. Returns trimmed stdout, or nil when it fails."
  [dir & args]
  (let [{:keys [exit out]} (apply p/sh {:dir (str dir) :continue true} args)]
    (when (zero? exit)
      (str/trim out))))


(defn sh
  "Runs a command in the project root. Returns trimmed stdout, or nil when it fails."
  [& args]
  (apply sh-in root args))


(defn rel [& parts] (str (apply fs/path root parts)))


(defn slurp-if
  [path]
  (when (fs/regular-file? path)
    (slurp path)))


(defn origin-repo
  "owner/name of the GitHub origin of the git repository at dir, or nil."
  [dir]
  (when-let [url (sh-in dir "git" "remote" "get-url" "origin")]
    (second (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?/?$" url))))


(defn gh-failure
  "The exception for a failed gh call: gh's message, or, when it printed none, the command and its exit code (#97)."
  [args exit text]
  (ex-info (or (not-empty (str/trim text))
               (str "gh " (str/join " " (take 2 args))
                    " failed with exit code " exit
                    " and printed no message; "
                    "run the same call by hand to see why"))
           {}))


(defn gh!
  "Runs gh; stdout, or an exception carrying gh's message."
  [& args]
  (let [{:keys [exit out err]} (apply p/sh {:continue true} "gh" args)]
    (if (zero? exit)
      out
      (throw (gh-failure args exit (str err out))))))
