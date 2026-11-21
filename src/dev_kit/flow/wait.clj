(ns dev-kit.flow.wait
  "Waiting on a pull request until what is awaited happens, with no fixed time limit (#109)."
  (:require
   [clojure.string :as str]
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


(defn failed-text
  [failed where]
  (str (count failed)
       " required check" (when (> (count failed) 1) "s")
       " failed " where
       ":\n"
       (str/join "\n" (map #(str "  " (:name %) ": " (:link %)) failed))))


(defn failed-checks [checks] (filter #(#{"fail" "cancel"} (:bucket %)) checks))
