(ns dev-kit.flow.wait
  "Waiting on a pull request until what is awaited happens, with no fixed time limit (#109)."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.shell :as shell]
   [dev-kit.problem :as problem]))


;; ------------------------------------------------------------------ waiting on a pull request


;; Nothing here waits on a clock (#109). A wait ends when what it waits for happens, or when that became impossible,
;; and it says which. The only number is the pause between two looks at GitHub, which no event replaces: GitHub cannot
;; call a process on this machine.


(defn poll-seconds
  "The pause between two looks, in seconds, from GHWF_MERGE_POLL's text (\"10\" when unset); any other text, or a
  negative number, stops the step."
  [text]
  (let [seconds (parse-long text)]
    (if (and seconds (not (neg? seconds)))
      seconds
      (problem/fail! :wait/bad-poll
                     (str "GHWF_MERGE_POLL is '" text "': give it a whole number of seconds, or unset it for 10")
                     {:fix "set GHWF_MERGE_POLL to a whole number of seconds (0 or more), or unset it"}))))


(defn sleep!
  "Waits the given seconds between two looks at GitHub. A story substitutes it with its clock, which plays the script."
  [seconds]
  (Thread/sleep (* 1000 seconds)))


(def passing-errors-allowed
  "How many passing GitHub errors in a row a wait takes before it stops."
  3)


(defn- what-github-answered
  "The HTTP code in gh's message, as \"HTTP 503\", or \"with an error\" when it names none."
  [message]
  (if-let [[_ code] (re-find #"HTTP (\d{3})" message)]
    (str "HTTP " code)
    "with an error"))


(defn look!
  "One look at GitHub inside a wait; `ask` is a function of nothing that reads it, `errors` the passing errors in a row
  so far, `poll` the pause before the next look. Returns {:answer <what ask gave> :errors 0}. A read that GitHub
  answered with a passing error (shell/passing-github-error?, judged here from gh's message) is no answer this time:
  it prints so, and returns {:errors <one more>} for the wait to ask again. The third in a row, and any other error,
  stops the wait."
  [errors poll ask]
  (try {:answer (ask) :errors 0}
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [problem message]} (ex-data e)
               errors (inc errors)]
           (cond (not (and problem (shell/passing-github-error? message)))
                 (throw e)

                 (>= errors passing-errors-allowed)
                 (problem/fail! :github/passing-errors
                                (format "GitHub answered with an error %d times in a row; the last: %s" errors message)
                                {:exit 2 :fix "check https://www.githubstatus.com, then run the same command again"})

                 :else
                 (do (println (format "GitHub answered %s; asking again in %d s" (what-github-answered message) poll))
                     {:errors errors}))))))


(defn failed-text
  [failed where]
  (str (count failed)
       " required check" (when (> (count failed) 1) "s")
       " failed " where
       ":\n"
       (str/join "\n" (map #(str "  " (:name %) ": " (:link %)) failed))))


(defn failed-checks [checks] (filter #(#{"fail" "cancel"} (:bucket %)) checks))
