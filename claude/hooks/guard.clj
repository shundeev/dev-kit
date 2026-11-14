#!/usr/bin/env bb
;; PreToolUse(Bash). The delivery flow is not optional — see AGENTS.md, "Delivery" (#93, ADR-0005).
;;
;; Refuses the commands that create work GitHub can see but the project board cannot. The
;; gh-project-workflow scripts pass untouched by construction: this hook sees only the command the agent
;; itself submitted, never the `gh` calls a script makes in a subprocess. Running the wrapper is the escape
;; hatch, and it needs no marker.
;;
;; Gate on the invariant ("an issue exists for this work"), not on the verb: `gh pr create` is correct from
;; an issue branch and wrong from the base branch.
;;
;; The command is read by a tokenizer, not by patterns: quoted text stays one word, heredoc bodies are
;; skipped by structure, and commands inside $(…), `…`, `bash -c "…"` and `eval` are judged as statements of
;; their own.
;;
;; Known gaps. It judges only the command text the agent submits:
;; - a command run inside a script file, or by another tool, is not seen;
;; - a push to main is left to the server's ruleset and the settings' deny rules; a forced push is judged here
;;   only for its target branch (#38);
;; - `gh api` is judged by its method and fields (#54), not by what the endpoint does.
;;
;; The bash entry (managed/pretooluse.sh) refuses when bb is missing, and an error here refuses too (exit 2):
;; the guard fails closed (ADR-0001, rule 3).
(ns guard
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))


;; ------------------------------------------------------------------ fast exit


;; Most commands touch no rule. bb reads the forms of this file one by one, so a command whose text holds none
;; of the words a rule needs leaves here, before the rest is read: the hook then costs what bb's start does.
;; Quotes and backslashes are dropped first, so `g\h` or `g''h` does not slip past.
(def run? (= *file* (System/getProperty "babashka.file")))


(def input (when run? (try (json/parse-string (slurp *in*)) (catch Exception e e))))


(def command (when (map? input) (get-in input ["tool_input" "command"])))


;; Claude Code sets run_in_background on a Bash call (https://code.claude.com/docs/en/hooks, the tool_input of a
;; PreToolUse call); only `true` counts, a missing or false field is a foreground call (#109).
(def background? (when (map? input) (true? (get-in input ["tool_input" "run_in_background"]))))


(when (and run? (map? input))
  (let [bare (str/replace (str command) #"[\\'\"]" "")
        zsh? (= "zsh" (some-> (System/getenv "SHELL") (str/replace #".*/" "")))]
    (when-not (or (str/includes? bare "gh")
                  (str/includes? bare "DELIVERY_GUARD")
                  (str/includes? bare "sleep")
                  (str/includes? bare "kit")
                  ;; a forced push: --force…, -f (alone or among short flags), +refspec (#117)
                  (and (str/includes? bare "git") (re-find #"force| -[A-Za-z]*f| \+\S" bare))
                  (and zsh? (str/includes? bare "$")))
      (System/exit 0))))


(require '[babashka.process :as p])


;; ------------------------------------------------------------------ tokenizer


(def ^:private name-start? #(or (Character/isLetter (char %)) (= % \_)))


(declare tokenize)


(defn- read-balanced
  "From i, just after `$(`, to the matching `)`. Returns [content index-after]. Quotes and escapes inside are
  skipped, so a `)` in them does not close it; neither does the `)` after a pattern of a `case … esac`."
  [^String s i]
  (let [n (count s)]
    (loop [j     i
           depth 1
           q     nil
           cases 0
           w     ""]
      (if (>= j n)
        [(subs s i n) n]
        (let [c (.charAt s j)
              ;; a bare word just ended: count `case` against `esac`
              [cases w] (if (or (Character/isLetter c) (= c \_))
                          [cases (str w c)]
                          [(case w
                             "case" (inc cases)
                             "esac" (max 0 (dec cases))
                             cases) ""])]
          (cond
            (and (= c \\) (not= q \')) (recur (+ j 2) depth q cases "")
            q        (recur (inc j) depth (if (= c q) nil q) cases "")
            (#{\' \"} c) (recur (inc j) depth c cases "")
            (= c \() (recur (inc j) (inc depth) q cases "")
            (= c \)) (cond (pos? cases) (recur (inc j) depth q cases "")
                           (= depth 1)  [(subs s i j) (inc j)]
                           :else        (recur (inc j) (dec depth) q cases ""))
            :else    (recur (inc j) depth q cases w)))))))


(defn- read-backtick
  "From i, just after a backtick, to the closing one. Returns [content index-after]."
  [^String s i]
  (let [n (count s)]
    (loop [j i]
      (cond (>= j n) [(subs s i n) n]
            (= (.charAt s j) \\) (recur (+ j 2))
            (= (.charAt s j) \`) [(subs s i j) (inc j)]
            :else (recur (inc j))))))


(defn- skip-heredocs
  "At the start of a line, after the line that opened them: skips each heredoc's body up to its terminator
  line, a CRLF line end included. Returns the index after the last terminator."
  [^String s i heredocs]
  (reduce (fn [j {:keys [term strip]}]
            (loop [j j]
              (if (>= j (count s))
                j
                (let [e    (let [k (.indexOf s "\n" (int j))]
                             (if (neg? k)
                               (count s)
                               k))
                      line (str/replace (subs s j e) #"\r$" "")
                      line (if strip
                             (str/replace line #"^\t+" "")
                             line)]
                  (if (= line term)
                    (min (count s) (inc e))
                    (recur (inc e)))))))
          i
          heredocs))


(defn tokenize
  "The statements of a shell command, each a vector of words {:t text, :var true when it holds an unquoted
  $name or ${name}, :q true when any of it was quoted or escaped}. Statements end at && || ; | & ( ) newlines
  and the words { }. Redirections and their targets are dropped, heredoc bodies skipped. Commands inside $(…)
  and `…` follow as statements of their own."
  [^String s]
  (let [n (count s)]
    (loop [i        0
           word     nil
           fl       #{}
           drop?    false
           words    []
           out      []
           nested   []
           heredocs []]
      (let [end-word (fn [words]
                       (cond (nil? word) words
                             drop?       words
                             :else       (conj words {:t word :var (contains? fl :var) :q (contains? fl :q)})))
            end-stmt (fn [out words]
                       (let [ws (end-word words)]
                         (if (seq ws)
                           (conj out ws)
                           out)))
            more     (fn [i] (when (< i n) (.charAt s i)))
            quoted   (conj fl :q)]
        (if (>= i n)
          (into (end-stmt out words) nested)
          (let [c (.charAt s i)]
            (cond
              ;; a newline ends the statement; heredoc bodies opened on this line are skipped
              (= c \newline)
              (recur (skip-heredocs s (inc i) heredocs) nil #{} false [] (end-stmt out words) nested [])

              ;; a redirection's target may follow after blanks: it is still dropped
              (#{\space \tab \return} c)
              (recur (inc i) nil #{} (and drop? (nil? word)) (end-word words) out nested heredocs)

              (and (= c \#) (nil? word))
              (let [k (.indexOf s "\n" (int i))] (recur (if (neg? k) n k) nil #{} false words out nested heredocs))

              (= c \\)
              (if (= (more (inc i)) \newline)
                (recur (+ i 2) word fl drop? words out nested heredocs)
                (recur (+ i 2) (str word (or (more (inc i)) "")) quoted drop? words out nested heredocs))

              (= c \')
              (let [k (.indexOf s "'" (int (inc i)))
                    k (if (neg? k) n k)]
                (recur (min n (inc k)) (str word (subs s (inc i) k)) quoted drop? words out nested heredocs))

              ;; $'…', ANSI-C quoting: a backslash escapes, so \' does not end it
              (and (= c \$) (= (more (inc i)) \'))
              (let [[text j] (loop [j    (+ i 2)
                                    text ""]
                               (cond (>= j n) [text n]
                                     (= (.charAt s j) \\) (recur (+ j 2) (str text (or (more (inc j)) "")))
                                     (= (.charAt s j) \') [text (inc j)]
                                     :else (recur (inc j) (str text (.charAt s j)))))]
                (recur j (str word text) quoted drop? words out nested heredocs))

              (= c \")
              (let [[text j inner]
                    (loop [j     (inc i)
                           text  ""
                           inner []]
                      (if (>= j n)
                        [text j inner]
                        (let [d (.charAt s j)]
                          (cond
                            (= d \") [text (inc j) inner]
                            (and (= d \\) (#{\" \\ \$ \` \newline} (more (inc j))))
                            (recur (+ j 2)
                                   (if (= (more (inc j)) \newline)
                                     text
                                     (str text (more (inc j))))
                                   inner)
                            (and (= d \$) (= (more (inc j)) \())
                            (let [[sub k] (read-balanced s (+ j 2))] (recur k (str text "$(" sub ")") (conj inner sub)))
                            (= d \`)
                            (let [[sub k] (read-backtick s (inc j))] (recur k (str text "`" sub "`") (conj inner sub)))
                            :else (recur (inc j) (str text d) inner)))))]
                (recur j (str word text) quoted drop? words out (into nested (mapcat tokenize inner)) heredocs))

              (and (= c \$) (= (more (inc i)) \())
              (let [[sub j] (read-balanced s (+ i 2))]
                (recur j (str word "$(" sub ")") fl drop? words out (into nested (tokenize sub)) heredocs))

              (= c \`)
              (let [[sub j] (read-backtick s (inc i))]
                (recur j (str word "`" sub "`") fl drop? words out (into nested (tokenize sub)) heredocs))

              (and (= c \$) (some-> (more (inc i)) int (#(or (name-start? %) (= % (int \{))))))
              (recur (inc i) (str word c) (conj fl :var) drop? words out nested heredocs)

              ;; redirections: an fd number before them goes, their target goes, a heredoc opens
              (#{\< \>} c)
              (let [words (if (and word (re-matches #"\d+" word))
                            words
                            (end-word words))
                    op    (re-find #"^(<<<|<<-|<<|>>|>&|<&|>\||&>>|&>|<>|>|<)" (subs s i))
                    j     (+ i (count (first op)))]
                (case (first op)
                  ("<<" "<<-")
                  ;; the delimiter is one word, quoted or not: it ends at a blank or an operator
                  (let [[_ ws d] (re-find #"^([ \t]*)((?:'[^']*'|\"[^\"]*\"|\\.|[^\s;|&<>()'\"\\])+)" (subs s j))
                        term     (str/replace (or d "") #"['\"\\]" "")]
                    (recur (+ j (count (or ws "")) (count (or d "")))
                           nil
                           #{}
                           false
                           words
                           out
                           nested
                           (conj heredocs {:term term :strip (= (first op) "<<-")})))
                  (">&" "<&")
                  (if-let [t (re-find #"^[0-9-]+" (subs s j))]
                    (recur (+ j (count t)) nil #{} false words out nested heredocs)
                    (recur j nil #{} true words out nested heredocs))
                  (recur j nil #{} true words out nested heredocs)))

              (and (= c \&) (= (more (inc i)) \>))
              (let [j (if (= (more (+ i 2)) \>)
                        (+ i 3)
                        (+ i 2))]
                (recur j nil #{} true (end-word words) out nested heredocs))

              (#{\; \& \| \( \)} c)
              (let [j (if (and (#{\& \|} c) (= (more (inc i)) c))
                        (+ i 2)
                        (inc i))
                    j (if (and (= c \|) (= (more j) \&))
                        (inc j)
                        j)]
                (recur j nil #{} false [] (end-stmt out words) nested heredocs))

              :else
              (let [word' (str word c)]
                (if (and (#{"{" "}"} word') (#{\space \tab \newline \; nil} (more (inc i))) (empty? (end-word words)))
                  (recur (inc i) nil #{} false [] (end-stmt out words) nested heredocs)
                  (recur (inc i) word' fl drop? words out nested heredocs))))))))))


;; ------------------------------------------------------------------ statements


(def wrapper-args
  "The wrappers a statement may start with, each with its options that take a value."
  {"sudo"    #{"-u" "-g" "-h" "-p" "-C" "-D" "-R" "-T" "-U" "-r" "-t" "--user" "--group" "--host" "--prompt"
               "--chdir" "--chroot" "--close-from" "--role" "--type" "--other-user" "--command-timeout"}
   "env"     #{"-u" "--unset" "-C" "--chdir" "-S" "--split-string"}
   "time"    #{"-f" "--format" "-o" "--output"}
   "exec"    #{"-a"}
   "command" #{}
   "nohup"   #{}})


(def keywords "Shell words that may stand before a command." #{"then" "do" "else" "elif" "if" "while" "until" "!"})


(def assignment? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %))


(defn strip-head
  "The words of a statement from its command on: without leading assignments, shell keywords (then, do, if,
  while, !, function f) and wrappers with their options (sudo -u bob, env -u X A=1, time -p, …)."
  [words]
  (loop [ws words]
    (let [t (:t (first ws))]
      (cond
        (nil? t)         ws
        (assignment? t)  (recur (rest ws))
        (keywords t)     (recur (rest ws))
        (= t "function") (recur (drop 2 ws))
        (wrapper-args t) (recur (loop [ws (rest ws)]
                                  (let [o (:t (first ws) "")]
                                    (cond ((wrapper-args t) o) (recur (drop 2 ws))
                                          (and (str/starts-with? o "-") (not= o "-")) (recur (rest ws))
                                          (and (= t "env") (assignment? o)) (recur (rest ws))
                                          :else ws))))
        :else            ws))))


(def shells #{"bash" "sh" "zsh" "dash"})


(defn expand
  "Statements, with the strings of `bash -c …` (-lc, -ec and the like; sh, zsh, dash) and `eval …` read as
  statements of their own."
  [statements]
  (mapcat (fn [st]
            (let [ws    (strip-head st)
                  head  (some-> (:t (first ws)) (str/replace #".*/" ""))
                  ts    (map :t ws)
                  inner (cond
                          (shells head)   (some->> (drop-while #(not (re-matches #"-[A-Za-z]*c[A-Za-z]*" %)) (rest ts))
                                                   second
                                                   vector)
                          (= head "eval") [(str/join " " (rest ts))]
                          :else           nil)]
              (cons st (mapcat (comp expand tokenize) inner))))
   statements))


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
    (when (zero? exit) (str/trim out))))


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
          (take-while #(assignment? (:t %)) (cond-> st (= "export" (:t (first st))) rest))))


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
      method (when (not= method "GET") (str method " " endpoint))
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


(defn waits?
  "A statement that waits: sleep, gh pr checks --watch, gh run watch, kit merge, kit checks (#109)."
  [ts]
  (let [[head sub verb] ts
        base #(some-> % (str/replace #".*/" ""))
        kit  (if (= (base head) "bb")
               (rest ts)
               ts)]
    (boolean
     (or (= (base head) "sleep")
         (and (= (base head) "gh") (= sub "run") (= verb "watch"))
         (and (= (base head) "gh") (= sub "pr") (= verb "checks") (some #{"--watch" "-w"} ts))
         (and (= (base (first kit)) "kit") (#{"merge" "checks"} (second kit)))))))


(defn judge
  [statements]
  (let [base   (or (System/getenv "GHWF_DEFAULT_BASE") "main")
        repo   (or (System/getenv "GHWF_REPO") "<owner/repo>")
        start  ".shared/bin/kit start"
        finish ".shared/bin/kit finish"
        zsh?   (= "zsh" (some-> (System/getenv "SHELL") (str/replace #".*/" "")))
        branch (delay (sh-out "git" "rev-parse" "--abbrev-ref" "HEAD"))]
    (loop [[st & more] statements
           wd          "."
           vars        {}]
      (when st
        (when (some #(str/includes? (:t %) "DELIVERY_GUARD=off") st)
          (decide
           "ask"
           "DELIVERY_GUARD=off is set: the delivery guard is being bypassed on purpose. Approve only if you asked for this raw command."))
        (let [ws   (vec (strip-head st))
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
              (when (and (not background?) (waits? ts))
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
                    (let [named  (head-branch ts)
                          source (or named @branch)]
                      (when-not (issue-branch? source)
                        (decide
                         "deny"
                         (str
                          "Refused: "
                          (if named
                            (str "--head names '" named "'")
                            (str "HEAD is '" (or @branch "unknown") "'"))
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
                     (let [d (when (and (= head "cd") sub) (resolve-dir vars sub))]
                       (if (or (map? d) (some-> d (str/starts-with? "/")))
                         d
                         wd))
                     (assigned vars st)))))))))


(defn -main
  []
  (try
    (when (instance? Exception input) (throw input)) ; the hook's input was no JSON
    (when (and (string? command) (not (str/blank? command)))
      (judge (expand (tokenize command))))
    (catch Exception e
      (binding [*out* *err*]
        (println "Refused: the delivery guard failed, so the command is held back (ADR-0001, rule 3):"
                 (ex-message e)
                 "— report it to the owner; the guard is .shared/claude/hooks/guard.clj"))
      (System/exit 2))))


(when run?
  (-main))
