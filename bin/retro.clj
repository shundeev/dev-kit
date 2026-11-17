;; The data half of a retrospective (dev-kit #28, #55): the friction of one merged issue, from the friction
;; log the hooks write on this machine (claude/hooks/friction.sh) and from GitHub. Read-only.
;;
;;   kit retro <issue> [--repo R] [--messages]        (--now T for tests)
;;
;; The log is ${XDG_STATE_HOME:-~/.local/state}/dev-kit/friction/<YYYY-MM>.jsonl: one line per hook event, in
;; the order they happened. What happened to a tool call follows from the events around it (tested live, #55):
;;   window, yes         PreToolUse → PermissionRequest (same turn and input) → PostToolUse or PostToolUseFailure
;;   window, no or Esc   PreToolUse → PermissionRequest → no outcome
;;   refused             PermissionDenied (the classifier), HookDenied (dev-kit's hook), or no outcome in a
;;                       turn that ended with Stop (a deny rule)
;;   Esc                 a turn (prompt_id) with neither Stop nor StopFailure that another turn follows
;; A call belongs to the task its branch names (<n>-slug in the task's repository); a call naming none, to its
;; turn's task when the turn names exactly one. An owner's message, an Esc, a failed turn and a question to the
;; owner belong to the task the session was last working on.
;; The output holds counts, ask rules and command shapes: never commands, paths, URLs, flag values or the
;; owner's words, except the owner's marks («трение: …»).
(ns retro
  (:require
   [dev-kit.retro.missing :as missing]
   [dev-kit.retro.report :as report]))


;; kit calls these two through (resolve 'retro/run) and (resolve 'retro/run-missing).
(defn run [opts] (report/run opts))


(defn run-missing [opts] (missing/run-missing opts))
