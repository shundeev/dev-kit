(ns dev-kit.flow.github
  "What several flow commands ask of GitHub and git, as plain functions a step calls directly (#139, step 7b): a
  reader goes from the call to the code in one jump. The calls are today's gh and git calls, in the same order, since
  the selftest's fake gh checks them as strings. A function returns data and decides nothing: refusing is the
  step's. A call only one command needs sits in that command's file, above its step.

  The per-command operation maps below are for the commands not yet converted: a step calls
  `((:github ctx) :op args…)` and tests hand an in-memory GitHub. A map goes away with its command; no name may
  appear in two maps."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]))


(defn issue-url
  "gh issue view: {:exit :out :err}, the issue's URL on stdout. A missing issue is the step's to judge."
  [repo number]
  (shell/run "gh" "issue" "view" number
    "-R"     repo
    "--json" "url"
    "--jq"   ".url"))


(defn item-add!
  "The issue's card on the board: {:id card-id}, or {:error message} when the board refuses."
  [project-number owner url]
  (try {:id (board/board-gh! "project" "item-add" project-number
              "--owner"  owner
              "--url"    url
              "--format" "json"
              "--jq"     ".id")}
       (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))


(defn set-field!
  "A single-select field of the issue's card: nil, or why the board refused."
  [project-number owner url field option]
  (board/board-set-field project-number owner url field option))


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn ready-workflow-failure
  "What it says when GitHub cannot say whether `base` of `repo` holds the ready workflow: gh's message, or its exit code
  when it printed none."
  [repo base exit message]
  (let [reason (if (str/blank? message)
                 (format "gh exit code %s" exit)
                 message)]
    (format
     "Could not look for %s on '%s' of %s (%s). Nothing was committed or pushed: run it again when GitHub answers, or check 'gh auth status'."
     ready-workflow-path
     base
     repo
     reason)))


(defn ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure goes to
  `fail` with the text: guessing would open a ready PR in a repository that has the workflow, or a draft that nothing
  marks ready. Without `fail` it ends the run."
  ([repo base] (ready-workflow? repo base shell/die))
  ([repo base fail]
   (let [{:keys [exit out err]}
         (shell/run "gh" "api" (format "repos/%s/contents/%s?ref=%s" repo ready-workflow-path base) "--silent")]
     (cond (zero? exit) true
           (re-find #"404|Not Found" (str out err)) false
           :else (fail (ready-workflow-failure repo base exit (str/trim (str out err))))))))


(defn head-branch
  "The branch checked out now, by git."
  []
  (shell/git-out "rev-parse" "--abbrev-ref" "HEAD"))


(defn open-pr-url
  "The URL of the branch's pull request in this state, nil when it has none."
  [repo branch state]
  (not-empty (shell/gh! "pr" "list"
               "-R"      repo
               "--head"  branch
               "--state" state
               "--json"  "url"
               "--jq"    ".[0].url // empty")))


(defn read-pr
  "The pull request as data, see wait/read-pr; a failed read goes to `fail` with gh's message."
  [pr repo fail]
  (wait/read-pr pr repo fail))


(defn required-checks
  "The pull request's required checks, see wait/required-checks."
  [pr repo fail]
  (wait/required-checks pr repo fail))


(defn auto-merge!
  "Turns auto-merge on: {:exit :out :err}. finish, merge and release make this one call; each step decides what a
  refusal means."
  [pr-url]
  (shell/run "gh" "pr" "merge" pr-url "--auto" "--squash"))


(def shared-operations
  "The operations of the commands not yet converted to direct calls, by name; the plain functions above are what a
  converted command calls. Goes away with the last of them."
  {:head-branch     head-branch
   :open-pr-url     open-pr-url
   :read-pr         read-pr
   :required-checks required-checks
   :ready-workflow? ready-workflow?
   :auto-merge!     auto-merge!
   :issue-url       issue-url
   :set-field!      set-field!})


(def merge-operations
  "kit merge's operations. The git calls print to the terminal and end the run with git's own exit code when they fail,
  as the scripts did under `set -e`; so does a gh call made through shell/gh!."
  {:update-branch!
   (fn [pr-url] (shell/shell "gh" "pr" "update-branch" pr-url))

   :squash-merge!
   (fn [pr-url] (shell/shell "gh" "pr" "merge" pr-url "--squash"))

   :closing-issues
   (fn [pr-url fail]
     (:closingIssuesReferences (wait/gh-json fail "pr" "view" pr-url "--json" "closingIssuesReferences")))

   :issue-state
   (fn [url]
     (shell/gh! "issue" "view" url
       "--json" "state"
       "--jq"   ".state"))

   :close-issue!
   (fn [url note]
     (shell/run "gh" "issue" "close" url
       "--reason"  "completed"
       "--comment" note))

   :fetch-base!
   (fn [base] (shell/ok! (shell/shell "git" "fetch" "-q" "origin" base)))

   :worktree-branches
   (fn []
     (let [listing (shell/git-out "worktree" "list" "--porcelain")]
       (for [line  (str/split-lines listing)
             :when (str/starts-with? line "branch refs/heads/")]
         (str/replace-first line "branch refs/heads/" ""))))

   :detach-at-base!
   (fn [base] (shell/ok! (shell/shell "git" "switch" "-q" "--detach" (str "origin/" base))))

   :switch-to!
   (fn [branch] (shell/ok! (shell/shell "git" "switch" "-q" branch)))

   :pull-ff-only!
   (fn [] (shell/ok! (shell/shell "git" "pull" "-q" "--ff-only")))

   :delete-local-branch!
   (fn [branch] (shell/ok! (shell/shell "git" "branch" "-D" branch)))

   :remote-branch?
   (fn [branch]
     (zero? (:exit (shell/run "git" "ls-remote" "--exit-code" "--heads" "origin" branch))))

   :delete-remote-branch!
   (fn [branch] (shell/ok! (shell/shell "git" "push" "origin" "--delete" branch)))})


(def release-operations
  "kit release's operations."
  {:view-pr-for-release
   (fn [pr repo]
     (let [{:keys [state isDraft labels url]}
           (json/parse-string (shell/gh! "pr" "view" pr
                                "-R"     repo
                                "--json" "state,isDraft,labels,url")
                              true)]
       {:state state
        :draft isDraft
        :hold  (boolean (some #(= "hold" (:name %)) labels))
        :url   url}))

   :remove-label!
   (fn [pr repo label]
     (shell/ok! (shell/shell "gh" "pr" "edit" pr
                  "-R"             repo
                  "--remove-label" label)))

   :mark-ready!
   (fn [pr repo]
     (shell/ok! (shell/shell "gh" "pr" "ready" pr "-R" repo)))
  })


(def checks-operations
  "kit checks' operations. A gh call that gives no answer goes to the step's `fail` with gh's message."
  {:required-contexts
   (fn [repo base fail]
     (->> (wait/gh-json fail "api" (str "repos/" repo "/rules/branches/" base))
          (filter #(= "required_status_checks" (:type %)))
          (mapcat #(get-in % [:parameters :required_status_checks]))
          (map :context)
          distinct
          vec))

   :ready-runs
   (fn [repo fail]
     (wait/gh-json fail
                   "run"        "list"
                   "-R"         repo
                   "--workflow" (last (str/split ready-workflow-path #"/"))
                   "--event"    "workflow_run"
                   "--limit"    "20"
                   "--json"     "databaseId,status"))

   :run-log
   (fn [repo id]
     (:out (shell/run "gh" "run" "view" (str id) "-R" repo "--log")))})


(def reviews-query
  "A pull request's state, the head commit's date, its reviews and review threads (PullRequest.reviews, .reviewThreads
  and .commits, docs.github.com/en/graphql/reference/pulls), and who is asking (viewer). A thread's `line` is null once its code changed: `originalLine` then
  tells where it was."
  "query($owner: String!, $name: String!, $number: Int!, $reviewsAfter: String, $threadsAfter: String) {
      viewer { login }
      repository(owner: $owner, name: $name) {
        pullRequest(number: $number) {
          state
          commits(last: 1) { nodes { commit { committedDate } } }
          reviews(first: 100, after: $reviewsAfter) {
            pageInfo { hasNextPage endCursor }
            nodes { author { login } state submittedAt body }
          }
          reviewThreads(first: 100, after: $threadsAfter) {
            pageInfo { hasNextPage endCursor }
            nodes { isResolved path line originalLine comments(first: 100) { nodes { author { login } body } } }
          }
        }
      }
    }")


(defn- pr-coordinates
  "owner, name and number of a pull request given as a URL, or as a number with its repository."
  [pr repo]
  (if-let [[_ owner repo-name number] (re-find #"github\.com/([^/]+)/([^/]+)/pull/(\d+)" (str pr))]
    [owner repo-name number]
    (conj (str/split repo #"/" 2) (str pr))))


(defn- review-of
  [{:keys [author state submittedAt body]}]
  {:author (:login author) :state state :at submittedAt :body body})


(defn- thread-of
  [{:keys [isResolved path line originalLine comments]}]
  {:resolved? isResolved
   :path      path
   :line      (or line originalLine)
   :comments  (mapv (fn [{:keys [author body]}]
                      {:author (:login author) :body body})
                    (:nodes comments))})


(defn read-reviews
  "The pull request's {:state :viewer (the login of whoever runs kit) :head-committed (the head commit's date)
  :reviews :threads}, every page of both lists, one query for a page. A review is
  {:author :state :at :body} (a pending one, not submitted, is left out), a thread {:resolved? :path :line :comments}
  with its comments {:author :body}. The cursors of a list that has ended stay, and ask for the page after its last."
  [pr repo fail]
  (let [[owner repo-name number] (pr-coordinates pr repo)]
    (loop [reviews-after nil
           threads-after nil
           result        {:reviews [] :threads []}]
      (let [response (apply wait/gh-json
                            fail
                            "api"
                            "graphql"
                            "-f"
                            (str "query=" reviews-query)
                            "-f"
                            (str "owner=" owner)
                            "-f"
                            (str "name=" repo-name)
                            "-F"
                            (str "number=" number)
                            (concat (when reviews-after
                                      ["-f" (str "reviewsAfter=" reviews-after)])
                                    (when threads-after
                                      ["-f" (str "threadsAfter=" threads-after)])))
            request  (get-in response [:data :repository :pullRequest])]
        (when-not request
          (fail (wait/gh-failure-text "api graphql"
                                      (str (or (some-> response :errors first :message) "no such pull request")))))
        (let [{:keys [reviews reviewThreads]} request
              result (->
                      result
                      (assoc :state  (:state request)
                             :viewer (get-in response [:data :viewer :login])
                             :head-committed (get-in request [:commits :nodes 0 :commit :committedDate]))
                      (update :reviews into (comp (remove #(nil? (:submittedAt %))) (map review-of)) (:nodes reviews))
                      (update :threads into (map thread-of) (:nodes reviewThreads)))]
          (if (or (get-in reviews [:pageInfo :hasNextPage]) (get-in reviewThreads [:pageInfo :hasNextPage]))
            (recur (get-in reviews [:pageInfo :endCursor]) (get-in reviewThreads [:pageInfo :endCursor]) result)
            result))))))


(def review-operations
  "kit review's operations. A gh call that gives no answer goes to the step's `fail` with gh's message."
  {:read-reviews read-reviews})


(def command-operations
  "Each command's operations by the command, the shared ones under :shared."
  {:shared  shared-operations
   :merge   merge-operations
   :release release-operations
   :checks  checks-operations
   :review  review-operations})


(def operations
  "Every operation by name."
  (apply merge (vals command-operations)))


(defn real
  "The GitHub of a real run: (github :op args…) calls the operation of that name."
  [op & args]
  (let [operation (or (operations op) (throw (ex-info (str "no GitHub operation " op) {:op op})))]
    (apply operation args)))
