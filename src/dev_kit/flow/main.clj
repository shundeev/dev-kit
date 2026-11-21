(ns dev-kit.flow.main
  "The flow commands by name, and the flow's one edge: a command reads its input, runs its recipe and prints its result;
  a refusal it throws is printed here, once, and becomes the exit code that kit's entry ends the process with."
  (:require
   [dev-kit.flow.board :as board]
   [dev-kit.flow.checks :as checks]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.merge :as merge]
   [dev-kit.flow.release :as release]
   [dev-kit.flow.review :as review]
   [dev-kit.flow.start :as start]
   [dev-kit.flow.steps :as steps]))


;; ------------------------------------------------------------------ entry


(defn- run-command
  [cmd args kit]
  (case cmd
    "start"   (start/start! kit args)
    "finish"  (finish/finish! kit args)
    "release" (release/release! kit args)
    "merge"   (merge/merge! kit args)
    "checks"  (checks/checks! kit args)
    "review"  (review/review! kit args)
    "board"   (board/board! kit args)))


(defn main
  "kit start | finish | release | merge | checks | review | board, with the path of bin/kit for the commands that call it
  back. Returns the exit code: 0 when the command went through, else the refusal's, printed on stderr."
  [cmd args kit]
  (try (run-command cmd args kit)
       0
       (catch clojure.lang.ExceptionInfo e
         (if (:problem (ex-data e))
           (let [{:keys [text exit]} (steps/problem-refusal (ex-data e))]
             (binding [*out* *err*]
               (println text)
               (flush))
             exit)
           (throw e)))))
