(ns dev-kit.problem
  "One way to stop with a message (step 4 of #139): fail! throws a problem, one ex-info shape that every edge of
  kit reads, and exit-codes is the one list of the exit codes kit uses."
  (:require
   [clojure.string :as str]))


(defn fail!
  "Stops with a problem: a problem keyword written out at the call, the text the person reads, and options. :fix is
  what the person does next; :exit is the exit code (1 when absent). Any other key is a fact that explains the
  problem; none says how it is printed, for every refusal prints the same way (refusal-text). The ex-info carries
  the options with :problem and :message."
  ([problem message] (fail! problem message {}))
  ([problem message options]
   (throw (ex-info message (assoc options :problem problem :message message)))))


(defn refusal-text
  "What the person reads when a problem ends the run, the one form of every refusal of kit: the text, then what to do.
  After a text of several lines, \"Fix: <fix>\" is on its own line; else it follows the text, after a full stop."
  [{:keys [message fix]}]
  (cond
    (nil? fix)
    message

    (str/includes? message "\n")
    (str message "\nFix: " fix)

    (re-find #"[.!?]$" message)
    (str message " Fix: " fix)

    :else
    (str message ". Fix: " fix)))


(def exit-codes
  "Every exit code kit uses, what it means and who reads it."
  {0 "done, allowed or help; read by CI steps and by Claude Code's hooks"
   1
   "refused: the text says why and what to do; kit retro --missing and kit gate also use it for 'found something'; read by CI steps, the selftest, session-start.sh (retro --missing) and kit start (retro --missing)"
   2
   "no verdict yet (kit checks, kit review, retro --missing: the check could not be made; read by the skills and kit start), or a hook's refusal (ADR-0001; read by Claude Code)"
   3 "kit hook: the hook asks; read by Claude Code and people running kit hook"
   128 "git's own code for a failed git push, passed through by kit finish and kit merge; read by the selftest"})
