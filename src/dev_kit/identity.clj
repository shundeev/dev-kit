(ns dev-kit.identity
  "The agent's own GitHub account per machine (#18): local settings, the gh login, the managed items it needs."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.config :as config]
   [dev-kit.problem :as problem]))


;; The agent works under its own GitHub account (kit.edn :agent), so the main ruleset's required
;; review is a review by the owner. Claude Code applies settings env to the session, its hooks
;; and every command it runs; the owner's own terminal is untouched.


(def local-settings ".claude/settings.local.json")


(defn expand-home
  "~ as the shell and gh read it: from HOME, not from the JVM's user.home."
  [path]
  (str/replace-first (str path)
                     #"^~(?=/|$)"
                     (str/re-quote-replacement (or (not-empty (System/getenv "HOME"))
                                                   (System/getProperty "user.home")))))


(defn agent-env
  [{:keys [login email gh-config-dir]}]
  {"GH_CONFIG_DIR"       (expand-home gh-config-dir)
   ;; git takes config from the environment too: SSH remotes go over HTTPS, and the github.com
   ;; credential helpers are reset to gh alone, which answers with GH_CONFIG_DIR's login.
   "GIT_CONFIG_COUNT"    "3"
   "GIT_CONFIG_KEY_0"    "url.https://github.com/.insteadOf"
   "GIT_CONFIG_VALUE_0"  "git@github.com:"
   "GIT_CONFIG_KEY_1"    "credential.https://github.com.helper"
   "GIT_CONFIG_VALUE_1"  ""
   "GIT_CONFIG_KEY_2"    "credential.https://github.com.helper"
   "GIT_CONFIG_VALUE_2"  "!gh auth git-credential"
   "GIT_AUTHOR_NAME"     login
   "GIT_AUTHOR_EMAIL"    email
   "GIT_COMMITTER_NAME"  login
   "GIT_COMMITTER_EMAIL" email})


(defn read-json-object
  "The JSON object in file f: {} when the file is missing, ::invalid when it holds no object."
  [f]
  (if-let [s (base/slurp-if f)]
    (let [m (try (json/parse-string s) (catch Exception _ nil))]
      (if (map? m) m ::invalid))
    {}))


(declare gh-login)


(defn human-login
  "The person behind the agent on this machine: their own gh login (the default gh config, never
  GH_CONFIG_DIR, which points at the agent). Their PRs are assigned to them (#52)."
  []
  (gh-login (or (some-> (not-empty (System/getenv "XDG_CONFIG_HOME")) (fs/path "gh") str)
                (expand-home "~/.config/gh"))))


(defn local-env-problem
  [dir env]
  (let [f (str (fs/path dir local-settings))
        m (read-json-object f)]
    (cond (not (fs/exists? f)) "missing"
          (= ::invalid m) "is not a JSON object"
          (not= env (select-keys (get m "env") (keys env))) "env differs from the agent identity in kit.edn"
          (and (human-login) (str/blank? (get-in m ["env" "GHWF_HUMAN"])))
          "lacks GHWF_HUMAN, the person behind the agent")))


(defn write-local-env!
  "Merges env into the file's env; every other key, the owner's own, stays."
  [dir env]
  (let [f (str (fs/path dir local-settings))
        m (read-json-object f)]
    (when (= ::invalid m)
      (problem/fail! :identity/settings-not-object
                     (str local-settings " is not a JSON object")
                     {:fix "fix or remove it, then run again"}))
    (fs/create-dirs (fs/parent f))
    (spit f
          (config/json-text (update m
                                    "env"
                                    #(into (sorted-map)
                                           (merge %
                                                  env
                                                  (when-let [h (human-login)]
                                                    (when (str/blank? (get % "GHWF_HUMAN"))
                                                      {"GHWF_HUMAN" h})))))))))


(defn ignored-problem
  [dir path]
  (when-not (base/sh-in dir "git" "check-ignore" "-q" path) "is not git-ignored, so it could be committed"))


(defn ignore!
  "Adds path to the repository's info/exclude: per clone, shared by its worktrees."
  [dir path]
  (let [ex (or (base/sh-in dir "git" "rev-parse" "--path-format=absolute" "--git-path" "info/exclude")
               (problem/fail! :identity/not-a-repository
                              (str dir " is not a git repository")
                              {:fix "run it inside a git repository, or name one with --dir"}))
        s  (base/slurp-if ex)]
    (fs/create-dirs (fs/parent ex))
    (spit ex
          (str (when (and s (not (str/blank? s)) (not (str/ends-with? s "\n"))) "\n") "/" path "\n")
          :append
          true)))


(defn gh-config-dir-in-use
  "Where gh in this process reads its login from."
  []
  (or (not-empty (System/getenv "GH_CONFIG_DIR"))
      (some-> (not-empty (System/getenv "XDG_CONFIG_HOME")) (fs/path "gh") str)
      (expand-home "~/.config/gh")))


(defn gh-login
  "The github.com login stored in a gh config dir, read offline; nil when logged out."
  [dir]
  (some-> (base/slurp-if (str (fs/path dir "hosts.yml")))
          (yaml/parse-string :keywords false)
          (get-in ["github.com" "user"])))


(defn same-dir? [a b] (= (str (fs/normalize (fs/absolutize a))) (str (fs/normalize (fs/absolutize b)))))


(defn agent-login-problem
  "nil when the agent's gh config holds the agent's login."
  [{:keys [login gh-config-dir]}]
  (let [dir  (expand-home gh-config-dir)
        user (gh-login dir)]
    (cond (nil? user)       (str "the agent's gh config " dir " is not logged in")
          (not= login user) (str "the agent's gh config " dir " is logged in as " user ", not " login))))


(defn interactive?
  "A person at a terminal: stdin and stdout are a tty, and this is not a Claude session."
  []
  (and (not (System/getenv "CLAUDECODE"))
       (try (boolean (some-> (System/console) (.isTerminal)))
            (catch Exception _ (some? (System/console))))))


(defn login-agent!
  "Logs the agent's account into its gh config with gh's browser flow, attached to this
  terminal. The person finishes it in the browser, signed in as the agent."
  [{:keys [login gh-config-dir]}]
  (let [dir  (expand-home gh-config-dir)
        opts {:continue true :extra-env {"GH_CONFIG_DIR" dir}}]
    (println (str "\nLogging the agent's GitHub account "
                  login
                  " into "
                  dir
                  ".\n"
                  "On the github.com/login/device page check that you are signed in as "
                  login
                  " (switch account, or use a private window), then enter the code gh shows.\n"))
    (when-let [user (gh-login dir)]
      (p/shell opts "gh" "auth" "logout" "--hostname" "github.com" "--user" user))
    (p/shell opts
             "gh"         "auth"
             "login"      "--hostname"
             "github.com" "--git-protocol"
             "https"      "--web"
             "--scopes"   "project,workflow")))


(defn offer-login!
  "Starts the agent's login when it is missing or wrong and a person is at the terminal."
  [agent]
  (when (and (agent-login-problem agent)
             (interactive?)
             (base/sh "bash" "-c" "command -v gh"))
    (login-agent! agent)))


(defn identity-problems
  "Outside a Claude session: a warning when the agent is not logged in on this machine.
  Inside one (CLAUDECODE is set): gh must act as the agent, never as the owner."
  [cfg]
  (when-let [{:keys [login gh-config-dir] :as agent} (:agent cfg)]
    (let [session? (System/getenv "CLAUDECODE")
          want     (expand-home gh-config-dir)
          have     (gh-config-dir-in-use)
          user     (gh-login have)
          token    (first (filter #(not-empty (System/getenv %)) ["GH_TOKEN" "GITHUB_TOKEN"]))]
      (cond
        (and session? token)
        [(base/finding :fail
                       (str token " is set, so gh in this Claude session uses that token, not the agent's account")
                       (str "unset " token " in the shell that starts claude, then restart the session"))]

        (and session? (not (same-dir? want have)))
        [(base/finding :fail
                       (str "this Claude session runs gh from "
                            have
                            (if user
                              (str ", logged in as " user)
                              ", logged out")
                            ", not from the agent's config "
                            want)
                       (if (local-env-problem base/root (agent-env agent))
                         (str base/mount "/bin/kit init, then restart this Claude session")
                         (str "restart this Claude session: " local-settings " is in place and applies at start")))]

        :else
        (when-let [problem (agent-login-problem agent)]
          [(base/finding (if session? :fail :warn)
                         problem
                         (str "in your own terminal: " base/mount "/bin/kit init — it logs " login " in"))])))))


(defn codeowners-text
  [{:keys [owners]}]
  (str "# Managed by dev-kit (`kit init`); the code owners come from :owners in kit.edn.\n"
       "# With \"Require review from Code Owners\" in the main ruleset, a pull request merges only\n"
       "# with the approval of one of them, whoever else approves it.\n"
       "* "
       (str/join " " (map #(str "@" %) owners))
       "\n"))


(def agent-marker
  "<!-- Managed by dev-kit: this copy is rewritten by `kit init`. Edit the original in dev-kit. -->")


(defn agent-text
  [source]
  (let [s (slurp (str source))
        [_ front body] (re-matches #"(?s)(---\n.*?\n---\n)(.*)" s)]
    (str front "\n" agent-marker "\n" body)))


(defn kondo-entry [] (str "../" base/mount "/clojure/clj-kondo"))


(defn kondo-map-text [] (str "{:config-paths [\"" (kondo-entry) "\"]}"))


(defn managed-items
  "Everything init writes and doctor verifies, derived from kit.edn."
  [cfg]
  (concat
   [{:kind :block :path "AGENTS.md" :content (config/block-text cfg)}
    {:kind :file :path ".claude/settings.json" :content (config/settings-text cfg)}
    {:kind    :file
     :path    ".github/workflows/ci.yml"
     :content (slurp (str (fs/path base/kit-dir "managed" "ci.yml")))}
    ;; ADR-0007: marks a draft pull request ready when its required checks passed; kit finish opens drafts
    ;; only where the base branch holds this file
    {:kind    :file
     :path    ".github/workflows/ready-when-green.yml"
     :content (slurp (str (fs/path base/kit-dir ".github" "workflows" "ready-when-green.yml")))}
    {:kind :file :path ".github/CODEOWNERS" :content (codeowners-text cfg)}
    {:kind    :file
     :path    ".github/ISSUE_TEMPLATE/task.yml"
     :content (slurp (str (fs/path base/kit-dir "managed" "github" "ISSUE_TEMPLATE" "task.yml")))}
    {:kind    :file
     :path    ".github/pull_request_template.md"
     :content (slurp (str (fs/path base/kit-dir "managed" "github" "pull_request_template.md")))}
    {:kind    :file
     :path    ".claude/hooks/pretooluse.sh"
     :content (str/replace (slurp (str (fs/path base/kit-dir "managed" "pretooluse.sh"))) "{{kit}}" base/mount)}
    ;; the safety guard's policy (#20): the one file Claude Code, Codex and OpenCode all read
    {:kind :policy :path ".cc-safety-net/policy.json"}]
   ;; Claude Code's worktrees live inside the project; per clone, so no file of the project's own is touched (#97)
   [{:kind :ignored :path ".claude/worktrees/" :local true}]
   (when-let [a (:agent cfg)]
     [{:kind :ignored :path local-settings :local true}
      {:kind :local-env :path local-settings :env (agent-env a) :local true}])
   (for [dir   (sort (fs/list-dir (fs/path base/kit-dir "claude" "skills")))
         :when (fs/directory? dir)
         :let  [n (str (fs/file-name dir))]]
     {:kind :symlink :path (str ".claude/skills/" n) :target (str "../../" base/mount "/claude/skills/" n)})
   (for [dir (cond-> [(fs/path base/kit-dir "claude" "agents")]
               (config/clojure? cfg) (conj (fs/path base/kit-dir "claude" "agents" "clojure")))
         f   (sort (fs/glob dir "*.md"))]
     {:kind :file :path (str ".claude/agents/" (fs/file-name f)) :content (agent-text f)})
   (when (config/clojure? cfg)
     [{:kind :symlink :path ".zprint.edn" :target (str base/mount "/clojure/.zprint.edn")}
      {:kind :kondo :path ".clj-kondo/config.edn"}])
   [{:kind :git-config :key "core.hooksPath" :value (str base/mount "/githooks") :local true}
    ;; a plain git pull never makes a merge commit by accident (#135)
    {:kind :git-config :key "pull.ff" :value "only" :local true}]))


(defn stale-paths
  "Managed files left over from an older dev-kit version or a module that was switched off."
  [items]
  (let [wanted (set (map :path items))
        links  (for [f     (when (fs/directory? (base/rel ".claude/skills"))
                             (fs/list-dir (base/rel ".claude/skills")))
                     :when (and (fs/sym-link? f)
                                (str/includes? (str (fs/read-link f)) (str base/mount "/")))]
                 f)
        agents (for [f     (when (fs/directory? (base/rel ".claude/agents"))
                             (fs/glob (base/rel ".claude/agents") "*.md"))
                     :when (str/includes? (slurp (str f)) agent-marker)]
                 f)
        zprint (when (and (fs/sym-link? (base/rel ".zprint.edn"))
                          (str/starts-with? (str (fs/read-link (base/rel ".zprint.edn"))) (str base/mount "/")))
                 [(base/rel ".zprint.edn")])]
    (->> (concat links agents zprint)
         (map #(str (fs/relativize base/root %)))
         (remove wanted)
         sort)))
