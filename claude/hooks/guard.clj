#!/usr/bin/env bb
;; PreToolUse(Bash). The delivery flow is not optional — see AGENTS.md, "Delivery" (#93, ADR-0005).
;;
;; Refuses the commands that create work GitHub can see but the project board cannot. The
;; gh-project-workflow scripts pass untouched by construction: this hook sees only the command the agent
;; itself submitted, never the `gh` calls a script makes in a subprocess. Running the wrapper is the escape
;; hatch, and it needs no marker.
;;
;; Gate on the invariant ("an issue exists for this work"), not on the verb: `gh pr create` is correct from
;; an issue branch and wrong from the base branch.
;;
;; The command is read by a tokenizer, not by patterns: quoted text stays one word, heredoc bodies are
;; skipped by structure, and commands inside $(…), `…`, `bash -c "…"` and `eval` are judged as statements of
;; their own.
;;
;; Known gaps. It judges only the command text the agent submits:
;; - a command run inside a script file, or by another tool, is not seen;
;; - a push to main is left to the server's ruleset and the settings' deny rules; a forced push is judged here
;;   only for its target branch (#38);
;; - `gh api` is judged by its method and fields (#54), not by what the endpoint does.
;;
;; The bash entry (managed/pretooluse.sh) refuses when bb is missing, and an error here refuses too (exit 2):
;; the guard fails closed (ADR-0001, rule 3).
(ns guard
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))


;; ------------------------------------------------------------------ fast exit


;; Most commands touch no rule. bb reads the forms of this file one by one, so a command whose text holds none
;; of the words a rule needs leaves here, before the rest is read: the hook then costs what bb's start does.
;; Quotes and backslashes are dropped first, so `g\h` or `g''h` does not slip past.
(def run? (= *file* (System/getProperty "babashka.file")))


(def input
  (when run?
    (try (json/parse-string (slurp *in*)) (catch Exception e e))))


(def command
  (when (map? input)
    (get-in input ["tool_input" "command"])))


(when (and run? (map? input))
  (let [bare (str/replace (str command) #"[\\'\"]" "")
        zsh? (= "zsh" (some-> (System/getenv "SHELL") (str/replace #".*/" "")))]
    (when-not (or (str/includes? bare "gh")
                  (str/includes? bare "DELIVERY_GUARD")
                  (str/includes? bare "sleep")
                  (str/includes? bare "kit")
                  ;; a forced push: --force…, -f (alone or among short flags), +refspec (#117)
                  (and (str/includes? bare "git") (re-find #"force| -[A-Za-z]*f| \+\S" bare))
                  (and zsh? (str/includes? bare "$")))
      (System/exit 0))))


;; Required here, not in the ns form: only a command that passed the fast exit loads the rules.
(require 'dev-kit.guard.rules)


(when run?
  (let [{:keys [exit out err]} (dev-kit.guard.rules/run input)]
    (when out
      (println out))
    (when err
      (binding [*out* *err*]
        (println err)))
    (System/exit exit)))
