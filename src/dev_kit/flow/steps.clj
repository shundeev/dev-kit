(ns dev-kit.flow.steps
  "A command as a list of named steps (ADR-0008). A step is (fn [facts]) and
  returns the facts it adds; what it needs from the outside it calls directly (step 7b of #139). A step that cannot go on throws a problem with dev-kit.problem/fail!, and the run stops there: the
  result keeps every fact gathered before it and names the step. The run's own keys are namespaced (::status,
  ::done, ::stopped-at, ::problem), so no fact of a command can collide with them: kit start has a :status of its
  own, the board's Status."
  (:require
   [clojure.string :as str]))


(defn run
  "Runs the steps, given as name and function pairs, in order. The result holds the facts gathered and the
  names of the steps done (::done), with ::status :ok, or ::status :fail with the ::problem and the step it
  ::stopped-at."
  [facts & steps]
  (when (odd? (count steps))
    (throw (ex-info "steps/run takes a name and a function for each step" {:steps steps})))
  (reduce (fn [facts [step-name step]]
            (try
              (-> facts
                  (merge (step facts))
                  (update ::done conj step-name))
              (catch clojure.lang.ExceptionInfo e
                (if (:problem (ex-data e))
                  (reduced (assoc facts ::status :fail ::problem (ex-data e) ::stopped-at step-name))
                  (throw e)))))
          (assoc facts ::status :ok ::done [])
          (partition 2 steps)))


(defn- with-fix
  "The text, then what to do: on its own line after a text of several lines, else after a full stop."
  [text fix]
  (cond (nil? fix) text
        (str/includes? text "\n") (str text "\nFix: " fix)
        (re-find #"[.!?]$" text) (str text " Fix: " fix)
        :else (str text ". Fix: " fix)))


(defn problem-refusal
  "How a problem ends the run: the text for stderr and the exit code. \"Error: <text>\" and 1, unless the problem
  named another :exit or a :plain text; the :fix follows the text as \"Fix: <fix>\"."
  [{:keys [message fix exit plain]}]
  {:text (with-fix (if plain
                     message
                     (str "Error: " message))
                   fix)
   :exit (or exit 1)})


(defn refusal
  "How a stopped run ends: problem-refusal of its problem."
  [result]
  (problem-refusal (::problem result)))


(defn complete!
  "The result of a run that went through. A stopped run is thrown again as its problem, for the flow's one edge to
  print and to turn into an exit code."
  [result]
  (if (= :fail (::status result))
    (let [problem (::problem result)]
      (throw (ex-info (:message problem) problem)))
    result))
