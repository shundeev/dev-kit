(ns dev-kit.flow.wait
  "Waiting on a pull request until what is awaited happens, with no fixed time limit (#109)."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]))


;; ------------------------------------------------------------------ waiting on a pull request


;; Nothing here waits on a clock (#109). A wait ends when what it waits for happens, or when that became impossible,
;; and it says which. The only number is the pause between two looks at GitHub, which no event replaces: GitHub cannot
;; call a process on this machine.


(defn- bad-poll [v] (str "GHWF_MERGE_POLL is '" v "': give it a whole number of seconds, or unset it for 10"))


(defn pause
  "The pause between two looks, in seconds: GHWF_MERGE_POLL, for kit merge and kit checks alike. It goes once both
  are recipes, which take `poll-seconds` in a step."
  []
  (let [v (shell/env "GHWF_MERGE_POLL" "10")]
    (or (parse-long v)
        (shell/die (bad-poll v)))))


(defn poll-seconds
  "The pause between two looks, in seconds, from GHWF_MERGE_POLL's text (\"10\" when unset); any other text stops
  the step."
  [text]
  (or (parse-long text)
      (steps/fail! :wait/bad-poll (bad-poll text))))


(defn sleep!
  "Waits the given seconds between two looks at GitHub. A story substitutes it with its clock, which plays the script."
  [seconds]
  (Thread/sleep (* 1000 seconds)))


(defn failed-text
  [failed where]
  (str (count failed)
       " required check" (when (> (count failed) 1) "s")
       " failed " where
       ":\n"
       (str/join "\n" (map #(str "  " (:name %) ": " (:link %)) failed))))


(defn failed-checks [checks] (filter #(#{"fail" "cancel"} (:bucket %)) checks))
