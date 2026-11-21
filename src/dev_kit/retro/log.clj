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
  [opt s]
  (or (try (Instant/parse s) (catch Exception _ nil))
      (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
      (problem/fail! :retro/bad-time
                     (str opt " " s " : not a time; write it as 2026-11-12T10:00:00Z or 2026-11-12T11:00:00+01:00"))))


(defn instant
  [s]
  (when s
    (Instant/parse s)))


(defn before? [^Instant a ^Instant b] (.isBefore a b))


(defn stamp [^Instant t] (str/replace (subs (str t) 0 16) "T" " "))


(defn span
  [a b]
  (let [m (.toMinutes (Duration/between a b))]
    (if (>= m 60)
      (format "%dh %02dm" (quot m 60) (rem m 60))
      (str m "m"))))


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
  [w]
  (let [w (str/replace w #"[\"'()]" "")]
    (if (str/blank? w)
      w
      (str (fs/file-name w)))))


(defn plain? [w] (and w (re-matches #"[a-z][a-z0-9-]*" w)))


(defn words [s] (remove str/blank? (str/split (str/trim (str s)) #"\s+")))


(defn command-words [seg] (drop-while #(or (lead-words %) (re-matches #"[A-Za-z_]\w*=.*" %)) (words seg)))


(defn split-top
  "Splits a shell command at && || ; | and newlines outside quotes."
  [^String s]
  (loop [i   0
         q   nil
         cur (StringBuilder.)
         out []]
    (if (>= i (count s))
      (conj out (str cur))
      (let [c  (.charAt s i)
            c2 (when (< (inc i) (count s))
                 (.charAt s (inc i)))
            op (cond (or (and (= c \&) (= c2 \&)) (and (= c \|) (= c2 \|))) 2
                     (#{\| \; \newline} c) 1)]
        (cond
          (and (= c \\) c2 (not= q \')) (recur (+ i 2) q (doto cur (.append c) (.append c2)) out)
          q (recur (inc i) (when (not= c q) q) (.append cur c) out)
          (#{\' \"} c) (recur (inc i) c (.append cur c) out)
          op (recur (+ i op) nil (StringBuilder.) (conj out (str cur)))
          :else (recur (inc i) q (.append cur c) out))))))


(defn segments
  "The simple commands of a shell command: heredoc bodies dropped, $(…) bodies added."
  [cmd]
  (let [s (-> (str cmd)
              (str/replace #"\\\n" " ")
              (str/replace #"(?s)<<-?\s*['\"]?(\w+)['\"]?([^\n]*)\n.*?\n[ \t]*\1[ \t]*(?=\n|$)" "$2"))]
    (concat (split-top s) (mapcat #(split-top (second %)) (re-seq #"\$\(([^()]*)\)" s)))))


(defn gh-args
  "gh's words without its flags and their values."
  [ws]
  (loop [[w & more] ws
         out        []]
    (cond (nil? w) out
          (gh-value-flags w) (recur (rest more) out)
          (str/starts-with? w "-") (recur more out)
          :else (recur more (conj out w)))))


(defn gh-api-write?
  [ws]
  (if-let [m (some (fn [[a b]]
                     (cond (#{"-X" "--method"} a)  b
                           (str/starts-with? a "--method=") (subs a 9)
                           (re-matches #"-X\w+" a) (subs a 2)))
                   (partition-all 2 1 ws))]
    (not= "GET" (str/upper-case (str m)))
    (boolean (some #{"-f" "-F" "--field" "--raw-field" "--input"} ws))))


(defn shape-of
  [[w & more]]
  (let [b (base w)]
    (case b
      "gh"  (let [[a c :as args] (gh-args more)]
              (cond (not= a "api")       (str/join " " (cons "gh" (filter plain? (take 2 args))))
                    (= c "graphql")      "gh api graphql"
                    (gh-api-write? more) "gh api (REST, write)"
                    :else                "gh api (REST)"))
      "git" (let [sub (first (remove #(str/starts-with? % "-")
                                     (loop [ws more]
                                       (if (#{"-C" "-c"} (first ws))
                                         (recur (drop 2 ws))
                                         ws))))]
              (if (plain? sub)
                (str "git " sub)
                "git"))
      ("bash" "sh" "zsh" "bb" "python" "python3" "node")
      (if (#{"-e" "-c"} (first more))
        (str b " " (first more))
        (let [[script sub] (remove #(or (str/starts-with? % "-") (re-find #"^\d*[<>&]" %)) more)
              file (some-> script base)]
          (str/join " "
                    (remove str/blank?
                            [b (when (and file (re-matches #"[\w.+-]+" file)) file)
                             (when (and (= b "bb") (plain? sub)) sub)]))))
      b)))


(defn shapes
  "The shapes of a command's parts, setting aside cd, echo and the like; a command made only of those is named
  by its first plain word."
  [tool input]
  (if (= tool "Bash")
    (let [segs  (segments (:command input))
          named (distinct (keep (fn [seg]
                                  (let [[w :as ws] (command-words seg)]
                                    (when (and w
                                               (not (skip-words w))
                                               (not (and (str/starts-with? w "$") (not (str/includes? w "/"))))
                                               (re-matches #"[A-Za-z][\w.+-]*" (base w)))
                                      (shape-of ws))))
                                segs))]
      (or (seq named) (some #(when (and (plain? %) (not= "cd" %)) [%]) (keep #(first (command-words %)) segs)) []))
    [(str tool)]))


(defn shape [tool input] (or (first (shapes tool input)) "shell"))


(defn shape-all
  [tool input]
  (let [s (shapes tool input)]
    (if (seq s)
      (str/join " + " (cond-> (vec (take 3 s)) (< 3 (count s)) (conj "…")))
      "shell")))


;; ------------------------------------------------------------------ ask rules


(defn glob-re
  "Bash(gh api *) or Read(/x/**) as a regex: * is anything; a trailing :* is a prefix too."
  [body]
  (re-pattern (str "^"
                   (str/join ".*" (map #(Pattern/quote %) (str/split (str/replace body #":\*$" "*") #"\*" -1)))
                   "$")))


(defn rule-matches?
  [tool input [_ t body]]
  (and (re-find (glob-re t) tool)
       (cond
         (nil? body)     true
         (= tool "Bash") (some #(re-find (glob-re body) (str/join " " (command-words %))) (segments (:command input)))
         (str/starts-with? body "domain:") (some-> (:url input) java.net.URI. .getHost (= (subs body 7)))
         :else           (some->> (some input [:file_path :notebook_path :path :url]) str (re-find (glob-re body))))))


(defn window-cause
  "The ask rule that raised a window, from the rules in force at that moment. Only a rule of plain words is
  printed whole; one naming a path or a URL prints as Tool(…)."
  [{:keys [tool input asks]}]
  (if-let [[r t body] (some #(let [p (re-matches #"([\w*-]+)(?:\((.*)\))?" %)]
                               (when (and p (rule-matches? tool input p)) p))
                            asks)]
    (str "ask rule: "
         (if (or (nil? body) (re-matches #"[\w .:*-]*" body))
           r
           (str t "(…)")))
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
  (vec (for [f     (sort (map str (fs/glob dir "*.jsonl")))
             line  (with-open [r (io/reader f)] (doall (line-seq r)))
             :let  [e (try (let [e (json/parse-string line true)]
                             ;; an event without a turn id belongs to no turn
                             (cond-> (assoc e :at (instant (:at e))) (str/blank? (str (:turn e))) (dissoc :turn)))
                           (catch Exception _ nil))]
             :when (and (:at e) (:session e))]
         e)))


(defn task-of
  [{:keys [branch origin]}]
  (when-let [[_ n] (some->> branch (re-matches #"(\d+)-.+"))] [(some-> origin str/lower-case) (parse-long n)]))


(defn runs
  "Commands sorted by time, split where neighbours are more than 10 minutes apart."
  [cmds]
  (reduce (fn [acc c]
            (let [prev (peek (peek acc))]
              (if (and prev (not (before? (.plus ^Instant (:at prev) (Duration/ofMinutes 10)) (:at c))))
                (conj (pop acc) (conj (peek acc) c))
                (conj acc [c]))))
          []
          cmds))


(defn pair
  "Calls in order, each with its window and outcome. A window goes to the first call of its turn with the same
  agent, tool and input that has neither yet."
  [events]
  (reduce (fn [calls e]
            (let [same (fn [ks] (fn [i c] (when (= (map c ks) (map e ks)) i)))]
              (case (:event e)
                "PreToolUse" (conj calls (assoc e :task (task-of e)))
                "PermissionRequest"
                (if-let [i (first (keep-indexed (fn [i c]
                                                  (when (and (nil? (:window c)) (nil? (:outcome c)))
                                                    ((same [:turn :agent :tool :input]) i c)))
                                                calls))]
                  (assoc-in calls [i :window] e)
                  calls)
                ("PostToolUse" "PostToolUseFailure" "PermissionDenied" "HookDenied")
                (if-let [i (first (keep-indexed (same [:id]) calls))]
                  (update calls i assoc :outcome (:event e) :reason (:reason e) :done (:at e))
                  calls)
                calls)))
          []
          events))


(defn session-friction
  "The friction in one session's events, {:items [{:kind :cause :at :task (:n)}] :messages [{:at :text :task}]}."
  [events]
  (let [calls    (pair events)
        turns    (distinct (keep :turn events))
        ended    (fn [ev] (into {} (for [e events :when (and (:turn e) (= ev (:event e)))] [(:turn e) e])))
        stop     (ended "Stop")
        failed   (ended "StopFailure")
        named    (memoize (fn [t]
                            (let [ts (set (keep :task (filter #(= t (:turn %)) calls)))]
                              (when (= 1 (count ts))
                                (first ts)))))
        ;; a message, an Esc, a failed turn or a question belongs to the task the session was last working on
        latest   (loop [[t & more] turns
                        prev       nil
                        out        {}]
                   (if t
                     (let [n (or (named t) prev)] (recur more n (assoc out t n)))
                     out))
        item     (fn [kind cause c] {:kind kind :cause cause :at (:at c) :task (or (:task c) (named (:turn c)))})
        first-at (fn [t] (:at (first (filter #(= t (:turn %)) events))))
        dialog   #{"AskUserQuestion" "ExitPlanMode"}
        call     (fn [{:keys [tool input window outcome reason turn] :as c}]
                   (let [ran? (#{"PostToolUse" "PostToolUseFailure"} outcome)]
                     (cond-> []
                       (= tool "AskUserQuestion") (conj (item :question "AskUserQuestion" c))
                       (= tool "ExitPlanMode") (conj (item :plan (if ran? "approved" "declined") c))
                       (and window (not (dialog tool)))
                       (conj (item (if ran? :confirm-yes :confirm-no) (window-cause window) c))
                       (= outcome "HookDenied") (conj (assoc (item :refusal-hook (shape-all tool input) c)
                                                             :why
                                                             (second (re-find #"Rule: (\S+)" (str reason)))))
                       (= outcome "PermissionDenied")
                       (conj (item :refusal-classifier
                                   (str (shape-all tool input)
                                        " — "
                                        (or (second (re-find #"\[(\w+)\]" (str reason))) "classifier"))
                                   c))
                       (= outcome "PostToolUseFailure") (conj (item :tool-error (shape tool input) c))
                       (and (nil? outcome) (nil? window) (stop turn) (not (dialog tool)))
                       (conj (item :refusal-rule (shape-all tool input) c)))))
        bash     (filter #(and (= "Bash" (:tool %)) (get-in % [:input :command])) calls)]
    {:items    (concat
                (mapcat call calls)
                ;; neither Stop nor StopFailure while the session went on: the owner pressed Esc, unless on a
                ;; window, which is that window's no
                (for [t     (butlast turns)
                      :when (and (not (stop t))
                                 (not (failed t))
                                 (not (some #(and (= t (:turn %)) (:window %) (nil? (:outcome %))) calls)))]
                  {:kind :interrupt :cause "—" :at (first-at t) :task (latest t)})
                (for [t (keys failed)] {:kind :turn-failed :cause "—" :at (first-at t) :task (latest t)})
                (for [[t e] stop
                      :when (:needs_input e)]
                  {:kind :question :cause "needs input in chat" :at (:at e) :task (latest t)})
                ;; the same command, word for word, three times or more within minutes: polling or retries
                (for [[_ same] (group-by #(str/join " " (words (get-in % [:input :command]))) bash)
                      run      (runs same)
                      :when    (>= (count run) 3)]
                  (assoc (item :repeat (shape "Bash" (:input (first run))) (first run)) :n (count run))))
     :messages (for [e     events
                     :when (= "UserPromptSubmit" (:event e))]
                 {:at (:at e) :text (:prompt e) :task (latest (:turn e))})
     ;; where the time went (#65): spans {:what :task :from :to}
     :time     (let [end  (fn [t] (:at (or (stop t) (failed t) (last (filter #(= t (:turn %)) events)))))
                     asks (filter #(= "UserPromptSubmit" (:event %)) events)
                     task (fn [c] (or (:task c) (named (:turn c))))]
                 (concat
                  (for [t turns] {:what :turn :task (latest t) :from (first-at t) :to (end t)})
                  ;; a window the owner answered, from the window to the run
                  (for [{:keys [window done tool] :as c} calls
                        :when (and window done (not (dialog tool)))]
                    {:what :window :task (task c) :from (:at window) :to done})
                  ;; a question: AskUserQuestion until answered; "needs input" until the owner's next message
                  (for [c     calls
                        :when (and (= "AskUserQuestion" (:tool c)) (:done c))]
                    {:what :question :task (task c) :from (:at c) :to (:done c)})
                  (for [[t e] stop
                        :when (:needs_input e)
                        :let  [answer (:at (first (filter #(before? (:at e) (:at %)) asks)))]
                        :when answer]
                    {:what :question :task (latest t) :from (:at e) :to answer})
                  (for [{:keys [window done tool input] :as c} calls
                        :when (and done (nil? window) (not (dialog tool)))]
                    {:what :command :task (task c) :from (:at c) :to done :shape (shape tool input)})))}))
