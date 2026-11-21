;; The report: the friction log and GitHub, printed (see bin/retro.clj).
(ns dev-kit.retro.report
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [dev-kit.problem :as problem]
   [dev-kit.retro.github :as github]
   [dev-kit.retro.log :as log])
  (:import
   (java.time Duration Instant)))


;; ------------------------------------------------------------------ the report


(def kinds
  [[:confirm-yes "Confirmation: yes"] [:confirm-no "Confirmation: no"] [:question "Question to the owner"]
   [:plan "Plan for approval"] [:interrupt "Owner interrupted (Esc)"] [:turn-failed "Turn failed (API error)"]
   [:refusal-hook "Refused by a hook"] [:refusal-classifier "Refused by the classifier"]
   [:refusal-rule "Refused by a rule"] [:tool-error "Tool error"] [:repeat "Same command 3+ times"]])


(def label (into {} kinds))


(def rank (into {} (map-indexed (fn [i [k]] [k i]) kinds)))


(defn tally [items] (reduce (fn [m i] (update m [(:kind i) (:cause i)] (fnil + 0) (:n i 1))) {} items))


(defn ordered [m] (sort-by (fn [[[k c]]] [(rank k) c]) m))


(defn one-line [s] (str/trim (str/replace (str s) #"\s+" " ")))


(defn seconds [spans] (reduce + (map #(.getSeconds (Duration/between (:from %) (:to %))) spans)))


(defn minutes
  "A duration as people read it: 45s, 12m, 1h 05m."
  [secs]
  (let [m (quot secs 60)]
    (cond (< secs 60) (str secs "s")
          (>= m 60)   (format "%dh %02dm" (quot m 60) (rem m 60))
          :else       (str m "m"))))


(defn time-lines
  "Where the task's time went: its turns, waiting on the owner, the commands."
  [spans]
  (let [of      (fn [what] (filter #(= what (:what %)) spans))
        longest (->> (of :command) (filter #(>= (seconds [%]) 60)) (sort-by #(- (seconds [%]))) (take 3))]
    (when (seq spans)
      [""
       (if (seq (of :turn))
         (str "Time of the task: " (count (of :turn)) " turns, " (minutes (seconds (of :turn))) " in all")
         ;; a turn that worked on several tasks belongs to none of them
         "Time of the task: its turns also served other tasks, so their time is not split")
       (str "- waiting on the owner: " (count (of :window))
            " confirmation windows, " (minutes (seconds (of :window)))
            "; " (count (of :question))
            " questions, " (minutes (seconds (of :question))))
       (str "- commands: "
            (minutes (seconds (of :command)))
            (when (seq longest)
              (str "; the longest: "
                   (str/join ", " (for [c longest] (str (:shape c) " " (minutes (seconds [c]))))))))])))


(defn mark? [text] (re-find #"(?iu)^\s*(трение|friction)\s*:" (str text)))


(defn rule-lines
  "The refusals that name their rule (a `Rule: <name>` line in the refusal: cc-safety-net, the delivery guard, the
  edit gate), counted per rule: in the task's own items, and in the last `days` days in every task on this machine."
  [items own now days]
  (let [named  (filter :why items)
        since  (.minus ^Instant now (Duration/ofDays days))
        recent (frequencies (map :why (remove #(log/before? (:at %) since) named)))
        mine   (frequencies (keep :why own))
        rules  (sort-by (fn [rule] [(- (get recent rule 0)) rule]) (set (concat (keys recent) (keys mine))))]
    (if (seq rules)
      (concat
       [""
        "Refusals by rule (only a refusal that names its rule is counted: cc-safety-net, the delivery guard, the edit gate):"
        (str "| Rule | This task | " days " days |")
        "|---|---|---|"]
       (for [rule rules] (str "| " rule " | " (get mine rule 0) " | " (get recent rule 0) " |")))
      ["" (str "Refusals by rule: none named its rule, in this task or in the last " days " days.")])))


(defn log-lines
  [{:keys [repo issue now messages days]}]
  (let [dir (log/log-dir)]
    (if-not (seq (fs/glob dir "*.jsonl"))
      ["Friction log: not available — the recorder (dev-kit #55) has written nothing on this machine yet."]
      (let [found     (map log/session-friction (vals (group-by :session (log/read-log dir))))
            mine?     #(= [(str/lower-case repo) issue] (:task %))
            items     (mapcat :items found)
            own       (filter mine? items)
            said      (filter mine? (mapcat :messages found))
            marks     (filter #(mark? (:text %)) said)
            others    (remove #(mark? (:text %)) said)
            spans     (filter mine? (mapcat :time found))
            times     (sort (concat (map :at (concat own said)) (map :from spans)))
            fortnight (tally (remove #(log/before? (:at %) (.minus ^Instant now (Duration/ofDays 14))) items))]
        (concat
         [(if (seq times)
            (str "Friction log on this machine: "
                 (reduce + (map #(:n % 1) own))
                 " events of the task, "
                 (log/stamp (first times))
                 " → "
                 (log/stamp (last times))
                 " UTC. The 14-day column counts every task and project on this machine.")
            "Friction log on this machine: no events of this task.")
          ""]
         (when (seq own)
           (concat ["| Kind | Cause | This task | 14 days |" "|---|---|---|---|"]
                   (for [[[k c] n] (ordered (tally own))]
                     (str "| " (label k) " | " c " | " n " | " (get fortnight [k c] 0) " |"))))
         (when-let [refused (seq (filter #(#{:refusal-hook :refusal-classifier :refusal-rule} (:kind %)) own))]
           (concat
            [""
             "Refusals of the task, each needs a verdict in the retrospective (it protected something, or it was too strict and the hook changes):"]
            (for [r (sort-by :at refused)]
              (str "- "
                   (log/stamp (:at r))
                   " "
                   (label (:kind r))
                   " · "
                   (one-line (:cause r))
                   (when (:why r)
                     (str " (rule " (:why r) ")"))))))
         (rule-lines items own now (or days 14))
         (time-lines spans)
         (when-let [recurring (seq (ordered (filter #(>= (val %) 3) fortnight)))]
           (concat ["" "Recurring in the last 14 days (3 or more, whichever task):"]
                   (for [[[k c] n] recurring] (str "- " (label k) " · " c ": " n))))
         (when (seq marks)
           (concat ["" "The owner's marks:"] (for [m marks] (str "- " (log/stamp (:at m)) " " (one-line (:text m))))))
         [""
          (str "Owner messages: "
               (count others)
               " (classify them locally with `kit retro "
               issue
               " --messages`; publish only counts and paraphrases)")]
         (when messages
           (concat ["" "Owner messages (local only, never publish):"]
                   (for [m    others
                         :let [t (one-line (:text m))]]
                     (str "- " (log/stamp (:at m)) " " (subs t 0 (min 300 (count t))))))))))))


(defn run
  "Prints the friction data of one issue. Throws a usage problem for a --now that is not a time and for a --days that
  is not a number."
  [{:keys [issue repo agent now days] :as opts}]
  (let [now  (if now
               (log/parse-time "--now" now)
               (Instant/now))
        days (if days
               (let [n (parse-long days)]
                 (if (and n (pos? n))
                   n
                   (problem/fail! :retro/bad-days
                                  (str "retro: --days " days " : not a number of days")
                                  {:fix "write a whole number above 0, e.g. --days 30"})))
               14)]
    (println (str "## Friction data: " repo "#" issue))
    (println)
    (doseq [l (log-lines (assoc opts :now now :days days))] (println l))
    (println)
    (println "### GitHub")
    (doseq [l (try (github/github-lines repo issue agent)
                   (catch Exception e [(str "GitHub: not available (" (ex-message e) ")")]))]
      (println l))))
