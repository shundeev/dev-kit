(ns dev-kit.flow.checks
  "kit checks: waits until the pull request's required checks have finished and says how they ended (#109)."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]))


;; ------------------------------------------------------------------ kit checks


(def checks-usage
  "Usage: kit checks [options]

Waits until the checks the base branch requires exist on the pull request's head commit and have
finished, and, where the base has the ready workflow, until that workflow has decided (ADR-0007). The checks are read from the base's rulesets
(classic branch protection needs admin to read); a base that requires none ends it at once. Run it as a background command: the agent is woken when it exits.

  exit 0  every required check passed, or the base requires none. Where the base has the ready workflow, it
          also waits for the completion of that workflow's run for this head (not for the PR to leave draft: its review
          request and auto-merge come after) and reports the PR's state: ready (owners asked, auto-merge
          on), held (the label hold: kit release), or still a draft (the run's own decision line)
  exit 1  a required check failed (or was cancelled): each is named with its link, at once
  exit 2  no result: the pull request was closed or merged, its head moved (a push: the new
          commit's checks are the ones to await; run it again), or gh could not be read (its message is printed)

Options:
  --pr <number>                 The pull request (default: the open one of the current branch)
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --help                        Show this help

Environment: GHWF_MERGE_POLL, seconds between looks (default 10). Nothing else bounds the wait.")


(def checks-spec
  {"--pr" [:pr :value] "--repo" [:repo :value] "--help" [:help :flag] "-h" [:help :flag]})


(defn checks!
  [_kit args]
  (let [o         (shell/parse-opts checks-spec args)
        _ (when (:help o)
            (println checks-usage)
            (System/exit 0))
        repo      (shell/resolve-repo (:repo o))
        poll      (wait/pause)
        stop      (fn [code & msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit code))
        no-result #(stop 2 (str %))
        branch    (shell/git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (and (:pr o) (not (re-matches #"\d+" (:pr o))))
            (shell/die (str "--pr is '" (:pr o) "': give the pull request's number")))
        pr        (or (:pr o)
                      (do (when (#{(shell/env "GHWF_DEFAULT_BASE" "main") "HEAD"} branch)
                            (shell/die (str "Run from the issue branch, not '"
                                            branch
                                            "', or name the pull request: kit checks --pr <number>")))
                          (not-empty (shell/gh! "pr" "list"
                                       "-R"      repo
                                       "--head"  branch
                                       "--state" "open"
                                       "--json"  "url"
                                       "--jq"    ".[0].url // empty")))
                      (shell/die (str "No open pull request for branch '"
                                      branch
                                      "' in "
                                      repo
                                      ". Fix: open it with kit finish, or name it: kit checks --pr <number>")))
        {:keys [head base]} (wait/read-pr pr repo no-result)
        ready?    (github/ready-workflow? repo base)
        sha7      #(subs (str %) 0 (min 7 (count (str %))))
        ;; What the base requires, once: a check that is not registered yet is told from one the base never asks for.
        wanted    (->> (wait/gh-json no-result "api" (str "repos/" repo "/rules/branches/" base))
                       (filter #(= "required_status_checks" (:type %)))
                       (mapcat #(get-in % [:parameters :required_status_checks]))
                       (map :context)
                       distinct)]
    (if (empty? wanted)
      (println (str "No required checks on " base "; nothing to wait for: " pr))
      (do
        (println (str "Waiting for " (str/join ", " wanted) " on " (sha7 head) " (" pr ")."))
        (loop [shown nil
               ruled #{}]
          (let [checks (wait/required-checks pr repo no-result)
                now (wait/read-pr pr repo no-result)
                failed (wait/failed-checks checks)
                missing (remove (set (map :name checks)) wanted)
                pending (filter #(= "pending" (:bucket %)) checks)
                status (cond (seq missing) (str "Not registered yet: " (str/join ", " missing) ".")
                             (seq pending) (str (count pending)
                                                " of " (count checks)
                                                " required checks have not finished: "
                                                (str/join ", " (map :name pending))))
                ;; The ready workflow's decision (ADR-0007). Its run is found by the log line `CI run id: …, … at <head>`:
                ;; a workflow_run run carries the default branch's sha, not the PR's, so only the log names the head.
                settled (and ready? (nil? status) (empty? failed) (= head (:head now)))
                runs (when (and settled (not (:hold now)))
                       (->>
                        (wait/gh-json no-result
                                      "run"        "list"
                                      "-R"         repo
                                      "--workflow" (last (str/split github/ready-workflow-path #"/"))
                                      "--event"    "workflow_run"
                                      "--limit"    "20"
                                      "--json"     "databaseId,status")
                        (filter #(and (= "completed" (:status %)) (not (ruled (:databaseId %)))))
                        (mapv (fn [ci-run]
                                (let [log (shell/run "gh" "run" "view" (str (:databaseId ci-run)) "-R" repo "--log")]
                                  (assoc ci-run :log (:out log)))))))
                hit (first (filter #(str/includes? (str (:log %)) (str " at " head)) runs))
                st (if hit
                     (wait/read-pr pr repo no-result)
                     now) ; the run completed after `now` was read
                ;; Leaving draft is only the workflow's first step (it then asks the owners and turns auto-merge on), so
                ;; only its completed run, or the label hold, ends the wait; the PR's state is read after the run.
                done
                (when (and settled (or hit (:hold now)))
                  (cond
                    (:hold st)
                    (str
                     "Held: "
                     pr
                     " carries the label hold and stays a draft. Fix: kit release, once what it waited for has merged.")
                    (not (:draft st)) (str "Ready: "
                                           pr
                                           " is marked ready; owners asked: "
                                           (if (seq (:owners st))
                                             (str/join ", " (:owners st))
                                             "nobody")
                                           "; auto-merge "
                                           (if (:auto-merge st) "on" "off")
                                           ".")
                    hit (str "Still a draft: the ready workflow ran for "
                             (sha7 head)
                             " and left it. "
                             (str/join " " (re-seq #"decision: [^\n]*" (:log hit)))
                             " Fix: see its run; kit release once every required check is green.")))
                ;; No event exists for a workflow_run delivery that never comes: the wait has no end of its own then.
                ;; It is told so, with the way out, rather than bounded by a timer (#109).
                status (or status
                           (when (and settled (not done))
                             (str "Waiting for the ready workflow's run for "
                                  (sha7 head)
                                  ". If GitHub never delivers it, kit release is the way out.")))]
            (cond
              (= "MERGED" (:state now)) (stop 2 (str "The pull request merged, so there is nothing to wait for: " pr))
              (not= "OPEN" (:state now))
              (stop 2 (str "Ended with no result: the pull request is " (str/lower-case (:state now)) ", " pr "."))
              (not= head (:head now))
              (stop 2
                    (str "Ended with no result: the head moved from "
                         (sha7 head)
                         " to "
                         (sha7 (:head now))
                         " (a push). Fix: run kit checks again for the new commit."))

              (seq failed)
              (shell/die (str
                          (wait/failed-text failed (str "on " (sha7 head)))
                          "\nFix: open the link, fix the cause, push; then run kit checks again for the new commit."))

              status (do (when (not= status shown)
                           (println status))
                         (Thread/sleep (* 1000 poll))
                         (recur status (into ruled (map :databaseId runs))))

              :else (do (println (str "All " (count checks) " required checks passed on " (sha7 head) ": " pr))
                        (some-> done println)))))))))
