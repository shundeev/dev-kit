(ns dev-kit.guard.rules
  "The guard's rules: what is refused, what is asked about, and the hook's entry point (#141)."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.guard.statements :as statements]
   [dev-kit.guard.tokenizer :as tokenizer]))


;; ------------------------------------------------------------------ rules


(defn decide
  [d reason]
  (println (json/generate-string {:hookSpecificOutput {:hookEventName      "PreToolUse"
                                                       :permissionDecision d
                                                       :permissionDecisionReason reason}}))
  (System/exit 0))


(defn- sh-in
  "A command's stdout in directory dir (nil: this one), or nil when it fails."
  [dir & args]
  (let [{:keys [exit out]} (apply p/sh (cond-> {:continue true} dir (assoc :dir dir)) args)]
    (when (zero? exit)
      (str/trim out))))


(defn- sh-out [& args] (apply sh-in nil args))


(def issue-branch? #(boolean (re-matches #"[0-9].*-.*" (or % ""))))


(defn resolve-dir
  "A directory word with a leading ~, $NAME and ${NAME} replaced by the value of a NAME=value earlier in the
  command (vars), else of the environment (#124). Plain substitution, nothing is run. {:unresolved word} when
  it holds $(…) or a backtick, or a variable has no value: the session's folder is never taken for it."
  [vars word]
  (let [word  (str word)
        value #(or (get vars %) (System/getenv %))
        named #"\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?"]
    (if (or (re-find #"`|\$\(" word) (not-every? value (map second (re-seq named word))))
      {:unresolved word}
      (-> word
          (str/replace #"^~(?=/|$)" (str/re-quote-replacement (System/getenv "HOME")))
          (str/replace named (comp value second))))))


(defn assigned
  "vars with the NAME=value words that open statement st, or follow `export`; a value that cannot be
  resolved leaves its name unset."
  [vars st]
  (reduce (fn [vars {:keys [t]}]
            (let [[_ n v] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" t)
                  v       (resolve-dir vars v)]
              (if (string? v)
                (assoc vars n v)
                (dissoc vars n))))
          vars
          (take-while #(statements/assignment? (:t %)) (cond-> st (= "export" (:t (first st))) rest))))


(defn push-refused
  "[branch why] for a forced push the rules refuse, else nil: :branch when it is not an issue branch (#38), :approved
  when its open PR holds an approval, which a rewrite dismisses (#117), :dir when the push's directory cannot be
  resolved and the rule needs it (#124). Only a forced push to an issue branch reads GitHub, in the push's
  repository (#99); when it cannot be read the push passes."
  [dir args vars]
  (let [[dir args] (loop [dir        dir
                          [a & more] args]
                     (cond (= a "-C") (recur (resolve-dir vars (first more)) (rest more))
                           ;; git's global options before the command, some with a value
                           (#{"-c" "--git-dir" "--work-tree" "--namespace" "--super-prefix" "--config-env"} a)
                           (recur dir (rest more))
                           (= a "push") [dir more]
                           (and a (str/starts-with? a "-")) (recur dir more)
                           :else [dir nil]))]
    (when args
      (let [refs  (rest (remove #(str/starts-with? % "-") args))
            force (or (some #(re-matches #"--force(-with-lease(=.*)?|-if-includes)?|-[A-Za-z]*f[A-Za-z]*" %) args)
                      (some #(str/starts-with? % "+") refs))]
        (when force
          (some (fn [r]
                  (let [dst (-> r (str/replace #"^\+" "") (str/replace #"^.*:" "") (str/replace #"^refs/heads/" ""))
                        dst (if (and (= dst "HEAD") (string? dir))
                              (or (sh-out "git" "-C" dir "symbolic-ref" "--short" "-q" "HEAD") "HEAD")
                              dst)]
                    (cond (and (map? dir) (or (= dst "HEAD") (issue-branch? dst))) [(:unresolved dir) :dir]
                          (not (issue-branch? dst)) [(if (str/blank? dst) "the branches matching on both sides" dst)
                                                     :branch]
                          (= "APPROVED"
                             (sh-in dir
                                    "gh"
                                    "pr"      "list"
                                    "--head"  dst
                                    "--state" "open"
                                    "--json"  "reviewDecision"
                                    "--jq"    ".[0].reviewDecision // empty"))
                          [dst :approved])))
                (if (seq refs)
                  refs
                  ["HEAD"])))))))


(defn api-write
  "What a `gh api` call would change (#54): a method other than GET, fields without a method (gh then sends
  POST), a GraphQL mutation, or a GraphQL query read from a file. nil for a read."
  [ts]
  (let [{:keys [method fields endpoint]}
        (loop [[w & more] ts
               acc        {}]
          (cond
            (nil? w) acc
            (#{"-X" "--method"} w) (recur (rest more) (assoc acc :method (first more)))
            (str/starts-with? w "--method=") (recur more (assoc acc :method (subs w 9)))
            (re-matches #"-X.+" w) (recur more (assoc acc :method (subs w 2)))
            (#{"-f" "-F" "--field" "--raw-field" "--input"} w) (recur (rest more) (assoc acc :fields true))
            (#{"-H" "--header" "-q" "--jq" "-t" "--template" "--hostname" "--cache" "-p" "--preview"} w)
            (recur (rest more) acc)
            (or (str/starts-with? w "-") (#{"gh" "api"} w) (str/ends-with? w "/gh")) (recur more acc)
            :else (recur more (update acc :endpoint #(or % w)))))
        method (some-> method str/upper-case)]
    (cond
      method (when (not= method "GET")
               (str method " " endpoint))
      (= endpoint "graphql") (cond (some #(str/includes? % "mutation") ts)   "a GraphQL mutation"
                                   (some #(str/starts-with? % "query=@") ts) "a GraphQL query read from a file")
      fields (str "POST " endpoint ": fields make gh send POST"))))


(defn head-branch
  "The branch `gh pr create` names with --head/-H, without a fork's owner: prefix; nil when absent."
  [ts]
  (some (fn [[w nxt]]
          (let [cand (cond (str/starts-with? w "--head=") (subs w 7)
                           (str/starts-with? w "-H=") (subs w 3)
                           (#{"--head" "-H"} w)       nxt)]
            (when (and cand (seq cand) (not (str/starts-with? cand "-")))
              (str/replace cand #"^[^:]*:" ""))))
        (partition-all 2 1 ts)))


(def var-word #"\$(\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*)")


(defn zsh-split?
  "A statement that needs an unquoted $v split into words, which zsh does not do (#64)."
  [ws]
  (let [v? (fn [w] (and w (:var w) (re-matches var-word (:t w))))
        ts (mapv :t ws)]
    (or (and (= (get ts 0) "set") (= (get ts 1) "--") (v? (get ws 2)))
        (v? (first ws))
        (and (= (get ts 0) "for") (= (get ts 2) "in") (v? (get ws 3))))))


(defn loop-marked
  "The statements, each marked (metadata :in-loop) when it sits in a loop body: from a statement that starts with
  `do` to the matching `done`."
  [statements]
  (loop [[st & more] statements
         depth       0
         out         []]
    (if-not st
      out
      (let [w     (:t (first st))
            depth (cond (= w "done") (max 0 (dec depth))
                        (= w "do")   (inc depth)
                        :else        depth)]
        (recur more depth (conj out (with-meta st {:in-loop (pos? depth)})))))))


(defn waits?
  "A statement that waits for one event while the call is not in the background (#109): gh pr checks --watch,
  gh run watch, kit merge, kit checks (also inside a loop), and a sleep that is not in a loop body. A sleep in a loop
  is a poll loop, Monitor's normal use. The guard cannot tell a foreground `until …; do sleep 1; done` from a Monitor
  poll loop (a Monitor call arrives as a Bash call), so that one passes; the skill's rule in words covers it: a wait
  for one event runs as a background Bash command."
  [ts in-loop?]
  (let [[head sub verb] ts
        base #(some-> % (str/replace #".*/" ""))
        kit  (if (= (base head) "bb")
               (rest ts)
               ts)]
    (boolean
     (or (and (= (base head) "sleep") (not in-loop?))
         (and (= (base head) "gh") (= sub "run") (= verb "watch"))
         (and (= (base head) "gh") (= sub "pr") (= verb "checks") (some #{"--watch" "-w"} ts))
         (and (= (base (first kit)) "kit") (#{"merge" "checks"} (second kit)))))))


(defn judge
  [statements background?]
  (let [base   (or (System/getenv "GHWF_DEFAULT_BASE") "main")
        repo   (or (System/getenv "GHWF_REPO") "<owner/repo>")
        start  ".shared/bin/kit start"
        finish ".shared/bin/kit finish"
        zsh?   (= "zsh" (some-> (System/getenv "SHELL") (str/replace #".*/" "")))]
    (loop [[st & more] statements
           wd          "."
           vars        {}]
      (when st
        (when (some #(str/includes? (:t %) "DELIVERY_GUARD=off") st)
          (decide
           "ask"
           "DELIVERY_GUARD=off is set: the delivery guard is being bypassed on purpose. Approve only if you asked for this raw command."))
        (let [ws   (vec (statements/strip-head st))
              ts   (mapv :t ws)
              [head sub verb] ts
              text (str/join " " ts)]
          ;; reading a command's documentation is not running it; a quoted --help is a value, not that flag
          (if (some #(and (not (:q %)) (#{"--help" "-h"} (:t %))) ws)
            (recur more wd (assigned vars st))
            (do
              (when (= head "git")
                (when-let [[target why] (push-refused wd (rest ts) vars)]
                  (decide
                   "deny"
                   (case why
                     :dir
                     (str
                      "Refused: cannot tell which repository this forced push rewrites (`"
                      target
                      "` has no value the guard can read here: it is not set in this command, or it comes from a command).

Run it as `git -C <absolute path> push …`, so the repository is named in the command itself (#124).")
                     :approved
                     (str
                      "Refused: a forced push to '"
                      target
                      "', whose pull request holds an approval. A rewrite dismisses it, and the owner must approve again (#117).

Bring the branch up to date without rewriting it: gh pr update-branch (a merge keeps the approval).
If the branch must be rewritten, say so to the owner first.")
                     (str
                      "Refused: a forced push to '"
                      target
                      "', which is not an issue branch. The agent rewrites
only its own issue branches, named <number>-<slug> (#38); main is also protected on the server.

Push without rewriting, or rewrite the issue branch the work is on.")))))
              (when (and (not background?) (waits? ts (:in-loop (meta st))))
                (decide
                 "deny"
                 "Refused: this command waits. Run it as a background command (run_in_background); you are woken when it exits (#109)."))
              (when (and zsh? (zsh-split? ws))
                (decide
                 "deny"
                 (str
                  "Refused: zsh does not split an unquoted variable into words, so '"
                  text
                  "' would get one word where it needs several.

Split it explicitly: ${=v} in zsh (set -- ${=t}; for x in ${=list}), use an array,
or run the loop in a bash script (bash script.sh), where words split as written.")))
              (when (and head (or (= head "gh") (str/ends-with? head "/gh")))
                (if (= sub "api")
                  (when-let [why (api-write ts)]
                    (decide
                     "ask"
                     (str "This gh api call can change GitHub ("
                          why
                          "). Approve it only if it should. A gh api call that only reads runs without asking (#54).")))
                  (case [sub verb]
                    ["issue" "create"]
                    (decide
                     "deny"
                     (str
                      "Refused: a raw `gh issue create` produces an issue that is on no board, with no Status and no Priority.

Use the flow — invoke the `repo-task-delivery` skill, or run its GitHub step directly:
  "
                      start
                      " --title \"...\" --body-file <path> --priority <Urgent|High|Medium|Low> --status \"In progress\"

That one call creates the issue (or reuses one with the same title), puts it on the board,
sets Status, Priority and Showcase, and creates the issue branch with `gh issue develop`.

If the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off."))

                    ["pr" "create"]
                    ;; The branch is --head's, else that of the directory the command runs in (ADR-0006, #126): -R names
                    ;; the repository and changes nothing here.
                    (let [named  (head-branch ts)
                          branch (when-not (or named (map? wd))
                                   (sh-in wd "git" "symbolic-ref" "--short" "-q" "HEAD"))]
                      (when (and (not named) (map? wd))
                        (decide
                         "deny"
                         (str
                          "Refused: cannot tell which branch this pull request is from (`"
                          (:unresolved wd)
                          "` has no value the guard can read here: it is not set in this command, or it comes from a command).

Run it from the worktree with a literal path (cd <absolute path> && gh pr create …), or name the branch: gh pr create --head <number>-<slug> (#126).")))
                      (when-not (issue-branch? (or named branch))
                        (decide
                         "deny"
                         (str
                          "Refused: "
                          (if named
                            (str "--head names '" named "'")
                            (str "HEAD is '" (or branch "unknown") "'"))
                          ", which carries no issue number, so this pull request would have no issue and no board entry behind it.

Get the issue and its branch first:
  "
                          start
                          " --title \"...\" --priority High --status \"In progress\"
or, when the issue already exists:
  gh issue develop <n> -R "
                          repo
                          " --checkout --base "
                          base
                          "

From a <number>-<slug> branch, commit, push, open the PR and set Status in one step:
  "
                          finish
                          " --add <path> --pr-body-file <path>

If the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off."))))
                    nil)))
              (recur more
                     (let [d (when (and (= head "cd") sub)
                               (resolve-dir vars sub))]
                       (if (or (map? d) (some-> d (str/starts-with? "/")))
                         d
                         wd))
                     (assigned vars st)))))))))


;; Claude Code sets run_in_background on a Bash call (https://code.claude.com/docs/en/hooks, the tool_input of a
;; PreToolUse call); only `true` counts, a missing or false field is a foreground call (#109).
;; A Monitor call reaches the Bash-matched hooks, the guard among them, as a Bash call: tool_name "Bash", tool_input
;; {command, description}, no run_in_background (seen live in the friction log, #109). Nothing in the guard's input tells
;; it from a Bash call, so there is no Monitor clause here; the waiting rule below tells them apart by the command.
(defn -main
  [input]
  (let [command     (when (map? input)
                      (get-in input ["tool_input" "command"]))
        background? (when (map? input)
                      (true? (get-in input ["tool_input" "run_in_background"])))]
    (try
      (when (instance? Exception input)
        (throw input)) ; the hook's input was no JSON
      (when (and (string? command) (not (str/blank? command)))
        (judge (loop-marked (statements/expand (tokenizer/tokenize command))) background?))
      (catch Exception e
        (binding [*out* *err*]
          (println "Refused: the delivery guard failed, so the command is held back (ADR-0001, rule 3):"
                   (ex-message e)
                   "— report it to the owner; the guard is .shared/claude/hooks/guard.clj"))
        (System/exit 2)))))
