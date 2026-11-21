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


(defn main
  "kit start | finish | release | merge | checks | review | board show | board set, by the command's words, given the
  options the command tree (dev-kit.cli) parsed. Returns 0 when the command went through; a refusal is thrown as a
  problem, for kit's one edge to print."
  [command opts]
  (case command
    ["start"]        (start/start! opts)
    ["finish"]       (finish/finish! opts)
    ["release"]      (release/release! opts)
    ["merge"]        (merge/merge! opts)
    ["checks"]       (checks/checks! opts)
    ["review"]       (review/review! opts)
    ["board" "set"]  (board/set-field! opts)
    ["board" "show"] (board/show!))
  0)
