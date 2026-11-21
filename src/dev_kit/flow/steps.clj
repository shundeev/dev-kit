(ns dev-kit.flow.steps
  "A command as a list of named steps (ADR-0008). A step is (fn [facts]) and
  returns the facts it adds; what it needs from the outside it calls directly (step 7b of #139). A step that cannot go on throws a problem with dev-kit.problem/fail!, and the run stops there: the
  result keeps every fact gathered before it and names the step. The run's own keys are namespaced (::status,
  ::done, ::stopped-at, ::problem), so no fact of a command can collide with them: kit start has a :status of its
  own, the board's Status.")


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


(defn refusal
  "How a stopped run ends: the text for stderr and the exit code. \"Error: <text>\" and 1, unless the step's fail!
  named another :exit or a :plain text."
  [result]
  (let [{:keys [message exit plain]} (::problem result)]
    {:text (if plain
             message
             (str "Error: " message))
     :exit (or exit 1)}))
