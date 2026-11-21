(ns dev-kit.flow.main
  "The flow commands by name: a command reads its input, runs its recipe and prints its result; a refusal it throws
  is printed by kit's one edge (dev-kit.main/run)."
  (:require
   [dev-kit.flow.board :as board]
   [dev-kit.flow.checks :as checks]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.merge :as merge]
   [dev-kit.flow.release :as release]
   [dev-kit.flow.review :as review]
   [dev-kit.flow.start :as start]))


;; ------------------------------------------------------------------ entry


(defn- run-command
  [cmd args]
  (case cmd
    "start"   (start/start! args)
    "finish"  (finish/finish! args)
    "release" (release/release! args)
    "merge"   (merge/merge! args)
    "checks"  (checks/checks! args)
    "review"  (review/review! args)
    "board"   (board/board! args)))


(defn main
  "kit start | finish | release | merge | checks | review | board. Returns 0 when the command went through; a refusal is thrown as a problem, for kit's one edge to print."
  [cmd args]
  (run-command cmd args)
  0)
