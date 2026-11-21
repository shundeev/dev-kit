(ns dev-kit.init
  "Writing what kit init generates: the managed files, kit.edn and the scaffold."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.checks :as checks]
   [dev-kit.config :as config]
   [dev-kit.identity :as ident]
   [dev-kit.problem :as problem]
   [rewrite-clj.zip :as z]))


(defn write!
  [path content]
  (fs/create-dirs (fs/parent path))
  (spit (str path) content))


(defn fix!
  "Brings one managed item in place. Returns a description when something changed."
  [{git-key :key :keys [kind path content target value env] :as item}]
  (when-let [problem (checks/check item)]
    (let [f (some-> path base/rel)]
      (case kind
        :file (write! f content)

        :ignored (ident/ignore! base/root path)

        :local-env (ident/write-local-env! base/root env)

        :block
        (let [s (base/slurp-if f)
              {:keys [pairs stray]} (some-> s checks/block-markers)]
          (cond
            (nil? s)       (write! f (str content "\n" (slurp (str (fs/path base/kit-dir "scaffold" "AGENTS.md")))))
            (seq stray)    (problem/fail! :init/stray-markers
                                          problem
                                          {:fix "remove the stray marker or restore its pair, then run kit init again"})
            (empty? pairs) (write! f (str content "\n\n" s))
            :else          (write! f (checks/with-block s content pairs))))

        :symlink
        (if (and (fs/exists? f) (not (fs/sym-link? f)))
          (problem/fail! :init/not-a-link
                         (str path " is a real file where dev-kit wants a link")
                         {:fix "move it away, then run kit init again"})
          (do (fs/delete-if-exists f)
              (fs/create-dirs (fs/parent f))
              (fs/create-sym-link f target)))

        :policy
        (let [m (checks/policy-map f)]
          (cond
            (nil? m) (write! f (config/json-text checks/policy-default))
            (= m ::checks/unreadable) (problem/fail! :init/policy-unreadable
                                                     (str path " " problem)
                                                     {:fix "fix the file by hand, then run kit init again"})
            :else (write! f
                          (config/json-text
                           (reduce (fn [m path] (assoc-in m path (get-in m path (get-in checks/policy-default path))))
                                   m
                                   [["workflow" "worktree_mode"] ["destructive_command_protection" "allow_paths"]])))))

        :kondo
        (let [s     (base/slurp-if f)
              m     (some-> s checks/kondo-config)
              entry (str (ident/kondo-map-text) "\n")]
          (cond
            (nil? s)       (write! f
                                   (str ";; Project settings for clj-kondo. Shared strict linters come from dev-kit.\n"
                                        entry))
            (nil? m)       (write! f (str s (when-not (str/ends-with? s "\n") "\n") entry)) ; empty, or comments only
            (not (map? m)) (problem/fail! :init/kondo-not-a-map
                                          (str path " " problem)
                                          {:fix "make it a map by hand, then run kit init again"})
            :else          (let [zloc (z/of-string s)]
                             (write! f
                                     (z/root-string (if (z/get zloc :config-paths)
                                                      (z/edit (z/get zloc :config-paths) conj (ident/kondo-entry))
                                                      (z/assoc zloc :config-paths [(ident/kondo-entry)]))))))
          (when (checks/check item)
            (problem/fail! :init/kondo-not-updated
                           (str path " could not be updated")
                           {:fix (str "add " (ident/kondo-entry) " to :config-paths by hand")})))

        :git-config (base/sh "git" "config" "--local" git-key value))
      (str (checks/label item) " (" problem ")"))))


(defn new-config
  "The kit.edn a project without one gets, from init's flags. A .claude/settings.json already there becomes
  :claude-settings, which the generated settings merge in: nothing of it is lost."
  [{:keys [repo showcase modules]}]
  (let [settings (ident/read-json-object (base/rel ".claude/settings.json"))
        ;; settings dev-kit wrote run its hook; kit.edn went missing, they are not the project's own
        settings (if (str/includes? (json/generate-string settings) ".claude/hooks/pretooluse.sh") {} settings)]
    (when (= ::ident/invalid settings)
      (problem/fail! :init/settings-not-object
                     ".claude/settings.json is not a JSON object"
                     {:fix "fix it or move it away, then run kit init again"}))
    (cond-> {:repo     (or repo
                           (base/origin-repo base/root)
                           (problem/fail! :init/no-origin
                                          "no origin remote on GitHub"
                                          {:fix "pass --repo owner/name"}))
             :showcase showcase
             :modules  (into #{} (comp (remove str/blank?) (map keyword)) (some-> modules (str/split #",")))}
      (seq settings) (assoc :claude-settings settings))))


(defn create-config!
  [{:keys [repo showcase modules claude-settings]}]
  (write! config/config-file
          (str ";; dev-kit settings for this project, read by "
               base/mount
               "/bin/kit (see "
               base/mount
               "/README.md).\n"
               "{:repo     "
               (pr-str repo)
               "\n"
               " :showcase "
               (pr-str showcase)
               "\n"
               " ;; #{:clojure} adds the shared zprint/clj-kondo setup and Clojure rules\n"
               " :modules  "
               (pr-str modules)
               (when claude-settings
                 (str "\n ;; merged into the generated .claude/settings.json; it was the project's own before dev-kit\n"
                      " :claude-settings "
                      (pr-str claude-settings)))
               "}\n")))


(defn scaffold!
  [cfg all?]
  (let [repo  (:repo cfg)
        vars  {"{{year}}" (str (.getYear (java.time.LocalDate/now)))
               "{{repo}}" repo
               "{{name}}" (last (str/split repo #"/"))
               "{{dir}}"  (last (str/split repo #"/"))}
        files (cond-> ["LICENSE"]
                all? (into ["README.md" "adr/README.md" "docs/experiments/README.md"]))]
    (doall
     (for [f     files
           :when (not (fs/exists? (base/rel f)))]
       (do (write! (base/rel f) (reduce-kv str/replace (slurp (str (fs/path base/kit-dir "scaffold" f))) vars))
           (str f " (created)"))))))
