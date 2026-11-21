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
  back. Returns 0 when the command went through; a refusal is thrown as a problem, for kit's one edge to print."
  [cmd args kit]
  (run-command cmd args kit)
  0)
