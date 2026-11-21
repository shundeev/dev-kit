(ns dev-kit.main
  "kit's dispatch: the arguments, the commands that run git, the flow and retro; a problem ends here as its message and its exit code (1 when it names none)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.commands :as commands]
   [dev-kit.config :as config]
   [dev-kit.gate :as gate]
   [dev-kit.github :as github]
   [dev-kit.problem :as problem]))


(defn parse-args
  [args]
  (loop [[a & more] args
         opts       {}]
    (case a
      nil           opts
      "--repo"      (recur (rest more) (assoc opts :repo (first more)))
      "--showcase"  (recur (rest more) (assoc opts :showcase (first more)))
      "--modules"   (recur (rest more) (assoc opts :modules (first more)))
      "--scaffold"  (recur more (assoc opts :scaffold true))
      "--ci"        (recur more (assoc opts :ci true))
      "--quiet"     (recur more (assoc opts :quiet true))
      "--dir"       (recur (rest more) (assoc opts :dir (first more)))
      "--local"     (recur more (assoc opts :local true))
      "--branch"    (recur (rest more) (assoc opts :branch (first more)))
      "--pr"        (recur (rest more) (assoc opts :pr (first more)))
      "--g1"        (recur (rest more) (assoc opts :g1 (first more)))
      "--hook"      (recur more (assoc opts :hook true))
      "--shape"     (recur more (assoc opts :shape true))
      "--title"     (recur (rest more) (assoc opts :title (first more)))
      "--body-file" (recur (rest more) (assoc opts :body-file (first more)))
      "--now"       (recur (rest more) (assoc opts :now (first more)))
      "--days"      (recur (rest more) (assoc opts :days (first more)))
      "--messages"  (recur more (assoc opts :messages true))
      "--check"     (recur (rest more) (assoc opts :check (first more)))
      "--yes"       (recur more (assoc opts :yes true))
      "--edn"       (recur more (assoc opts :edn true))
      (problem/fail! :usage/unknown-option
                     (str "unknown option " a)
                     {:usage true :fix "see the commands and their options: kit"}))))


(defn usage
  []
  (println (str/join "\n"
                     (->> (str/split-lines (slurp (str (fs/path base/kit-dir "bin" "kit"))))
                          (drop 1)
                          (take-while #(str/starts-with? % ";;"))
                          (map #(str/replace % #"^;; ?" ""))))))


(defn hook!
  "kit hook '<command>': the project's entry hook's verdict on one Bash command, fed in Claude Code's input shape,
  never run. The recorder writes under XDG_STATE_HOME, a throwaway directory here: a hand check is no friction.
  Prints the verdict and returns its code: 0 allowed, 2 refused, 3 asks."
  [[command]]
  (let [entry (base/rel ".claude" "hooks" "pretooluse.sh")]
    (when (str/blank? command)
      (problem/fail! :hook/no-command
                     "hook: pass the command as one argument"
                     {:usage true :fix "kit hook '<command>', e.g. kit hook 'git reset --hard'"}))
    (when-not (fs/exists? entry)
      (problem/fail! :hook/no-entry
                     (str "hook: " entry " is missing")
                     {:usage true :fix (str "run " base/mount "/bin/kit init to write it")}))
    (let [state (str (fs/create-temp-dir))
          input (json/generate-string {:tool_name  "Bash"
                                       :tool_input {:command command}
                                       :hook_event_name "PreToolUse"
                                       :cwd        (System/getProperty "user.dir")})
          env   (assoc (into {} (System/getenv)) "CLAUDE_PROJECT_DIR" (str base/root) "XDG_STATE_HOME" state)
          {:keys [exit out err]} (try (p/sh {:in input :env env :continue true} "bash" entry)
                                      (finally (fs/delete-tree state)))
          out   (str/trim (str out))
          d     (when (str/starts-with? out "{")
                  (:hookSpecificOutput (json/parse-string out true)))
          why   (some-> (:permissionDecisionReason d) (str/replace #"\s+" " "))
          [code verdict] (cond (= "ask" (:permissionDecision d)) [3 (str "asks: " why)]
                               (= "deny" (:permissionDecision d)) [2 (str "refused: " why)]
                               (zero? exit) [0 "allowed"]
                               :else [2 (str "refused: " (str/replace (str/trim (str err out)) "\n" " "))])]
      (println verdict)
      (flush)
      code)))


(defn git-run
  "Runs git in the project root with its output passed through. Returns the exit code."
  [& args]
  (:exit @(p/process (into ["git" "-C" (str base/root)] args) {:inherit true})))


(defn sync!
  "kit sync: the owner's main checkout after a merge. Fast-forwards the base, sets .shared to the commit it pins and
  runs doctor. Needs the base branch and no change to a tracked file (untracked files are fine). Never pushes,
  resets or discards: when the checkout cannot be brought forward untouched, it stops and says what to do (#129)."
  []
  (let [base-branch (or (:base (config/read-config)) (:base (config/read-defaults)))
        branch      (base/sh "git" "branch" "--show-current")
        dirty       (remove str/blank?
                            (str/split-lines (or (base/sh "git" "diff" "--name-only" "HEAD")
                                                 "")))]
    (when-not (= base-branch branch)
      (problem/fail! :sync/wrong-branch
                     (str "sync: the checkout is on "
                          (if (str/blank? branch) "no branch (detached HEAD)" branch)
                          ", not on "
                          base-branch)
                     {:fix (str "switch to " base-branch " first (git switch " base-branch ")")}))
    (when (seq dirty)
      (problem/fail! :sync/dirty
                     (str "sync: uncommitted changes to tracked files: "
                          (str/join ", " (take 5 dirty))
                          (when (> (count dirty) 5) ", ..."))
                     {:fix "commit or stash them first"}))
    (when-not (zero? (git-run "fetch" "origin"))
      (problem/fail! :sync/fetch-failed
                     "sync: git fetch origin failed"
                     {:fix "check the network and the remote, then run kit sync again"}))
    (when-not (zero? (git-run "merge" "--ff-only" (str "origin/" base-branch)))
      (problem/fail! :sync/cannot-fast-forward
                     (str "sync: the local "
                          base-branch
                          " has commits of its own and cannot fast-forward to origin/"
                          base-branch
                          "; nothing was changed")
                     {:fix (str "look at them with git log origin/" base-branch "..HEAD")}))
    (when-not (zero? (git-run "-c" "protocol.file.allow=always" "submodule" "update" "--init"))
      (problem/fail! :sync/submodule-failed
                     "sync: git submodule update --init failed"
                     {:fix "fix what it printed, then run kit sync again"}))
    (let [{:keys [exit]} @(p/process ["bb" (str (fs/path base/kit-dir "bin" "kit")) "doctor"]
                                     {:inherit true :dir (str base/root)})]
      (when-not (zero? exit)
        (problem/fail! :sync/doctor-failed
                       "sync: kit doctor found problems, above"
                       {:fix "fix them with the commands it names"}))
      (println (str "Up to date: " (base/sh "git" "log" "-1" "--format=%h %s")
                    "; dev-kit "   (base/sh-in base/kit-dir "git" "rev-parse" "--short" "HEAD"))))))


(defn doctor-problem-count
  "How many problems kit doctor --edn found, read from what it printed."
  [out]
  (count (edn/read-string out)))


(defn after-git!
  "kit sync --after-git: run by the post-merge, post-rewrite and post-checkout hooks. When the commit the project pins
  for .shared differs from the one checked out, sets .shared to the pin, runs doctor in a fresh process (the new kit
  judges) and prints one line. Nothing differs: nothing is printed. A local change in .shared is never overwritten.
  A hook after the fact cannot undo the git command, so it reports and never fails it (#135): it returns 0."
  []
  (try
    (let [pin (some-> (base/sh "git" "ls-tree" "HEAD" base/mount) (str/split #"\s+") (nth 2 nil))
          cur (base/sh-in base/kit-dir "git" "rev-parse" "HEAD")]
      (when (and pin cur (not= pin cur))
        (let [abbrev #(subs % 0 (min 7 (count %)))
              dirty  (remove str/blank?
                             (str/split-lines (or (base/sh-in base/kit-dir "git" "status" "--porcelain") "")))
              {:keys [exit out err]} (when-not (seq dirty)
                                       (p/sh {:dir (str base/root) :continue true}
                                             "git"
                                             "-c"        "protocol.file.allow=always"
                                             "submodule" "update"
                                             "--init"    base/mount))]
          (cond
            (seq dirty)
            (println (str "dev-kit: "
                          base/mount
                          " stays at "
                          (abbrev cur)
                          ", not the pinned "
                          (abbrev pin)
                          ": local changes in it ("
                          (str/join ", " (map #(str/trim (subs % 2)) (take 3 dirty)))
                          (when (> (count dirty) 3) ", ...")
                          "). Look: git -C "
                          base/mount
                          " status. Then git submodule update "
                          base/mount
                          "."))

            (not (zero? exit))
            (println (str "dev-kit: "
                          base/mount
                          " could not move to the pinned "
                          (abbrev pin)
                          ": "
                          (str/replace (str/trim (str err out)) "\n" " ")
                          " Fix that, then run kit sync."))

            :else
            (let [res (p/sh {:dir (str base/root) :continue true}
                            "bb"     (str (fs/path base/kit-dir "bin" "kit"))
                            "doctor" "--edn")
                  n   (doctor-problem-count (:out res))]
              (println (str "dev-kit " (abbrev cur)
                            " → " (base/sh-in base/kit-dir "git" "rev-parse" "--short" "HEAD")
                            "; doctor: "
                            (if (zero? n)
                              "ok"
                              (str n " problem" (when (> n 1) "s") ", see kit doctor")))))))))
    (catch Throwable e
      (println (str "dev-kit: could not bring " base/mount " up to date (" (ex-message e) "); run kit sync."))))
  0)


(defn newer-merge!
  "In dev-kit's own repository (its root holds kit-defaults.edn), a branch's older kit code must not run the closeout
  (#137, #132): when origin's base holds other code (bin/, src/ and kit-defaults.edn, compared as a tree, so a change
  in any module counts, #141), `kit merge` runs the base's, extracted whole to a temporary folder, and says so.
  Projects mount the kit at .shared, pinned on main: nothing changes there. Only a kit run from the repository's own
  checkout compares: the extracted copy runs from its temporary folder, so it runs itself instead of extracting again.
  Returns the exit code of the base's run, or nil when this version is the one to run."
  [args]
  (let [top         (base/sh-in (fs/cwd) "git" "rev-parse" "--show-toplevel")
        base-branch (or (second (drop-while #(not= "--base" %) args)) (System/getenv "GHWF_DEFAULT_BASE") "main")
        base-ref    (str "origin/" base-branch)
        code        ["bin" "src" "kit-defaults.edn"]]
    (when (and top
               (= (str (fs/canonicalize base/kit-dir)) (str (fs/canonicalize top)))
               (fs/exists? (fs/path top "kit-defaults.edn"))
               (base/sh-in top "git" "fetch" "-q" "origin" base-branch)
               (= 1 (:exit (apply p/sh {:dir top :continue true} "git" "diff" "--quiet" base-ref "--" code))))
      (let [tmp     (str (fs/create-temp-dir {:prefix "kit-merge-"}))
            archive (str (fs/path tmp "code.tar"))
            present (some-> (apply base/sh-in top "git" "ls-tree" "--name-only" base-ref "--" code) str/split-lines)]
        (try
          (apply base/sh-in top "git" "archive" "--format=tar" "-o" archive base-ref "--" present)
          (base/sh-in tmp "tar" "-xf" archive)
          (println (str "kit merge: running "
                        base-branch
                        "'s version ("
                        (base/sh-in top "git" "rev-parse" "--short" base-ref)
                        "), newer than this branch's"))
          (:exit @(apply p/process {:inherit true} "bb" (str (fs/path tmp "bin" "kit")) "merge" args))
          (finally (fs/delete-tree tmp)))))))


(defn flow!
  "kit start, finish, merge, checks, review and board set: the delivery flow's GitHub steps, in bin/flow.clj (#95).
  The exit code of the command: the base's run of kit merge (newer-merge!), or the flow's own."
  [cmd args]
  (or (when (= "merge" cmd)
        (newer-merge! args))
      (do (load-file (str (fs/path base/kit-dir "bin" "flow.clj")))
          ((resolve 'flow/main) cmd args (str (fs/path base/kit-dir "bin" "kit"))))))


(defn retro!
  "kit retro N … and kit retro --missing …: the code lives in bin/retro.clj, loaded only for this command. The exit
  code of the command, nil when all is well."
  [[n & more]]
  (when-not (and n (or (= n "--missing") (re-matches #"\d+" n)))
    (problem/fail! :retro/no-issue
                   "retro: name the issue"
                   {:usage true :fix "e.g. kit retro 55, or kit retro --missing"}))
  (load-file (str (fs/path base/kit-dir "bin" "retro.clj")))
  (let [opts (parse-args more)
        opts (assoc opts :repo (gate/gate-repo opts (gate/gate-config)) :gh base/gh!)]
    (if (= n "--missing")
      ((resolve 'retro/run-missing) opts)
      ((resolve 'retro/run)
       (assoc opts :issue (parse-long n) :agent (get-in (config/read-defaults) [:agent :login]))))))


(defn report-problem
  "Prints a problem as its message and, when it has one, what to do next. A :plain problem is an answer, not an error:
  stdout, the text as it is, the fix on the next line. A usage error, or any problem marked :stderr, goes to stderr
  as \"kit: ...\"; every other problem to stdout as \"  FAIL  ...\"."
  [{:keys [message fix plain stderr] usage-error :usage}]
  (let [text (cond-> message
               fix (str "  →  " fix))]
    (cond
      plain
      (println (str message
                    (when fix
                      (str "\n" fix))))

      (or usage-error stderr)
      (binding [*out* *err*]
        (println (str "kit: " text))
        (flush))

      :else
      (println (str "  FAIL  " text)))))


(defn run
  "Runs the command the arguments name and returns the exit code: the code the command returns, 0 when it returns
  none, and for a problem thrown on the way, its :exit (1 when it names none) after printing it."
  [argv]
  (let [[cmd & args] argv]
    (try
      (when (and (#{"init" "doctor" "modules" "sync"} cmd)
                 (not= (str base/root) (base/sh "git" "rev-parse" "--show-toplevel")))
        (problem/fail!
         :usage/not-mounted
         (str "dev-kit must be mounted at <project>/" base/mount "; " base/root " is not a git repository root")
         {:usage true :fix (str "run kit from the project's root, where " base/mount " is a submodule")}))
      (let [result (case cmd
                     "init"      (let [opts (parse-args args)]
                                   (if (:local opts)
                                     (commands/init-local)
                                     (commands/init opts)))
                     "doctor"    (commands/doctor (parse-args args))
                     "modules"   (let [cfg (config/read-config)]
                                   (println (str "clojure=" (boolean (some-> cfg config/clojure?))))
                                   (println (str "user_test=" (boolean (get-in cfg [:user-test :run]))))
                                   (println (str "user_test_artifacts="
                                                 (or (get-in cfg [:user-test :artifacts]) ""))))
                     "user-test" (config/user-test!)
                     "identity"  (commands/identity! (parse-args args))
                     "github"    (github/github! (parse-args args))
                     "gate"      (gate/gate! (parse-args args))
                     "retro"     (retro! args)
                     "sync"      (if (some #{"--after-git"} args)
                                   (after-git!)
                                   (sync!))
                     "hook"      (hook! args)
                     ("start" "finish" "release" "merge" "checks" "review" "board") (flow! cmd args)
                     nil         (usage)
                     (do (usage)
                         (problem/fail! :usage/unknown-command
                                        (str "unknown command " cmd)
                                        {:usage true :fix "pick one of the commands listed above"})))]
        (if (integer? result) result 0))
      (catch clojure.lang.ExceptionInfo e
        (if-let [data (ex-data e)]
          (if (:problem data)
            (do (report-problem data) (or (:exit data) 1))
            (throw e))
          (throw e))))))


(defn -main
  "Runs the command the arguments name and ends the process with its exit code: kit's one exit."
  [& argv]
  (System/exit (run argv)))
