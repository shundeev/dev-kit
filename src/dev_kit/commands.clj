(ns dev-kit.commands
  "kit doctor, init and identity: the commands that print a project's setup and repair it."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.checks :as checks]
   [dev-kit.config :as config]
   [dev-kit.identity :as ident]
   [dev-kit.init :as init]
   [dev-kit.problem :as problem]))


(defn print-findings
  "Prints each finding as one line: its level, what is wrong and what to do."
  [findings quiet]
  (doseq [{:keys [level message fix]} findings]
    (println (format "  %-4s  %s  →  %s" (if (= level :fail) "FAIL" "WARN") message fix)))
  (when (and (empty? findings) (not quiet))
    (println "  ok    everything in place")))


(defn failed?
  "Whether any finding is a failure, not only a warning."
  [findings]
  (boolean (some #(= :fail (:level %)) findings)))


(defn doctor
  "Prints what is wrong with the project's setup, or, with --edn, only the findings as EDN for the program that reads
  them. Returns the exit code: 1 when something fails, 0 otherwise."
  [{:keys [quiet edn] :as opts}]
  (let [findings (checks/problems (config/read-config) opts)]
    (cond
      edn (prn (vec findings))

      (or (seq findings) (not quiet))
      (do (when-not quiet
            (println "dev-kit doctor" (str "(" base/root ")")))
          (print-findings findings quiet)))
    (if (failed? findings) 1 0)))


(defn init-local
  "Per-machine items only (settings.local.json, local git config), never a committed file:
  the SessionStart hook runs it, so a fresh clone or worktree sets itself up."
  []
  (when-let [cfg (config/read-config)]
    (when (empty? (config/config-problems cfg)) ; doctor names them; the session start does not repeat it
      (doseq [c (keep init/fix! (filter :local (ident/managed-items cfg)))]
        (println "dev-kit init --local: wrote" c)))))


(defn refuse
  "Prints why init writes nothing, one line for each of the problems ({:message :fix}). Returns the exit code, 1."
  [problems]
  (println "dev-kit init" (str "(" base/root ")"))
  (doseq [{:keys [message fix]} problems] (println (str "  FAIL  " message "  →  " fix)))
  (println "  nothing was written")
  1)


(defn- bring-in-place
  "Writes what init writes, item by item, and prints what it did. Returns the exit code."
  [opts new cfg items]
  (when new
    (init/create-config! new))
  (let [stale   (ident/stale-paths items)
        _ (run! #(fs/delete-if-exists (base/rel %)) stale)
        results (doall (for [item items]
                         (try {:wrote (init/fix! item)}
                              (catch clojure.lang.ExceptionInfo e
                                {:failed  (checks/label item)
                                 :finding (base/finding :fail
                                                        (str (checks/label item) ": " (ex-message e))
                                                        (or (:fix (ex-data e))
                                                            "fix it by hand, then run kit init again"))}))))
        failed  (set (keep :failed results))
        changes (concat
                 (when (:claude-settings new)
                   ["kit.edn :claude-settings (the project's .claude/settings.json, which is generated from now on)"])
                 (init/scaffold! cfg (:scaffold opts))
                 (keep :wrote results)
                 (map #(str % " (removed, no longer managed)") stale))]
    (println "dev-kit init" (str "(" base/root ")"))
    (doseq [c changes] (println "  wrote" c))
    (print-findings (keep :finding results) true)
    (when (and (empty? changes) (empty? failed))
      (println "  nothing to change"))
    (some-> (:agent cfg) ident/offer-login!)
    ;; doctor's view of what is left, without repeating the items that failed above
    (let [left (remove (fn [{:keys [message]}] (some #(str/starts-with? message (str % ": ")) failed))
                       (checks/problems cfg {}))]
      (print-findings left false)
      (if (or (seq failed) (failed? left)) 1 0))))


(defn init
  "Writes nothing until kit.edn is valid and no project file sits where dev-kit writes; then brings every item in
  place on its own, so one that fails leaves the others done (#14). Returns the exit code."
  [opts]
  (let [new (when-not (fs/exists? config/config-file)
              (init/new-config opts))
        cfg (if new
              (config/merge-config (config/read-defaults) new)
              (config/read-config))]
    (or (some-> (seq (config/config-problems cfg)) refuse)
        (let [items (ident/managed-items cfg)]
          (or (some-> (seq (checks/ownership-problems items)) refuse)
              (bring-in-place opts new cfg items))))))


(defn identity!
  "Writes the agent identity into dir's settings.local.json: dev-kit itself, or a repository
  that does not mount dev-kit yet. Uses dir's kit.edn when it has one, else the defaults."
  [{:keys [dir]}]
  (let
    [dir (str (fs/normalize (fs/absolutize (or dir base/root))))
     _ (when-not (and (fs/directory? dir)
                      (= (str (fs/real-path dir)) (base/sh-in dir "git" "rev-parse" "--show-toplevel")))
         (problem/fail! :identity/not-a-root
                        (str dir " is not the root of a git repository")
                        {:usage true :fix "name the folder that holds .git with --dir, or run kit identity there"}))
     cfg (merge (config/read-defaults) (some-> (base/slurp-if (str (fs/path dir "kit.edn"))) edn/read-string))
     agent
     (or
      (:agent cfg)
      (problem/fail!
       :identity/no-agent
       "no :agent in kit.edn or dev-kit's defaults"
       {:usage true
        :fix
        "add :agent {:login \"<the agent's GitHub login>\" :email \"<its noreply email>\" :gh-config-dir \"~/.config/gh-agent\"} to kit.edn"}))
     env (ident/agent-env agent)
     done (cond-> []
            (ident/ignored-problem dir ident/local-settings)
            (conj (do (ident/ignore! dir ident/local-settings) "ignored"))
            (ident/local-env-problem dir env) (conj (do (ident/write-local-env! dir env) "wrote")))]
    (println "dev-kit identity" (str "(" dir ")"))
    (println (if (seq done)
               (str "  " (str/join ", " done) " " ident/local-settings " — restart Claude sessions here to use it")
               "  nothing to change"))
    (ident/offer-login! agent)
    (when-let [problem (ident/agent-login-problem agent)]
      (println (str "  WARN  "
                    problem
                    "  →  in your own terminal: bb "
                    (fs/path base/kit-dir "bin" "kit")
                    " identity --dir "
                    dir
                    " — it logs "
                    (:login agent)
                    " in")))))
