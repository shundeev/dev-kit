;; The GitHub steps of the delivery flow (#95, ADR-0005), loaded by bin/kit:
;;
;;   kit start    files the issue (or reuses one), puts it on the board with its fields, creates its branch
;;   kit finish   commits, pushes, opens the pull request (a draft where main holds the ready workflow, ADR-0007;
;;                otherwise ready, with auto-merge on), sets the card In review
;;   kit release  a held pull request: removes the label hold, marks it ready, turns auto-merge on (ADR-0007)
;;   kit merge    after the owner's Approve: waits for the merge, sets the card Done, removes the branch
;;   kit checks   waits until the pull request's required checks exist and have finished, and says how they ended (#109)
;;   kit review   the owner's latest review and the unresolved review threads; --wait until the owner reviews again
;;   kit board set <project-number> <owner> <issue-url> <field> <option>   one field of one card (#41)
;;   kit board show   the board of the folder's kit.edn as JSON
;;
;; They were start_issue_flow.sh, finish_issue_flow.sh, merge_issue_flow.sh and board.sh: the same options, the
;; same output lines and exit codes. gh's JSON is read as data, so the flow needs no jq.
(ns flow
  (:require
   [dev-kit.flow.main :as main]))


(defn main
  "kit start | finish | release | merge | checks | review | board show | board set."
  [command opts]
  (main/main command opts))
