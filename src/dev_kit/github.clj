(ns dev-kit.github
  "kit github (#33): the repository's main ruleset and the agent's access, compared with what dev-kit expects."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.gate :as gate]
   [dev-kit.identity :as ident]
   [dev-kit.problem :as problem]))


(defn gh-json [& args] (json/parse-string (apply base/gh! args) true))


(defn gh-send!
  "gh api with a JSON body on stdin; stdout, or an exception carrying gh's message."
  [path method body]
  (let [{:keys [exit out err]} (p/sh {:continue true :in (json/generate-string body)}
                                     "gh"
                                     "api"     path
                                     "-X"      method
                                     "--input" "-")]
    (if (zero? exit)
      out
      (throw (base/gh-failure ["api" path "-X" method "--input" "-"] exit (str err out))))))


(defn expected-ruleset
  "managed/github/ruleset.json with the project's required check, the owners' teams as required reviewers, and the
  person who runs kit github as the only bypass actor, in pull-request mode (ADR-0003)."
  [check-name team-ids owner-id]
  (let [reviewers (vec (for [id team-ids]
                         {:minimum_approvals 1 :file_patterns ["*" "**/*"] :reviewer {:id id :type "Team"}}))]
    (-> (json/parse-string (slurp (str (fs/path base/kit-dir "managed" "github" "ruleset.json"))) true)
        (assoc :bypass_actors [{:actor_type "User" :actor_id owner-id :bypass_mode "pull_request"}])
        (update :rules
                (partial
                 mapv
                 (fn [r]
                   (case (:type r)
                     "pull_request" (cond-> r (seq reviewers) (assoc-in [:parameters :required_reviewers] reviewers))
                     "required_status_checks" (assoc-in r [:parameters :required_status_checks 0 :context] check-name)
                     r)))))))


(defn- norm
  "A value for comparison: lists become sets, at every level, so order does not count."
  [v]
  (cond (map? v)        (update-vals v norm)
        (sequential? v) (set (map norm v))
        :else           v))


(defn ruleset-differences
  "How the repository's ruleset `have` differs from `want`, one line each. Only the keys dev-kit sets count: a field
  GitHub adds later is no difference."
  [want have]
  (let [by-type #(into {} (map (juxt :type identity)) (:rules %))
        w       (by-type want)
        h       (by-type have)
        js      json/generate-string
        bypass  #(set (map (fn [a] (select-keys a [:actor_type :actor_id :bypass_mode])) %))]
    (concat
     (for [k     [:enforcement :target :conditions]
           :when (not= (norm (get want k)) (norm (get have k)))]
       (str (name k) ": " (js (get have k)) " → " (js (get want k))))
     (when (not= (bypass (:bypass_actors want)) (bypass (:bypass_actors have)))
       [(str "bypass_actors: " (js (:bypass_actors have)) " → " (js (:bypass_actors want)))])
     (for [t (sort (remove w (keys h)))] (str "rule " t ": not in dev-kit's ruleset, removed"))
     (for [t (sort (remove h (keys w)))] (str "rule " t ": added"))
     (for [[t r] (sort-by key w)
           :when (h t)
           [k v] (sort-by key (:parameters r))
           :let  [hv (get-in h [t :parameters k])]
           :when (not= (norm v) (norm hv))]
       (str "rule " t ", " (name k) ": " (js hv) " → " (js v)))
     ;; A PUT replaces a rule's parameters: one dev-kit does not set falls back to GitHub's default. Shown when it
     ;; holds something, e.g. a reviewer team, so it never goes unseen.
     (for [[t r]  (sort-by key w)
           :when  (h t)
           [k hv] (sort-by key (get-in h [t :parameters]))
           :when  (and (not (contains? (:parameters r) k))
                       (not (or (nil? hv) (false? hv) (and (coll? hv) (empty? hv)))))]
       (str "rule " t ", " (name k) ": " (js hv) " → not set by dev-kit, reset")))))


(defn yes-expr
  "What counts as yes, as Linux's rpmatch(3) has it (#91): ^[yY] in any locale, and the locale's own pattern,
  `locale yesexpr` (glibc's ru_RU: ^[+1yYДд]). A Cyrillic у is no, as there."
  []
  (let [own (try (some-> (p/sh {:continue true} "locale" "yesexpr") :out str/trim not-empty re-pattern)
                 (catch Exception _ nil))]
    (fn [answer] (boolean (or (re-find #"^[yY]" answer) (some-> own (re-find answer)))))))


(defn- confirm!
  "True when the changes may go ahead: --yes, or a yes from the person at the terminal. Any other answer is named,
  so a no is never mistaken for a fault."
  [yes]
  (or yes
      (and (ident/interactive?)
           (do (print "Apply these changes? [y/N] ")
               (flush)
               (let [a (str/trim (or (read-line) ""))]
                 (or ((yes-expr) a)
                     (do (println (str "  the answer was «" a "», not yes (y, or the yes of this system's language)"))
                         false)))))))


(defn github!
  "Brings one repository's GitHub side to what the agent's work relies on (#33): the main ruleset from
  managed/github/ruleset.json, Write for the agent and every :owners entry, and the agent's invitation accepted as
  the agent. Run by the owner, in their own terminal: changing a ruleset takes an admin. Safe to run again."
  [{check-name :check :keys [yes] :as opts}]
  (when (System/getenv "CLAUDECODE")
    (problem/fail!
     :github/agent-session
     (str
      "kit github changes the repository's settings, which an agent session never does: run it in your own terminal, "
      "logged in to gh as the owner")))
  (let [cfg        (gate/gate-config)
        repo       (gate/gate-repo opts cfg)
        first-line #(first (str/split-lines (str %)))
        read!      (fn [what & args]
                     (try (apply gh-json args)
                          (catch clojure.lang.ExceptionInfo e
                            (problem/fail! :github/unreadable
                                           (str what
                                                " could not be read ( " (first-line (ex-message e))
                                                " ): check `gh auth status` and the "
                                                "connection, then run kit github again")))))
        _ (when-not (get-in (read! repo "api" (str "repos/" repo)) [:permissions :admin])
            (problem/fail! :github/not-admin
                           (str "gh is not logged in as an admin of " repo
                                " (gh auth status): changing its ruleset takes an admin; "
                                "run kit github as the owner")))
        org        (first (str/split repo #"/"))
        teams      (filter #(str/includes? % "/") (:owners cfg))
        users      (remove #(str/includes? % "/") (:owners cfg))
        _ (doseq [t     teams
                  :when (not (str/starts-with? t (str org "/")))]
            (problem/fail! :github/foreign-team
                           (str "the team " t
                                " in :owners is not of " org
                                " ; a team gives access only to its own organization's "
                                "repositories: fix :owners in kit.edn")))
        sets       (read! (str "the rulesets of " repo) "api" (str "repos/" repo "/rulesets?per_page=100"))
        mains      (filter #(= "main" (:name %)) sets)
        _ (when (next mains)
            (problem/fail! :github/several-main-rulesets
                           (str repo
                                " has "
                                (count mains)
                                " rulesets named main (ids "
                                (str/join ", " (map :id mains))
                                "): "
                                "keep one in Settings → Rules → Rulesets, then run kit github again")))
        _ (when (and (empty? mains) (seq sets))
            (problem/fail!
             :github/no-main-ruleset
             (str repo
                  " has rulesets, none named main ( "
                  (str/join ", " (map :name sets))
                  " ): rename the one that "
                  "protects the default branch to main in Settings → Rules → Rulesets, or delete it, then run "
                  "kit github again")))
        main       (first mains)
        have       (when main
                     (read! "the main ruleset" "api" (str "repos/" repo "/rulesets/" (:id main))))
        ;; Without --check an existing ruleset keeps its check: dev-kit's own is selftest, a project's is checks.
        check-name (or check-name
                       (some #(when (= "required_status_checks" (:type %))
                                (get-in % [:parameters :required_status_checks 0 :context]))
                             (:rules have))
                       "checks")
        _ (when-not (re-matches #"[A-Za-z0-9 ._/()-]+" check-name)
            (problem/fail! :github/bad-check-name (str "--check " (pr-str check-name) " is not a check name")))
        me         (read! "your GitHub account" "api" "user")
        want       (expected-ruleset check-name
                                     (for [t teams]
                                       (:id (read! (str "the team " t " of :owners")
                                                   "api"
                                                   (str "orgs/" (str/replace t "/" "/teams/")))))
                                     (:id me))
        diffs      (when have
                     (seq (ruleset-differences want have)))
        perm       #(try (str/trim (base/gh! "api"  (str "repos/" repo "/collaborators/" % "/permission")
                                             "--jq" ".permission"))
                         (catch clojure.lang.ExceptionInfo _ "none"))
        agent      (get-in cfg [:agent :login])
        _ (when-let [p (some-> agent perm #{"admin" "maintain"})]
            (problem/fail!
             :github/agent-too-strong
             (str
              "the agent " agent
              " has "      p
              " on "       repo
              " , more than Write: with it the agent could change the rules "
              "it works under. Lower it to Write in Settings → Collaborators and teams, then run kit github again")))
        pending    (set (keep #(get-in % [:invitee :login])
                              (read! "the invitations" "api" (str "repos/" repo "/invitations"))))
        access     (into {} (map (juxt :slug :permission)) (read! "the teams" "api" (str "repos/" repo "/teams")))
        plan       (concat
                    (cond (nil? have) [["ruleset main: created"
                                        #(gh-send! (str "repos/" repo "/rulesets") "POST" want)]]
                          diffs       [[(str "ruleset main: updated\n" (str/join "\n" (map #(str "        " %) diffs)))
                                        #(gh-send! (str "repos/" repo "/rulesets/" (:id main)) "PUT" want)]])
                    (for [u     (distinct (remove nil? (cons agent users)))
                          :let  [p (perm u)]
                          :when (and (not (#{"write" "maintain" "admin"} p)) (not (pending u)))]
                      [(str u ": invited with Write (now " p ")")
                       #(base/gh! "api" (str "repos/" repo "/collaborators/" u) "-X" "PUT" "-f" "permission=push")])
                    (for [t     teams
                          :let  [slug (second (str/split t #"/"))]
                          :when (not (#{"push" "maintain" "admin"} (access slug)))]
                      [(str "team " t ": given Write")
                       #(base/gh! "api" (str "orgs/" org "/teams/" slug "/repos/" repo)
                                  "-X"  "PUT"
                                  "-f"  "permission=push")]))]
    (println (str "dev-kit github (" repo ")"))
    (if (empty? plan)
      (println "  nothing to change")
      (do (doseq [[what] plan] (println (str "  " what)))
          (when-not (confirm! yes)
            (problem/fail! :github/not-confirmed "nothing was changed: run kit github --yes to apply these changes"))
          (doseq [[what act] plan]
            (try (act)
                 (catch clojure.lang.ExceptionInfo e
                   (problem/fail! :github/change-failed
                                  (str "\"" (first-line what)
                                       "\" failed (" (first-line (ex-message e))
                                       "); the steps above it"
                                       " are done. Run kit github again: it is safe to repeat")))))))
    (when agent
      (let [dir      (ident/expand-home (get-in cfg [:agent :gh-config-dir]))
            ;; As the agent: its own config, never a token the shell may hold.
            as-agent #(apply p/sh
                             {:continue true
                              :env      (-> (into {} (System/getenv))
                                            (dissoc "GH_TOKEN" "GITHUB_TOKEN")
                                            (assoc "GH_CONFIG_DIR" dir))}
                             "gh"
                             "api"
                             %&)
            by-hand  (str "accept it at https://github.com/"
                          repo
                          "/invitations signed in as "
                          agent
                          ", or run kit github again")]
        (if (= agent (ident/gh-login dir))
          (let [why #(or (not-empty (first-line (:err %))) (str "gh exit " (:exit %)))
                {:keys [exit out] :as r} (as-agent "user/repository_invitations")]
            (when-not (zero? exit)
              (problem/fail! :github/invitations-unreadable
                             (str "the agent's invitations could not be read (" (why r) "): " by-hand)))
            (doseq [inv   (json/parse-string (or (not-empty out) "[]") true)
                    :when (= repo (get-in inv [:repository :full_name]))]
              (let [r (as-agent (str "user/repository_invitations/" (:id inv)) "-X" "PATCH")]
                (when-not (zero? (:exit r))
                  (problem/fail! :github/invitation-not-accepted
                                 (str "accepting the invitation as " agent " failed (" (why r) "): " by-hand)))
                (println (str "  " agent ": accepted the invitation, as the agent")))))
          (when (or (pending agent) (some #(str/starts-with? (first %) (str agent ":")) plan))
            (println (str "  "
                          agent
                          " has an invitation to accept: in this terminal, "
                          base/mount
                          "/bin/kit init logs the agent in; then run kit github again"))))))))
