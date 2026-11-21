;; The friction log, read: time, command shapes, ask rules and the per-session friction (see bin/retro.clj).
(ns dev-kit.retro.log
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dev-kit.problem :as problem])
  (:import
   (java.time Duration Instant OffsetDateTime)
   (java.util.regex Pattern)))


;; ------------------------------------------------------------------ time


(defn parse-time
  "A time given on the command line, in UTC or with an offset."
  [option text]
  (or (try (Instant/parse text) (catch Exception _ nil))
      (try (.toInstant (OffsetDateTime/parse text)) (catch Exception _ nil))
      (problem/fail! :retro/bad-time
                     (str "retro: " option " " text " : not a time")
                     {:fix "write it as 2026-11-12T10:00:00Z or 2026-11-12T11:00:00+01:00"})))


(defn instant
  [text]
  (when text
    (Instant/parse text)))


(defn before? [^Instant earlier ^Instant later] (.isBefore earlier later))


(defn stamp [^Instant moment] (str/replace (subs (str moment) 0 16) "T" " "))


(defn span
  [from to]
  (let [minutes (.toMinutes (Duration/between from to))]
    (if (>= minutes 60)
      (format "%dh %02dm" (quot minutes 60) (rem minutes 60))
      (str minutes "m"))))


;; ------------------------------------------------------------------ commands
;; A shape names what a command was without its arguments ("gh pr view", "kit merge"): every
;; word a plain command word, never a path, a flag value or inline code. Segments are a command's simple
;; commands; ask rules match against them.


(def skip-words
  #{"cd" "export" "set" "unset" "echo" "printf" "sleep" "true" "false" ":" "for" "while" "until" "if" "local"
    "read" "trap" "wait" "exit" "return" "shift" "case" "esac" "done" "fi" "test" "["})


(def lead-words #{"do" "then" "else" "elif" "{" "(" "!" "time" "env" "command" "exec" "nohup"})


(def gh-value-flags
  #{"-R" "--repo" "-H" "--header" "-f" "-F" "--field" "--raw-field" "--jq" "-q" "-t" "--template" "--json" "-X"
    "--method" "--input" "-b" "--body" "--body-file" "-T" "--title" "-l" "--label" "-a" "--assignee" "-B" "--base"
    "--head" "-s" "--state" "-L" "--limit" "--hostname" "-p" "--project" "-m" "--milestone" "-r" "--reviewer" "-S"
    "--search" "-w" "--workflow" "--cache"})


(defn base
  [word]
  (let [word (str/replace word #"[\"'()]" "")]
    (if (str/blank? word)
      word
      (str (fs/file-name word)))))


(defn plain? [word] (and word (re-matches #"[a-z][a-z0-9-]*" word)))


(defn words [text] (remove str/blank? (str/split (str/trim (str text)) #"\s+")))


(defn command-words
  [segment]
  (drop-while #(or (lead-words %) (re-matches #"[A-Za-z_]\w*=.*" %)) (words segment)))


(defn split-top
  "Splits a shell command at && || ; | and newlines outside quotes."
  [^String command]
  (loop [i          0
         open-quote nil
         current    (StringBuilder.)
         parts      []]
    (if (>= i (count command))
      (conj parts (str current))
      (let [character      (.charAt command i)
            next-character (when (< (inc i) (count command))
                             (.charAt command (inc i)))
            operator-width (cond (or (and (= character \&) (= next-character \&))
                                     (and (= character \|) (= next-character \|)))
                                 2
                                 (#{\| \; \newline} character) 1)]
        (cond
          (and (= character \\) next-character (not= open-quote \'))
          (recur (+ i 2) open-quote (doto current (.append character) (.append next-character)) parts)
          open-quote (recur (inc i) (when (not= character open-quote) open-quote) (.append current character) parts)
          (#{\' \"} character) (recur (inc i) character (.append current character) parts)
          operator-width (recur (+ i operator-width) nil (StringBuilder.) (conj parts (str current)))
          :else (recur (inc i) open-quote (.append current character) parts))))))


(defn segments
  "The simple commands of a shell command: heredoc bodies dropped, $(…) bodies added."
  [command]
  (let [script (-> (str command)
                   (str/replace #"\\\n" " ")
                   (str/replace #"(?s)<<-?\s*['\"]?(\w+)['\"]?([^\n]*)\n.*?\n[ \t]*\1[ \t]*(?=\n|$)" "$2"))]
    (concat (split-top script) (mapcat #(split-top (second %)) (re-seq #"\$\(([^()]*)\)" script)))))


(defn gh-args
  "gh's words without its flags and their values."
  [arguments]
  (loop [[word & more] arguments
         kept []]
    (cond (nil? word) kept
          (gh-value-flags word) (recur (rest more) kept)
          (str/starts-with? word "-") (recur more kept)
          :else (recur more (conj kept word)))))


(defn gh-api-write?
  [arguments]
  (if-let [method (some (fn [[flag value]]
                          (cond (#{"-X" "--method"} flag)  value
                                (str/starts-with? flag "--method=") (subs flag 9)
                                (re-matches #"-X\w+" flag) (subs flag 2)))
                        (partition-all 2 1 arguments))]
    (not= "GET" (str/upper-case (str method)))
    (boolean (some #{"-f" "-F" "--field" "--raw-field" "--input"} arguments))))


(defn shape-of
  [[word & more]]
  (let [program (base word)]
    (case program
      "gh"  (let [[command subcommand :as args] (gh-args more)]
              (cond (not= command "api")     (str/join " " (cons "gh" (filter plain? (take 2 args))))
                    (= subcommand "graphql") "gh api graphql"
                    (gh-api-write? more)     "gh api (REST, write)"
                    :else                    "gh api (REST)"))
      "git" (let [subcommand (first (remove #(str/starts-with? % "-")
                                            (loop [ws more]
                                              (if (#{"-C" "-c"} (first ws))
                                                (recur (drop 2 ws))
                                                ws))))]
              (if (plain? subcommand)
                (str "git " subcommand)
                "git"))
      ("bash" "sh" "zsh" "bb" "python" "python3" "node")
      (if (#{"-e" "-c"} (first more))
        (str program " " (first more))
        (let [[script subcommand] (remove #(or (str/starts-with? % "-") (re-find #"^\d*[<>&]" %)) more)
              file (some-> script base)]
          (str/join " "
                    (remove str/blank?
                            [program (when (and file (re-matches #"[\w.+-]+" file)) file)
                             (when (and (= program "bb") (plain? subcommand)) subcommand)]))))
      program)))


(defn shapes
  "The shapes of a command's parts, setting aside cd, echo and the like; a command made only of those is named
  by its first plain word."
  [tool input]
  (if (= tool "Bash")
    (let [parts (segments (:command input))
          named (distinct (keep (fn [segment]
                                  (let [[word :as command-parts] (command-words segment)]
                                    (when (and word
                                               (not (skip-words word))
                                               (not (and (str/starts-with? word "$") (not (str/includes? word "/"))))
                                               (re-matches #"[A-Za-z][\w.+-]*" (base word)))
                                      (shape-of command-parts))))
                                parts))]
      (or (seq named) (some #(when (and (plain? %) (not= "cd" %)) [%]) (keep #(first (command-words %)) parts)) []))
    [(str tool)]))


(defn shape [tool input] (or (first (shapes tool input)) "shell"))


(defn shape-all
  [tool input]
  (let [found (shapes tool input)]
    (if (seq found)
      (str/join " + " (cond-> (vec (take 3 found)) (< 3 (count found)) (conj "…")))
      "shell")))


;; ------------------------------------------------------------------ ask rules


(defn glob-re
  "Bash(gh api *) or Read(/x/**) as a regex: * is anything; a trailing :* is a prefix too."
  [body]
  (re-pattern (str "^"
                   (str/join ".*" (map #(Pattern/quote %) (str/split (str/replace body #":\*$" "*") #"\*" -1)))
                   "$")))


(defn rule-matches?
  [tool input [_ tool-pattern body]]
  (and (re-find (glob-re tool-pattern) tool)
       (cond
         (nil? body)     true
         (= tool "Bash") (some #(re-find (glob-re body) (str/join " " (command-words %))) (segments (:command input)))
         (str/starts-with? body "domain:") (some-> (:url input) java.net.URI. .getHost (= (subs body 7)))
         :else           (some->> (some input [:file_path :notebook_path :path :url]) str (re-find (glob-re body))))))


(defn window-cause
  "The ask rule that raised a window, from the rules in force at that moment. Only a rule of plain words is
  printed whole; one naming a path or a URL prints as Tool(…)."
  [{:keys [tool input asks]}]
  (if-let [[rule tool-pattern body] (some #(let [parsed (re-matches #"([\w*-]+)(?:\((.*)\))?" %)]
                                             (when (and parsed (rule-matches? tool input parsed)) parsed))
                                          asks)]
    (str "ask rule: "
         (if (or (nil? body) (re-matches #"[\w .:*-]*" body))
           rule
           (str tool-pattern "(…)")))
    (str (shape-all tool input) " (no ask rule matches)")))


;; ------------------------------------------------------------------ the log


(defn log-dir
  []
  (str (fs/path (or (not-empty (System/getenv "XDG_STATE_HOME")) (str (System/getenv "HOME") "/.local/state"))
                "dev-kit"
                "friction")))


(defn read-log
  "Every event of the log, in order. A line that is not a whole event (a session still writing) is skipped."
  [dir]
  (vec (for [file  (sort (map str (fs/glob dir "*.jsonl")))
             line  (with-open [reader (io/reader file)] (doall (line-seq reader)))
             :let  [event (try (let [parsed (json/parse-string line true)]
                                 ;; an event without a turn id belongs to no turn
                                 (cond-> (assoc parsed :at (instant (:at parsed)))
                                   (str/blank? (str (:turn parsed))) (dissoc :turn)))
                               (catch Exception _ nil))]
             :when (and (:at event) (:session event))]
         event)))


(defn task-of
  [{:keys [branch origin]}]
  (when-let [[_ number] (some->> branch (re-matches #"(\d+)-.+"))]
    [(some-> origin str/lower-case) (parse-long number)]))


(defn runs
  "Commands sorted by time, split where neighbours are more than 10 minutes apart."
  [cmds]
  (reduce (fn [groups command]
            (let [previous (peek (peek groups))]
              (if (and previous (not (before? (.plus ^Instant (:at previous) (Duration/ofMinutes 10)) (:at command))))
                (conj (pop groups) (conj (peek groups) command))
                (conj groups [command]))))
          []
          cmds))


(defn pair
  "Calls in order, each with its window and outcome. A window goes to the first call of its turn with the same
  agent, tool and input that has neither yet."
  [events]
  (reduce (fn [calls event]
            (let [same (fn [fields] (fn [index call] (when (= (map call fields) (map event fields)) index)))]
              (case (:event event)
                "PreToolUse" (conj calls (assoc event :task (task-of event)))
                "PermissionRequest"
                (if-let [index (first (keep-indexed (fn [index call]
                                                      (when (and (nil? (:window call)) (nil? (:outcome call)))
                                                        ((same [:turn :agent :tool :input]) index call)))
                                                    calls))]
                  (assoc-in calls [index :window] event)
                  calls)
                ("PostToolUse" "PostToolUseFailure" "PermissionDenied" "HookDenied")
                (if-let [index (first (keep-indexed (same [:id]) calls))]
                  (update calls index assoc :outcome (:event event) :reason (:reason event) :done (:at event))
                  calls)
                calls)))
          []
          events))


(defn session-friction
  "The friction in one session's events, {:items [{:kind :cause :at :task (:n)}] :messages [{:at :text :task}]}."
  [events]
  (let [calls    (pair events)
        turns    (distinct (keep :turn events))
        ended    (fn [event-name]
                   (into
                    {}
                    (for [event events :when (and (:turn event) (= event-name (:event event)))] [(:turn event) event])))
        stop     (ended "Stop")
        failed   (ended "StopFailure")
        named    (memoize (fn [turn]
                            (let [tasks (set (keep :task (filter #(= turn (:turn %)) calls)))]
                              (when (= 1 (count tasks))
                                (first tasks)))))
        ;; a message, an Esc, a failed turn or a question belongs to the task the session was last working on
        latest   (loop [[turn & more] turns
                        previous      nil
                        by-turn       {}]
                   (if turn
                     (let [task (or (named turn) previous)] (recur more task (assoc by-turn turn task)))
                     by-turn))
        item     (fn [kind cause source]
                   {:kind kind :cause cause :at (:at source) :task (or (:task source) (named (:turn source)))})
        first-at (fn [turn] (:at (first (filter #(= turn (:turn %)) events))))
        dialog   #{"AskUserQuestion" "ExitPlanMode"}
        call     (fn [{:keys [tool input window outcome reason turn] :as call}]
                   (let [ran? (#{"PostToolUse" "PostToolUseFailure"} outcome)]
                     (cond-> []
                       (= tool "AskUserQuestion") (conj (item :question "AskUserQuestion" call))
                       (= tool "ExitPlanMode") (conj (item :plan (if ran? "approved" "declined") call))
                       (and window (not (dialog tool)))
                       (conj (item (if ran? :confirm-yes :confirm-no) (window-cause window) call))
                       (= outcome "HookDenied") (conj (assoc (item :refusal-hook (shape-all tool input) call)
                                                             :why
                                                             (second (re-find #"Rule: (\S+)" (str reason)))))
                       (= outcome "PermissionDenied")
                       (conj (item :refusal-classifier
                                   (str (shape-all tool input)
                                        " — "
                                        (or (second (re-find #"\[(\w+)\]" (str reason))) "classifier"))
                                   call))
                       (= outcome "PostToolUseFailure") (conj (item :tool-error (shape tool input) call))
                       (and (nil? outcome) (nil? window) (stop turn) (not (dialog tool)))
                       (conj (item :refusal-rule (shape-all tool input) call)))))
        bash     (filter #(and (= "Bash" (:tool %)) (get-in % [:input :command])) calls)]
    {:items    (concat
                (mapcat call calls)
                ;; neither Stop nor StopFailure while the session went on: the owner pressed Esc, unless on a
                ;; window, which is that window's no
                (for [turn  (butlast turns)
                      :when (and (not (stop turn))
                                 (not (failed turn))
                                 (not (some #(and (= turn (:turn %)) (:window %) (nil? (:outcome %))) calls)))]
                  {:kind :interrupt :cause "—" :at (first-at turn) :task (latest turn)})
                (for [turn (keys failed)] {:kind :turn-failed :cause "—" :at (first-at turn) :task (latest turn)})
                (for [[turn event] stop
                      :when        (:needs_input event)]
                  {:kind :question :cause "needs input in chat" :at (:at event) :task (latest turn)})
                ;; the same command, word for word, three times or more within minutes: polling or retries
                (for [[_ same] (group-by #(str/join " " (words (get-in % [:input :command]))) bash)
                      run      (runs same)
                      :when    (>= (count run) 3)]
                  (assoc (item :repeat (shape "Bash" (:input (first run))) (first run)) :n (count run))))
     :messages (for [event events
                     :when (= "UserPromptSubmit" (:event event))]
                 {:at (:at event) :text (:prompt event) :task (latest (:turn event))})
     ;; where the time went (#65): spans {:what :task :from :to}
     :time     (let [end  (fn [turn] (:at (or (stop turn) (failed turn) (last (filter #(= turn (:turn %)) events)))))
                     asks (filter #(= "UserPromptSubmit" (:event %)) events)
                     task (fn [call] (or (:task call) (named (:turn call))))]
                 (concat
                  (for [turn turns] {:what :turn :task (latest turn) :from (first-at turn) :to (end turn)})
                  ;; a window the owner answered, from the window to the run
                  (for [{:keys [window done tool] :as call} calls
                        :when (and window done (not (dialog tool)))]
                    {:what :window :task (task call) :from (:at window) :to done})
                  ;; a question: AskUserQuestion until answered; "needs input" until the owner's next message
                  (for [call  calls
                        :when (and (= "AskUserQuestion" (:tool call)) (:done call))]
                    {:what :question :task (task call) :from (:at call) :to (:done call)})
                  (for [[turn event] stop
                        :when        (:needs_input event)
                        :let         [answer (:at (first (filter #(before? (:at event) (:at %)) asks)))]
                        :when        answer]
                    {:what :question :task (latest turn) :from (:at event) :to answer})
                  (for [{:keys [window done tool input] :as call} calls
                        :when (and done (nil? window) (not (dialog tool)))]
                    {:what :command :task (task call) :from (:at call) :to done :shape (shape tool input)})))}))
