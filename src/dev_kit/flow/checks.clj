(ns dev-kit.flow.checks
  "kit checks: waits until the pull request's required checks have finished and says how they ended (#109). The steps
  it shares with kit review are in dev-kit.flow.watched-pr."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.flow.wait :as wait]
   [dev-kit.flow.watched-pr :as watched-pr]))


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


(defn read-input
  "The command line and the GHWF_* variables kit checks reads, as one map; env is (fn [name default]), shell/env in a
  run. :poll-text is GHWF_MERGE_POLL as written, a step turns it into seconds; :default-base is the branch the command
  refuses to be run from."
  [args env]
  (let [o (shell/parse-opts checks-spec args)]
    (if (:help o)
      o
      (assoc o
             :poll-text    (env "GHWF_MERGE_POLL" "10")
             :default-base (env "GHWF_DEFAULT_BASE" "main")))))


;; ------------------------------------------------------------------ kit checks' steps


(defn- sha7
  [sha]
  (let [text (str sha)]
    (subs text 0 (min 7 (count text)))))


(defn- read-pr
  "The pull request's head and base."
  [{:keys [pr repo]}]
  (select-keys (github/read-pr pr repo watched-pr/no-result) [:head :base]))


(defn- read-base-workflow
  "Whether the base holds the ready workflow; asked once, before any wait."
  [{:keys [repo base]}]
  {:ready? (github/ready-workflow? repo base)})


(defn- required-contexts
  "The checks the base's rulesets require, by name. A gh call that gives no answer goes to `fail` with gh's message."
  [repo base fail]
  (->> (github/gh-json fail "api" (str "repos/" repo "/rules/branches/" base))
       (filter #(= "required_status_checks" (:type %)))
       (mapcat #(get-in % [:parameters :required_status_checks]))
       (map :context)
       distinct
       vec))


(defn- read-wanted
  "What the base requires, once: a check that is not registered yet is told from one the base never asks for."
  [{:keys [repo base]}]
  {:wanted (required-contexts repo base watched-pr/no-result)})


(defn- report-nothing-required
  "Ends the run at once, with the line to print, when the base requires no check."
  [{:keys [wanted base pr]}]
  (when (empty? wanted)
    {:report [(format "No required checks on %s; nothing to wait for: %s" (str base) pr)]}))


(defn- announce
  "Says what is awaited, once, before the first look."
  [{:keys [wanted head pr]}]
  (when (seq wanted)
    (println (format "Waiting for %s on %s (%s)." (str/join ", " wanted) (sha7 head) pr))
    {}))


(defn- ready-runs
  "The ready workflow's recent runs, {:databaseId :status}. A gh call that gives no answer goes to `fail`."
  [repo fail]
  (github/gh-json fail
                  "run"        "list"
                  "-R"         repo
                  "--workflow" (last (str/split github/ready-workflow-path #"/"))
                  "--event"    "workflow_run"
                  "--limit"    "20"
                  "--json"     "databaseId,status"))


(defn- run-log
  "gh run view --log: the run's log text."
  [repo id]
  (:out (shell/run "gh" "run" "view" (str id) "-R" repo "--log")))


(defn- read-ready-runs
  "The ready workflow's completed runs not read before, each with its log. Its run for the head is found by the log
  line `CI run id: …, … at <head>`: a workflow_run run carries the default branch's sha, not the PR's, so only the log
  names the head."
  [repo fail ruled]
  (->> (ready-runs repo fail)
       (filter #(and (= "completed" (:status %)) (not (ruled (:databaseId %)))))
       (mapv #(assoc % :log (run-log repo (:databaseId %))))))


(defn- workflow-decision
  "What the ready workflow did with the pull request (ADR-0007). `after-run` is the PR read after its run."
  [pr head after-run hit]
  (let [owners (if (seq (:owners after-run))
                 (str/join ", " (:owners after-run))
                 "nobody")]
    (cond
      (:hold after-run)
      (format "Held: %s carries the label hold and stays a draft. Fix: kit release, once what it waited for has merged."
              pr)

      (not (:draft after-run))
      (format "Ready: %s is marked ready; owners asked: %s; auto-merge %s."
              pr
              owners
              (if (:auto-merge after-run) "on" "off"))

      hit
      (format
       "Still a draft: the ready workflow ran for %s and left it. %s Fix: see its run; kit release once every required check is green."
       (sha7 head)
       (str/join " " (re-seq #"decision: [^\n]*" (:log hit)))))))


(defn- read-checks-and-runs
  "What the wait looks at: the required checks, the PR, and, where the ready workflow decides, its runs. Leaving draft is
  only the workflow's first step (it then asks the owners and turns auto-merge on), so only its completed run, or the
  label hold, ends the wait. Returns the checks and the PR read, the runs read, the line to say while waiting (nil when
  every check finished and the workflow decided) and the workflow's decision."
  [{:keys [pr repo head wanted ready?]} ruled]
  (let [fail      watched-pr/no-result
        checks    (github/required-checks pr repo fail)
        now       (github/read-pr pr repo fail)
        missing   (remove (set (map :name checks)) wanted)
        pending   (filter #(= "pending" (:bucket %)) checks)
        failed    (wait/failed-checks checks)
        status    (cond (seq missing) (format "Not registered yet: %s." (str/join ", " missing))
                        (seq pending) (format "%d of %d required checks have not finished: %s"
                                              (count pending)
                                              (count checks)
                                              (str/join ", " (map :name pending))))
        settled   (and ready? (nil? status) (empty? failed) (= head (:head now)))
        runs      (when (and settled (not (:hold now)))
                    (read-ready-runs repo fail ruled))
        hit       (first (filter #(str/includes? (str (:log %)) (str " at " head)) runs))
        after-run (if hit
                    (github/read-pr pr repo fail)
                    now)
        done      (when (and settled (or hit (:hold now)))
                    (workflow-decision pr head after-run hit))]
    {:checks checks
     :now    now
     :runs   runs
     :done   done
     ;; No event exists for a workflow_run delivery that never comes: the wait has no end of its own then. It is told
     ;; so, with the way out, rather than bounded by a timer (#109).
     :status (or
              status
              (when (and settled (not done))
                (format
                 "Waiting for the ready workflow's run for %s. If GitHub never delivers it, kit release is the way out."
                 (sha7 head))))}))


(defn- stop-if-over!
  "A wait that cannot end well stops here, judged in this order: merged, no longer open, the head moved, a check failed."
  [{:keys [pr head]} {:keys [checks now]}]
  (let [failed (wait/failed-checks checks)]
    (cond (= "MERGED" (:state now))
          (steps/fail! :checks/merged
                       (format "The pull request merged, so there is nothing to wait for: %s" pr)
                       {:exit 2 :plain true})

          (not= "OPEN" (:state now))
          (steps/fail! :checks/not-open
                       (format "Ended with no result: the pull request is %s, %s." (str/lower-case (:state now)) pr)
                       {:exit 2 :plain true})

          (not= head (:head now))
          (steps/fail!
           :checks/head-moved
           (format
            "Ended with no result: the head moved from %s to %s (a push). Fix: run kit checks again for the new commit."
            (sha7 head)
            (sha7 (:head now)))
           {:exit 2 :plain true})

          (seq failed)
          (steps/fail! :checks/failed
                       (format
                        "%s\nFix: open the link, fix the cause, push; then run kit checks again for the new commit."
                        (wait/failed-text failed (str "on " (sha7 head))))))))


(defn- wait-for-checks
  "Looks every poll period until the checks passed (and, where the base has the ready workflow, it decided) or the wait
  cannot end well. A status line is printed when it differs from the last one."
  [{:keys [wanted pr head poll] :as facts}]
  (when (seq wanted)
    (loop [shown nil
           ruled #{}]
      (let [seen (read-checks-and-runs facts ruled)]
        (stop-if-over! facts seen)
        (if-let [status (:status seen)]
          (do (when (not= status shown)
                (println status))
              (wait/sleep! poll)
              (recur status (into ruled (map :databaseId (:runs seen)))))
          {:report (cond-> [(format "All %d required checks passed on %s: %s" (count (:checks seen)) (sha7 head) pr)]
                     (:done seen) (conj (:done seen)))})))))


(defn checks
  "kit checks as a recipe; the result is data that checks! prints."
  [input]
  (steps/run (assoc input :command "checks")
    :read-repo          watched-pr/read-repo
    :read-poll          watched-pr/read-poll
    :read-branch        watched-pr/read-branch
    :check-pr-option    watched-pr/check-pr-option
    :find-pr            watched-pr/find-pr
    :read-pr            read-pr
    :read-base-workflow read-base-workflow
    :read-wanted        read-wanted
    :report-nothing-required report-nothing-required
    :announce           announce
    :wait-for-checks    wait-for-checks))


(defn print-result
  "What the run ends with, on stdout."
  [{:keys [report]}]
  (run! println report))


(defn checks!
  [_kit args]
  (let [input (read-input args shell/env)]
    (when (:help input)
      (println checks-usage)
      (System/exit 0))
    (let [result (checks input)]
      (when (= :fail (::steps/status result))
        (shell/exit-with! (steps/refusal result)))
      (print-result result))))
