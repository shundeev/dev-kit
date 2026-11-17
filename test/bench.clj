(ns bench
  "bb bench [--runs N] [--base REF]: the hooks that run on every call of a session, timed against the base's
  (origin/main by default). ADR-0005's method: N runs each (50), averaged. Old and new runs alternate, so drift
  over the run hits both alike; timed in a fixed order, drift once showed as a difference that was not there (#140)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]))


(def cases
  "What each hook gets, as Claude Code hands it over: a command the guard leaves at its fast exit, one it reads in
  full, and a file edit the gate judges."
  [{:name   "guard, fast exit"
    :script "claude/hooks/guard.clj"
    :args   []
    :input  {:tool_name "Bash" :tool_input {:command "ls -la"}}}
   {:name   "guard, full read"
    :script "claude/hooks/guard.clj"
    :args   []
    :input  {:tool_name "Bash" :tool_input {:command "gh issue list --limit 1"}}}
   {:name   "kit gate --hook"
    :script "bin/kit"
    :args   ["gate" "--hook"]
    :input  {:tool_name  "Edit"
             :tool_input {:file_path (str (fs/absolutize "README.md")) :old_string "a" :new_string "b"}}}])


(defn- millis
  "Wall time of one run of the hook, in ms."
  [kit {:keys [script args input]}]
  (let [t (System/nanoTime)]
    @(apply p/process
            {:in  (json/generate-string input)
             :out :string
             :err :string
             :env (assoc (into {} (System/getenv)) "SHELL" "/bin/zsh")}
            "bb"
            (str (fs/path kit script))
            args)
    (/ (- (System/nanoTime) t) 1e6)))


(defn- stats
  [xs]
  (let [n    (count xs)
        mean (/ (reduce + xs) n)
        mid  (nth (sort xs) (quot n 2))
        sd   (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) xs)) (dec n)))]
    (format "%5.1f ms (median %5.1f, sd %4.1f)" (double mean) (double mid) sd)))


(defn- base-copy!
  "The base's code, extracted from git into a temporary folder."
  [base-ref]
  (let [tmp     (str (fs/create-temp-dir {:prefix "kit-bench-"}))
        archive (str (fs/path tmp "code.tar"))]
    (p/shell "git" "fetch" "-q" "origin")
    (p/shell "git" "archive" "--format=tar" "-o" archive base-ref)
    (p/shell {:dir tmp} "tar" "-xf" archive)
    tmp))


(defn -main
  [& args]
  (when (odd? (count args))
    (println "usage: bb bench [--runs N] [--base REF]")
    (System/exit 2))
  (let [opts     (apply hash-map args)
        runs     (parse-long (get opts "--runs" "50"))
        base-ref (get opts "--base" "origin/main")
        base     (base-copy! base-ref)]
    (try
      (println (str "bb bench: " runs " runs each, " base-ref " against this checkout, interleaved"))
      (doseq [c cases]
        (millis base c) ; warm-up
        (millis "." c)
        (let [pairs (for [i (range runs)]
                      (if (even? i)
                        [(millis base c) (millis "." c)]
                        (reverse [(millis "." c) (millis base c)])))]
          (println (format "  %-18s %s: %s   this: %s"
                           (:name c)
                           base-ref
                           (stats (map first pairs))
                           (stats (map second pairs))))))
      (finally (fs/delete-tree base)))))
