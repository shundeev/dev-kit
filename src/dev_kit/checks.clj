(ns dev-kit.checks
  "What kit doctor finds wrong with a project's setup: each check returns its problems, none prints."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.config :as config]
   [dev-kit.identity :as ident]))


(defn block-markers
  "The dev-kit blocks of AGENTS.md text: {:pairs [[first-line last-line] …], :stray [line numbers]}, lines counted
  from 0 in :pairs and from 1 in :stray. A marker is found by its start, so another version's wording still counts."
  [s]
  (loop [[l & more :as ls] (str/split s #"\n" -1)
         i     0
         open  nil
         pairs []
         stray []]
    (cond
      (empty? ls) {:pairs pairs :stray (cond-> stray open (conj (inc open)))}
      (str/starts-with? l "<!-- dev-kit:begin") (recur more (inc i) i pairs (cond-> stray open (conj (inc open))))
      (str/starts-with? l "<!-- dev-kit:end") (if open
                                                (recur more (inc i) nil (conj pairs [open i]) stray)
                                                (recur more (inc i) nil pairs (conj stray (inc i))))
      :else (recur more (inc i) open pairs stray))))


(defn with-block
  "AGENTS.md text with every dev-kit block removed and content put where the first one was, in the file's line ends."
  [s content pairs]
  (let [content      (if (str/includes? s "\r\n")
                       (str (str/replace content "\n" "\r\n") "\r")
                       content) ; lines join on \n
        lines        (str/split s #"\n" -1)
        [first-line] (first pairs)
        in-pair?     (fn [i] (some (fn [[a b]] (<= a i b)) pairs))]
    (str/join "\n"
              (concat (subvec lines 0 first-line)
                      [content]
                      (keep-indexed (fn [i l] (when (and (> i first-line) (not (in-pair? i))) l)) lines)))))


(defn block-problem
  "nil when AGENTS.md text holds exactly the dev-kit block content, otherwise the problem."
  [s content]
  (let [{:keys [pairs stray]} (block-markers s)]
    (cond
      (seq stray)    (str "line " (str/join ", " stray)
                          " has a dev-kit marker without its pair: delete what is left of the"
                          " old block, or restore the missing marker, then run kit init")
      (empty? pairs) "has no dev-kit block"
      (next pairs)   (str "has " (count pairs) " dev-kit blocks")
      (not= s (with-block s content pairs)) "the dev-kit block differs from dev-kit's rules")))


(defn kondo-config
  "The form in a clj-kondo config.edn: nil when it holds none (empty, comments only), ::unreadable when it fails."
  [s]
  (try (edn/read-string s) (catch Exception _ ::unreadable)))


(def policy-default
  "The safety guard's policy as init first writes it (#20). Strict by default; worktree_mode relaxes `git restore`
  and `git checkout <ref> -- <path>` in a linked worktree only (#107): in the main checkout and for
  `git reset --hard` everywhere they stay refused. allow_paths lets an agent `rm -rf` under ~/.claude/jobs only (#117)."
  {"version"  1
   "safety"   {"level" "standard"}
   "workflow" {"worktree_mode" true}
   "destructive_command_protection" {"allow_paths" ["~/.claude/jobs"]}})


(defn policy-map
  "The policy file's map: nil when the file is absent, ::unreadable when it is not a JSON object."
  [f]
  (when-let [s (base/slurp-if f)]
    (let [m (try (json/parse-string s) (catch Exception _ ::unreadable))]
      (if (map? m) m ::unreadable))))


(defn check
  "nil when the item is in place, otherwise a one-line problem."
  [{git-key :key :keys [kind path content target value env]}]
  (let [f (some-> path base/rel)]
    (case kind
      :ignored (ident/ignored-problem base/root path)

      :local-env (ident/local-env-problem base/root env)

      :file
      (cond (not (fs/exists? f)) "missing"
            (not= content (base/slurp-if f)) "differs from what dev-kit generates")

      :block
      (if-let [s (base/slurp-if f)]
        (block-problem s content)
        "missing")

      :symlink
      (cond (not (fs/sym-link? f)) (if (fs/exists? f) "is a real file, not the dev-kit link" "missing")
            (not= target (str (fs/read-link f))) (str "points to " (fs/read-link f) ", expected " target)
            (not (fs/exists? f))   (str "dangling: " target " does not exist"))

      ;; the project owns the file after the first write: init only adds worktree_mode and allow_paths when the key is absent
      :policy
      (let [m (policy-map f)]
        (cond (not (fs/exists? f)) "missing"
              (= m ::unreadable) "cannot be read as a JSON object: fix it by hand"
              (not (contains? (get m "workflow") "worktree_mode"))
              "lacks workflow.worktree_mode (a linked worktree may then not discard its own work)"
              (not (contains? (get m "destructive_command_protection") "allow_paths"))
              "lacks destructive_command_protection.allow_paths (an agent may then not remove its scratch folders)"))

      :kondo
      (let [m (some-> (base/slurp-if f) kondo-config)]
        (cond (not (fs/exists? f)) "missing"
              (= m ::unreadable) "cannot be read: fix it by hand"
              (and (some? m) (not (map? m)))
              (str "is not a map: make it a map, e.g. " (ident/kondo-map-text))
              (not (contains? (set (:config-paths m)) (ident/kondo-entry)))
              (str ":config-paths lacks \"" (ident/kondo-entry) "\"")))

      :git-config
      (let [v (base/sh "git" "config" "--local" "--get" git-key)]
        (when (not= value v) (str "is " (or v "unset") ", expected " value))))))


(defn label
  [{git-key :key :keys [kind path]}]
  (if (= kind :git-config)
    (str "git config " git-key)
    path))


(defn submodule-problems
  []
  (let [paths  (some-> (base/sh "git" "config"
                         "-f"           ".gitmodules"
                         "--get-regexp" "path")
                       str/split-lines)
        status (base/sh "git" "submodule" "status" base/mount)
        dirty  (base/sh "git" "-C" base/mount "status" "--porcelain")]
    (cond-> []
      (not-any? #(str/ends-with? % (str " " base/mount)) paths)
      (conj [:fail (str base/mount " is not a submodule of this repository (.gitmodules has no path " base/mount ")")
             (str "git submodule add https://github.com/shundeev/dev-kit " base/mount)])

      (some-> status (str/starts-with? "+"))
      (conj [:warn (str base/mount " is checked out at another commit than the one this repository pins")
             (str "git submodule update " base/mount ", or commit the bump")])

      (some-> status (str/starts-with? "U"))
      (conj [:fail (str base/mount " has a merge conflict") "resolve it, then git add " base/mount])

      (seq dirty)
      (conj [:warn (str base/mount " has local changes") "commit them in dev-kit, or git -C .shared checkout ."]))))


(def min-claude-version
  "onFailure on hooks needs it; older versions let a failing guard through (ADR-0001, rule 3)."
  [2 1 295])


(defn claude-version-problems
  []
  (when-let [out (base/sh "bash" "-c" "command -v claude >/dev/null && claude --version")]
    (let [v (some->> (re-find #"(\d+)\.(\d+)\.(\d+)" out) rest (mapv parse-long))]
      (when (and v (neg? (compare v min-claude-version)))
        [[:fail
          (str "Claude Code " (str/join "." v) " ignores onFailure, so a failing guard would let commands through")
          (str "update Claude Code to " (str/join "." min-claude-version) " or later")]]))))


(defn pinned-bb-version
  "The bb version dev-kit is tested on: the bb its ci/action.yml has setup-clojure install, read from the parsed
  YAML. nil when the file is missing or unreadable, or names no bb."
  []
  (let [action (try (some-> (base/slurp-if (fs/file base/kit-dir "ci" "action.yml")) yaml/parse-string)
                    (catch Exception _ nil))]
    (some->> (get-in action [:runs :steps])
             (some #(get-in % [:with :bb]))
             str)))


(defn bb-version-problems
  "An older bb than the one dev-kit is tested on: kit's bb.edn and its command tree need it (ADR-0008).
  :min-bb-version in bin/bb.edn only warns, so this is the check that fails."
  [running pinned]
  (let [parts #(some->> % (re-find #"^(\d+)\.(\d+)\.(\d+)") rest (mapv parse-long))]
    (when (and (parts running) (parts pinned) (neg? (compare (parts running) (parts pinned))))
      [[:fail
        (str "bb " running " is older than " pinned ", the version dev-kit is tested on (its ci/action.yml)")
        (str "update bb to "
             pinned
             " or later: brew upgrade babashka, or the installer of"
             " https://github.com/babashka/babashka#installation with --version "
             pinned)]])))


(defn node-problems
  "The safety guard (#20) runs on Node 22 or later; without it every guarded call is refused."
  []
  (let [v     (base/sh "node" "--version")
        major (some->> v (re-find #"^v(\d+)\.") second parse-long)]
    (when-not (and major (>= major 22))
      [[:fail
        (str "Node 22 or later is needed for the safety guard, which refuses every guarded call without it ("
             (or v "node not found")
             ")")
        "install Node 22 or later (e.g. nvm install 22), then restart the Claude session"]])))


(defn tool-problems
  [cfg]
  (concat
   (for [[tool why] (cond-> [["git" "everything"]
                             ["gh" "the issue flow scripts"]
                             ["jq" "the flow scripts and the friction recorder"]]
                      (config/clojure? cfg) (into [["clj-kondo" "lint"] ["zprint" "format check"]]))
         :when      (nil? (base/sh "bash" "-c" (str "command -v " tool)))]
     [:fail (str tool " is not installed (needed for " why ")") (str "install " tool)])
   (node-problems)))


(defn ownership-problems
  "Where init would replace what dev-kit did not write (#14): a file that is neither what dev-kit writes nor marked
  as dev-kit's, a real file where a link goes, a hooks path of another tool. JSON carries no marker: a JSON file of
  dev-kit's counts by its content (#20), and .claude/settings.json found before the first init moves into kit.edn."
  [items]
  (for [{git-key :key :keys [kind path value content] :as item} items
        :let  [f       (some-> path base/rel)
               problem (case kind
                         :file
                         (when (and (fs/regular-file? f)
                                    (not= path ".claude/settings.json")
                                    (not= content (slurp f))
                                    (not (str/includes? (slurp f) "Managed by dev-kit")))
                           (if (str/starts-with? path ".claude/agents/")
                             "is an agent of the project's own with dev-kit's name: rename it"
                             "holds a file dev-kit did not write: move it away, or merge what it needs elsewhere"))
                         :symlink (when (and (fs/exists? f) (not (fs/sym-link? f)))
                                    (str "is a real "
                                         (if (fs/directory? f) "folder" "file")
                                         " where dev-kit puts a link to "
                                         (:target item)
                                         ": move it away"))
                         :git-config (let [v (base/sh "git" "config" "--local" "--get" git-key)]
                                       (when (and v (not= v value) (= git-key "core.hooksPath"))
                                         (str "is " v
                                              ": call those hooks from " value
                                              " or drop them, then git config --unset " git-key)))
                         nil)]
        :when problem]
    (str (label item) " " problem "; then run kit init again")))


(defn user-reads-both?
  "Whether the user's Claude Code settings load AGENTS.md next to CLAUDE.md files (Project instructions)."
  []
  (let [m (ident/read-json-object (ident/expand-home "~/.claude/settings.json"))]
    (some #(= "claude-md-and-agents-md" (get-in m ["pluginConfigs" % "options" "instructionFiles"]))
          ["cc-plugin-agents-md@builtin" "agents-md@builtin"])))


(defn claude-md-problems
  "Claude Code reads AGENTS.md only when no CLAUDE.md, .claude/CLAUDE.md or CLAUDE.local.md is in the project or
  above it; the user's own ~/.claude/CLAUDE.md does not count
  (https://code.claude.com/docs/en/memory#when-claude-code-reads-agents-md). --ci looks at the committed files only."
  [ci]
  (let [real  #(str (fs/real-path %))
        user  (let [u (ident/expand-home "~/.claude/CLAUDE.md")] (when (fs/exists? u) (real u)))
        found (distinct
               (for [d     (if ci
                             [base/root]
                             (take-while some? (iterate fs/parent (fs/real-path base/root))))
                     f     (if ci
                             ["CLAUDE.md" ".claude/CLAUDE.md"]
                             ["CLAUDE.md" ".claude/CLAUDE.md" "CLAUDE.local.md"])
                     :let  [p (fs/path d f)]
                     :when (and (fs/exists? p) (not= (real p) user))]
                 (real p)))]
    (when (and (seq found) (or ci (not (user-reads-both?))))
      [[:fail (str (str/join ", " found) " hides AGENTS.md: Claude Code then never reads the rules")
        "move its content into AGENTS.md below the dev-kit block and delete it, or set Project instructions to claude-md-and-agents-md in /config"]])))


(defn github-problems
  "The GitHub side the agent's work relies on (#33), read as the agent, so only when gh acts as the agent: the main
  ruleset requires an approving review, the agent cannot bypass it, and the agent has Write. Offline it warns."
  [{:keys [agent] :as cfg}]
  (let [login (:login agent)
        token (some #(not-empty (System/getenv %)) ["GH_TOKEN" "GITHUB_TOKEN"])]
    (when (and login (not token) (= login (ident/gh-login (ident/gh-config-dir-in-use))))
      (let [repo (or (base/origin-repo (str base/root)) (:repo cfg))
            fix  (str "in your own terminal: " base/mount "/bin/kit github")]
        (try
          (let [main (some #(when (= "main" (:name %)) %)
                           (json/parse-string (base/gh! "api" (str "repos/" repo "/rulesets?per_page=100")) true))
                rs   (when main (json/parse-string (base/gh! "api" (str "repos/" repo "/rulesets/" (:id main))) true))
                n    (some #(when (= "pull_request" (:type %))
                              (get-in % [:parameters :required_approving_review_count]))
                           (:rules rs))
                perm (str/trim (base/gh! "api"  (str "repos/" repo "/collaborators/" login "/permission")
                                         "--jq" ".permission"))]
            (cond-> []
              (nil? rs)
              (conj [:fail
                     (str repo " has no main ruleset, so the agent's pull requests could merge without an Approve")
                     fix])

              (and rs (not= "active" (:enforcement rs)))
              (conj [:fail (str repo ": the main ruleset is " (:enforcement rs) ", not active") fix])

              (and rs (not (and (int? n) (pos? n))))
              (conj [:fail (str repo ": the main ruleset requires no approving review") fix])

              (and rs (not= "never" (:current_user_can_bypass rs)))
              (conj [:fail
                     (str repo ": the agent " login " can bypass the main ruleset (" (:current_user_can_bypass rs) ")")
                     fix])

              (not= "write" perm)
              (conj [:fail (str repo ": the agent's permission is " perm ", not write") fix])))
          (catch clojure.lang.ExceptionInfo e
            [[:warn
              (str repo
                   ": GitHub could not be read to check the main ruleset ("
                   (first (str/split-lines (ex-message e)))
                   ")")
              "check the connection; the next session start checks it again"]]))))))


(defn problems
  [cfg {:keys [ci]}]
  (cond
    (nil? cfg)
    [[:fail "kit.edn is missing" (str base/mount "/bin/kit init --repo <owner/repo> --showcase <name>")]]

    (seq (config/config-problems cfg))
    (for [p (config/config-problems cfg)] [:fail (str "kit.edn: " p) "fix kit.edn"])

    :else
    (concat
     (claude-md-problems ci)
     (submodule-problems)
     (for [item  (ident/managed-items cfg)
           :when (not (and ci (:local item)))
           :let  [problem (check item)]
           :when problem]
       [:fail (str (label item) ": " problem) (str base/mount "/bin/kit init")])
     (for [p (ident/stale-paths (ident/managed-items cfg))]
       [:fail (str p ": left over from dev-kit, no longer managed") (str base/mount "/bin/kit init")])
     (when-not ci
       (concat (tool-problems cfg)
               (claude-version-problems)
               (bb-version-problems (System/getProperty "babashka.version") (pinned-bb-version))
               (ident/identity-problems cfg)
               (github-problems cfg))))))
