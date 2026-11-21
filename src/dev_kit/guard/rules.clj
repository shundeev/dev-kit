(ns dev-kit.guard.rules
  "The guard's rules: what is refused, what is asked about, as a pure `judge` of a command's statements and facts,
  and the hook's entry point (#141, #139 step 6)."
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.guard.statements :as statements]
   [dev-kit.guard.tokenizer :as tokenizer]))


;; ------------------------------------------------------------------ rules


(def issue-branch? #(boolean (re-matches #"[0-9].*-.*" (or % ""))))


(defn resolve-dir
  "A directory word with a leading ~, $NAME and ${NAME} replaced by the value of a NAME=value earlier in the
  command (vars), else of env (#124). Plain substitution, nothing is run. {:unresolved word} when
  it holds $(…) or a backtick, or a variable has no value: the session's folder is never taken for it."
  [env vars word]
  (let [word  (str word)
        value #(or (get vars %) (get env %))
        named #"\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?"]
    (if (or (re-find #"`|\$\(" word) (not-every? value (map second (re-seq named word))))
      {:unresolved word}
      (-> word
          (str/replace #"^~(?=/|$)" (str/re-quote-replacement (get env "HOME")))
          (str/replace named (comp value second))))))


(defn loop-marked
  "The statements, each marked (metadata :in-loop) when it sits in a loop body: from a statement that starts with
  `do` to the matching `done`."
  [statements]
  (loop [[statement & more] statements
         depth 0
         out   []]
    (if-not statement
      out
      (let [first-text (:text (first statement))
            depth      (cond (= first-text "done") (max 0 (dec depth))
                             (= first-text "do")   (inc depth)
                             :else                 depth)]
        (recur more depth (conj out (with-meta statement {:in-loop (pos? depth)})))))))


(def ^:private start ".shared/bin/kit start")


(defn- deny [rule reason] {:decision :deny :rule rule :reason reason})


(defn- ask [rule reason] {:decision :ask :rule rule :reason reason})


(defn- guard-off
  [described _]
  (when (some #(str/includes? (:text %) "DELIVERY_GUARD=off") (:statement described))
    (ask
     :guard-off
     "DELIVERY_GUARD=off is set: the delivery guard is being bypassed on purpose. Approve only if you asked for this raw command.")))


(defn- push-refusal
  "The decision on one ref of a forced push, or nil to let it pass: refused when its target is not an issue branch
  (:forced-push-branch, #38), when the open pull request of the issue branch holds an approval, which a rewrite
  dismisses (:forced-push-approved, #117), or when the push's directory cannot be resolved and the target is HEAD or
  an issue branch (:forced-push-dir, #124). A push to HEAD is judged by the branch checked out in dir. Only a target
  that is an issue branch reads GitHub, in the push's repository (#99), through the facts' :review-of; when it
  cannot be read the push passes."
  [facts dir refspec]
  (let [target (-> refspec
                   (str/replace #"^\+" "")
                   (str/replace #"^.*:" "")
                   (str/replace #"^refs/heads/" ""))
        target (if (and (= target "HEAD") (string? dir))
                 (or ((:branch-of facts) dir) "HEAD")
                 target)]
    (cond
      (and (map? dir) (or (= target "HEAD") (issue-branch? target)))
      (deny
       :forced-push-dir
       (format
        "Refused: cannot tell which repository this forced push rewrites (`%s` has no value the guard can read here: it is not set in this command, or it comes from a command).\n\nRun it as `git -C <absolute path> push …`, so the repository is named in the command itself (#124)."
        (:unresolved dir)))

      (not (issue-branch? target))
      (deny
       :forced-push-branch
       (format
        "Refused: a forced push to '%s', which is not an issue branch. The agent rewrites\nonly its own issue branches, named <number>-<slug> (#38); main is also protected on the server.\n\nPush without rewriting, or rewrite the issue branch the work is on."
        (if (str/blank? target)
          "the branches matching on both sides"
          target)))

      (= "APPROVED" ((:review-of facts) dir target))
      (deny
       :forced-push-approved
       (format
        "Refused: a forced push to '%s', whose pull request holds an approval. A rewrite dismisses it, and the owner must approve again (#117).\n\nBring the branch up to date without rewriting it: gh pr update-branch (a merge keeps the approval).\nIf the branch must be rewritten, say so to the owner first."
        target)))))


(defn- forced-push
  "A forced push (--force, --force-with-lease, --force-if-includes, -f among short flags, or a +refspec) is judged
  ref by ref by `push-refusal`, in the directory the push runs in: the session's, a `cd`'s, or `git -C`'s."
  [described facts]
  (when (= (:head described) "git")
    (let [[dir args] (loop [dir (:dir described)
                            [option & more] (rest (:texts described))]
                       (cond (= option "-C") (recur (resolve-dir (:env facts) (:vars described) (first more))
                                                    (rest more))
                             ;; git's global options before the command, some with a value
                             (#{"-c" "--git-dir" "--work-tree" "--namespace" "--super-prefix" "--config-env"} option)
                             (recur dir (rest more))
                             (= option "push") [dir more]
                             (and option (str/starts-with? option "-")) (recur dir more)
                             :else [dir nil]))]
      (when args
        (let [refs    (rest (remove #(str/starts-with? % "-") args))
              forced? (or (some #(re-matches #"--force(-with-lease(=.*)?|-if-includes)?|-[A-Za-z]*f[A-Za-z]*" %) args)
                          (some #(str/starts-with? % "+") refs))]
          (when forced?
            (some #(push-refusal facts dir %)
                  (if (seq refs)
                    refs
                    ["HEAD"]))))))))


(defn- foreground-wait
  "A statement that waits for one event while the call is not in the background (#109): gh pr checks --watch,
  gh run watch, kit merge, kit checks, kit review --wait (also inside a loop), and a sleep that is not in a loop body. A sleep in a loop
  is a poll loop, Monitor's normal use. The guard cannot tell a foreground `until …; do sleep 1; done` from a Monitor
  poll loop (a Monitor call arrives as a Bash call), so that one passes; the skill's rule in words covers it: a wait
  for one event runs as a background Bash command."
  [described facts]
  (let [{:keys [head sub verb texts]} described
        base #(some-> % (str/replace #".*/" ""))
        kit  (if (= (base head) "bb")
               (rest texts)
               texts)]
    (when (and (not (:background? facts))
               (or (and (= (base head) "sleep") (not (:in-loop? described)))
                   (and (= (base head) "gh") (= sub "run") (= verb "watch"))
                   (and (= (base head) "gh") (= sub "pr") (= verb "checks") (some #{"--watch" "-w"} texts))
                   (and (= (base (first kit)) "kit")
                        (or (#{"merge" "checks"} (second kit))
                            (and (= "review" (second kit)) (some #{"--wait"} texts))))))
      (deny
       :foreground-wait
       "Refused: this command waits. Run it as a background command (run_in_background); you are woken when it exits (#109)."))))


(defn- zsh-rule
  "The zsh rules (#64). A statement that needs an unquoted $v split into words is refused, since zsh does not split.
  A $v as the command word is refused only when vars holds a value for it with whitespace: one word, or a value the
  command does not give, runs. The word after `env` is refused whenever it is a variable: it is a list of words to
  split. Then a `$v:h`-style word, which zsh reads as a modifier."
  [described facts]
  (when (= "zsh" (:shell facts))
    (let [{:keys [statement words texts vars]} described
          variable?           (fn [word]
                                (boolean
                                 (and word
                                      (:var word)
                                      (re-matches
                                       #"\$(\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*)"
                                       (:text word)))))
          command-word        (first words)
          command-is-variable? (variable? command-word)
          set-from-variable?  (and (= (first texts) "set") (= (second texts) "--") (variable? (get words 2)))
          loop-over-variable? (and (= (first texts) "for") (= (get texts 2) "in") (variable? (get words 3)))
          env-from-variable?  (and command-is-variable?
                                   (some #(= "env" (:text %)) (take (- (count statement) (count words)) statement)))
          command-with-spaces? (and command-is-variable?
                                    (re-find #"\s"
                                             (get vars (re-find #"[A-Za-z_][A-Za-z0-9_]*" (:text command-word)) "")))
          modified-word       (some #(when (:mod %) %) statement)]
      (cond
        (or set-from-variable? loop-over-variable? env-from-variable? command-with-spaces?)
        (deny
         :zsh-split
         (format
          "Refused: zsh does not split an unquoted variable into words, so '%s' would get one word where it needs several.\n\nSplit it explicitly: ${=v} in zsh (set -- ${=t}; for x in ${=list}), use an array,\nor run the loop in a bash script (bash script.sh), where words split as written."
          (:text described)))

        modified-word
        (deny
         :zsh-modifier
         (format
          "Refused: in zsh '%s' holds `$v:h`-style text, which zsh reads as a modifier (:h the head of a path, :t its tail, and so on), also inside double quotes, not as `$v` followed by a colon and text.\n\nWrite ${v}:h for the text, or ${v:h} for the modifier."
          (:text modified-word)))))))


(defn- option-value
  "The value of an option in a command's words, the last one given: `--flag value`, `--flag=value`, `-f value`,
  `-f=value` and `-fvalue`. Nil when it is not there or has no value after it."
  [words long-flag short-flag]
  (->> (partition-all 2 1 words)
       (keep (fn [[word next-word]]
               (cond (#{long-flag short-flag} word)     next-word
                     (str/starts-with? word (str long-flag "=")) (subs word (inc (count long-flag)))
                     (str/starts-with? word short-flag) (str/replace (subs word (count short-flag)) #"^=" ""))))
       last))


(defn- gh-api-write
  "What a `gh api` call would change (#54): a method other than GET, fields without a method (gh then sends
  POST), a GraphQL mutation, or a GraphQL query read from a file. Nothing for a read."
  [described]
  (let [texts  (:texts described)
        {:keys [fields endpoint]}
        (loop [[word & more] texts
               found {}]
          (cond
            (nil? word)
            found

            (#{"-X" "--method"} word)
            (recur (rest more) found)

            (#{"-f" "-F" "--field" "--raw-field" "--input"} word)
            (recur (rest more) (assoc found :fields true))

            (#{"-H" "--header" "-q" "--jq" "-t" "--template" "--hostname" "--cache" "-p" "--preview"} word)
            (recur (rest more) found)

            (or (str/starts-with? word "-") (#{"gh" "api"} word) (str/ends-with? word "/gh"))
            (recur more found)

            :else
            (recur more (update found :endpoint #(or % word)))))
        method (some-> (option-value texts "--method" "-X") str/upper-case)
        why    (cond
                 method
                 (when (not= method "GET")
                   (str method " " endpoint))

                 (not= endpoint "graphql")
                 (when fields
                   (str "POST " endpoint ": fields make gh send POST"))

                 (some #(str/includes? % "mutation") texts)
                 "a GraphQL mutation"

                 (some #(str/starts-with? % "query=@") texts)
                 "a GraphQL query read from a file")]
    (when why
      (ask
       :gh-api-write
       (format
        "This gh api call can change GitHub (%s). Approve it only if it should. A gh api call that only reads runs without asking (#54)."
        why)))))


(defn- gh-pr-create
  "The branch of the pull request: the one --head (or -H) names, without a fork's owner: prefix, and when there is
  none, the one checked out in the directory the command runs in (ADR-0006, #126). -R names the repository and
  changes nothing here."
  [described facts]
  (let [dir (:dir described)
        branch-named-by-head (let [value (option-value (:texts described) "--head" "-H")]
                               (when (and (seq value) (not (str/starts-with? value "-")))
                                 (str/replace value #"^[^:]*:" "")))
        branch-in-dir (when-not (or branch-named-by-head (map? dir))
                        ((:branch-of facts) dir))]
    (if (and (not branch-named-by-head) (map? dir))
      (deny
       :pr-create-unknown-branch
       (format
        "Refused: cannot tell which branch this pull request is from (`%s` has no value the guard can read here: it is not set in this command, or it comes from a command).\n\nRun it from the worktree with a literal path (cd <absolute path> && gh pr create …), or name the branch: gh pr create --head <number>-<slug> (#126)."
        (:unresolved dir)))
      (when-not (issue-branch? (or branch-named-by-head branch-in-dir))
        (deny
         :pr-create-no-issue
         (format
          "Refused: %s, which carries no issue number, so this pull request would have no issue and no board entry behind it.\n\nGet the issue and its branch first:\n  %s --title \"...\" --priority High --status \"In progress\"\nor, when the issue already exists:\n  gh issue develop <n> -R %s --checkout --base %s\n\nFrom a <number>-<slug> branch, commit, push, open the PR and set Status in one step:\n  .shared/bin/kit finish --add <path> --pr-body-file <path>\n\nIf the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off."
          (if branch-named-by-head
            (str "--head names '" branch-named-by-head "'")
            (str "HEAD is '" (or branch-in-dir "unknown") "'"))
          start
          (:repo facts)
          (:base facts)))))))


(defn- gh-rule
  "`gh api` writes, a raw `gh issue create`, and `gh pr create` from a branch with no issue."
  [described facts]
  (when (some-> (:head described) (#(or (= % "gh") (str/ends-with? % "/gh"))))
    (case [(:sub described) (:verb described)]
      ["issue" "create"]
      (deny
       :gh-issue-create
       (format
        "Refused: a raw `gh issue create` produces an issue that is on no board, with no Status and no Priority.\n\nUse the flow — invoke the `repo-task-delivery` skill, or run its GitHub step directly:\n  %s --title \"...\" --body-file <path> --priority <Urgent|High|Medium|Low> --status \"In progress\"\n\nThat one call creates the issue (or reuses one with the same title), puts it on the board,\nsets Status, Priority and Showcase, and creates the issue branch with `gh issue develop`.\n\nIf the owner asked for the raw command, re-run it prefixed with DELIVERY_GUARD=off."
        start))

      ["pr" "create"]
      (gh-pr-create described facts)

      (when (= "api" (:sub described))
        (gh-api-write described)))))


(def rules
  "The rules after the DELIVERY_GUARD=off check, in the order they are tried: the first that fires decides."
  [forced-push foreground-wait zsh-rule gh-rule])


(defn- describe
  "A statement read for the rules: its words from the command on (without assignments, keywords and wrappers),
  their texts, head, sub-command and verb, the folder and the variables it runs with, whether it sits in a loop
  and whether it only asks for documentation: reading a command's documentation is not running it, and a quoted
  --help is a value, not that flag."
  [statement place]
  (let [words (vec (statements/strip-head statement))
        texts (mapv :text words)]
    {:statement statement
     :words     words
     :texts     texts
     :head      (first texts)
     :sub       (second texts)
     :verb      (get texts 2)
     :text      (str/join " " texts)
     :dir       (:dir place)
     :vars      (:vars place)
     :in-loop?  (:in-loop (meta statement))
     :help?     (boolean (some #(and (not (:quoted %)) (#{"--help" "-h"} (:text %))) words))}))


(defn- decide
  "The decision on a described statement: DELIVERY_GUARD=off asks first; unless the statement only asks for
  documentation, the first rule that fires decides."
  [described facts]
  (or (guard-off described facts)
      (when-not (:help? described)
        (some #(% described facts) rules))))


(defn- move-on
  "The place after a statement: the folder after a `cd` to a resolvable absolute path or to an unresolvable word
  (a `cd` in a documentation request, or to a relative path, leaves it), and the variables after the NAME=value
  words that open the statement or follow `export`; a value that cannot be resolved leaves its name unset."
  [described facts]
  (let [{:keys [statement head sub dir vars]} described
        target      (when (and (= head "cd") sub (not (:help? described)))
                      (resolve-dir (:env facts) vars sub))
        assignments (take-while #(statements/assignment? (:text %))
                                (cond-> statement (= "export" (:text (first statement))) rest))]
    {:dir  (if (or (map? target) (some-> target (str/starts-with? "/")))
             target
             dir)
     :vars (reduce (fn [vars assignment]
                     (let [[_ variable value] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" (:text assignment))
                           value (resolve-dir (:env facts) vars value)]
                       (if (string? value)
                         (assoc vars variable value)
                         (dissoc vars variable))))
                   vars
                   assignments)}))


(defn judge
  "What the delivery guard decides on a command: nil to let it run, else {:decision :deny|:ask :rule keyword
  :reason text}. statements are the command's (marked by loop-marked). facts: :background?, :shell (\"zsh\"),
  :env (a map of the environment), :cwd, :base and :repo (for the texts), and the two reads that need a process,
  called only by the rule that needs them: :branch-of (fn [dir] the branch checked out in dir, or nil) and
  :review-of (fn [dir branch] the review decision of the branch's open pull request, or nil)."
  [statements facts]
  (loop [[statement & more] statements
         place {:dir (or (:cwd facts) ".") :vars {}}]
    (when statement
      (let [described (describe statement place)]
        (or (decide described facts)
            (recur more (move-on described facts)))))))


(defn decision-json
  "What the hook prints for a decision: the JSON Claude Code reads (and managed/pretooluse.sh and `kit hook`)."
  [{:keys [decision reason]}]
  (json/generate-string {:hookSpecificOutput {:hookEventName      "PreToolUse"
                                              :permissionDecision (name decision)
                                              :permissionDecisionReason reason}}))


(defn- stdout-in
  "A command's stdout in directory dir (nil: this one), or nil when it fails."
  [dir & args]
  (let [{:keys [exit out]} (apply process/sh (cond-> {:continue true} dir (assoc :dir dir)) args)]
    (when (zero? exit)
      (str/trim out))))


;; Claude Code sets run_in_background on a Bash call (https://code.claude.com/docs/en/hooks, the tool_input of a
;; PreToolUse call); only `true` counts, a missing or false field is a foreground call (#109).
;; A Monitor call reaches the Bash-matched hooks, the guard among them, as a Bash call: tool_name "Bash", tool_input
;; {command, description}, no run_in_background (seen live in the friction log, #109). Nothing in the guard's input tells
;; it from a Bash call, so there is no Monitor clause here; the foreground-wait rule above tells them apart by the command.
(defn answer
  "The guard's answer to the hook's input (its parsed JSON): the JSON Claude Code reads, as text, or nil when no rule
  speaks. A failure of the guard throws; the hook's entry, claude/hooks/guard.clj, then holds the command back
  (ADR-0001, rule 3)."
  [input]
  (let [command     (get-in input ["tool_input" "command"])
        background? (true? (get-in input ["tool_input" "run_in_background"]))]
    (when (and (string? command) (not (str/blank? command)))
      (let [env   (System/getenv)
            ;; the facts of this process: its environment, and the two reads run for real, git and
            ;; gh in the given directory (#99)
            facts {:background? background?
                   :shell       (some-> (get env "SHELL") (str/replace #".*/" ""))
                   :env         env
                   :cwd         "."
                   :base        (or (get env "GHWF_DEFAULT_BASE") "main")
                   :repo        (or (get env "GHWF_REPO") "<owner/repo>")
                   :branch-of   (fn [dir]
                                  (stdout-in nil "git" "-C" dir "symbolic-ref" "--short" "-q" "HEAD"))
                   :review-of   (fn [dir branch]
                                  (stdout-in dir
                                             "gh"
                                             "pr"      "list"
                                             "--head"  branch
                                             "--state" "open"
                                             "--json"  "reviewDecision"
                                             "--jq"    ".[0].reviewDecision // empty"))}]
        (some-> (judge (loop-marked (statements/expand (tokenizer/tokenize command))) facts)
                decision-json)))))
