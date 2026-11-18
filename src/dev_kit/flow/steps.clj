(ns dev-kit.flow.steps
  "A command as a list of named steps (ADR-0008). A step is (fn [ctx facts]) and
  returns the facts it adds. A step that cannot go on throws a problem with fail!, and the run stops there: the
  result keeps every fact gathered before it and names the step. The run's own keys are namespaced (::status,
  ::done, ::stopped-at, ::problem), so no fact of a command can collide with them: kit start has a :status of its
  own, the board's Status.")


(defn fail!
  "Stops the step: a problem keyword, the text the person reads, and any facts that explain it. Two facts say how the
  run ends, read by `refusal`: :exit, the exit code (1 when absent), and :plain, true to print the text without
  \"Error: \"."
  ([problem message] (fail! problem message {}))
  ([problem message facts]
   (throw (ex-info message (assoc facts :problem problem :message message)))))


(defn run
  "Runs the steps, given as name and function pairs, in order. The result holds the facts gathered and the
  names of the steps done (::done), with ::status :ok, or ::status :fail with the ::problem and the step it
  ::stopped-at."
  [ctx facts & steps]
  (when (odd? (count steps))
    (throw (ex-info "steps/run takes a name and a function for each step" {:steps steps})))
  (reduce (fn [facts [step-name step]]
            (try
              (-> facts
                  (merge (step ctx facts))
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
