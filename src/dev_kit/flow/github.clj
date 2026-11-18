(ns dev-kit.flow.github
  "The GitHub (and git) that the flow commands talk to, as named operations over data (#139). A step calls
  `((:github ctx) :op args…)`; the real operations below are today's gh and git calls, moved unchanged and in the same
  order, since the selftest's fake gh checks them as strings. Tests hand an in-memory GitHub with the same
  operations instead. An operation returns data and decides nothing: refusing is the step's.

  The operations are kept one map per command, plus the shared ones, so each command is rewritten on its own; no
  name may appear in two maps."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]))


(def items-query
  "query($owner: String!, $number: Int!, $status: String!, $after: String) {
      owner: repositoryOwner(login: $owner) {
        ... on ProjectV2Owner {
          projectV2(number: $number) {
            items(first: 100, after: $after) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                isArchived
                status: fieldValueByName(name: $status) { ... on ProjectV2ItemFieldSingleSelectValue { name } }
                content {
                  __typename
                  ... on Issue { number url title state }
                  ... on PullRequest { number url title }
                  ... on DraftIssue { title }
                }
              }
            }
          }
        }
      }
    }")


(defn project-items
  "The board's cards, not archived ones: they are out of the CLI's listing too, and reusing one would resurrect work
  the owner had put away. Only the four fields a match needs are asked for: `gh project item-list` asks for every
  field of every card, 203 points of the 5000 hourly GraphQL budget on a board this size."
  [owner number status-field]
  (loop [after nil
         items []]
    (let [out   (apply shell/gh! "api" "graphql"
                  "-f" (str "query=" items-query)
                  "-F" (str "owner=" owner)
                  "-F" (str "number=" number)
                  "-f" (str "status=" status-field)
                  (when after
                    ["-F" (str "after=" after)]))
          conn  (get-in (json/parse-string out true) [:data :owner :projectV2 :items])
          items (into items
                      (for [{:keys [id isArchived status content]} (:nodes conn)
                            :when (not isArchived)]
                        {:id      id
                         :title   (:title content "")
                         :status  (:name status "")
                         :content {:type   (:__typename content)
                                   :number (:number content)
                                   :url    (:url content)
                                   :state  (:state content)}}))]
      (if (get-in conn [:pageInfo :hasNextPage])
        (recur (get-in conn [:pageInfo :endCursor]) items)
        items))))


(def convert-mutation
  "The schema (introspected, #12): input {itemId, repositoryId}, payload {item: ProjectV2Item}."
  "mutation($itemId:ID!,$repositoryId:ID!){ convertProjectV2DraftIssueItemToIssue(input:{itemId:$itemId,repositoryId:$repositoryId}) { item { content { ... on Issue { number url } } } } }")


(def start-operations
  "kit start's operations. Arguments and results are plain data; a failing gh call that is not the step's to judge
  ends the run inside shell/gh!, as before the pilot."
  {:open-milestones
   (fn [repo]
     (str/split-lines (shell/gh! "api" (str "repos/" repo "/milestones?state=open") "--paginate" "--jq" ".[].title")))

   :retro-missing
   (fn [kit repo]
     (shell/run "bb" kit "retro" "--missing" "--repo" repo))

   :graphql-remaining
   (fn []
     (some-> (shell/gh-out "api" "rate_limit" "--jq" ".resources.graphql.remaining")
             not-empty
             parse-long))

   :issue-url
   (fn [repo number]
     (shell/run "gh" "issue" "view" number
       "-R"     repo
       "--json" "url"
       "--jq"   ".url"))

   :project-items
   project-items

   :open-issues-titled
   (fn [repo title]
     (let [out (shell/gh-out "issue" "list"
                 "-R"       repo
                 "--state"  "open"
                 "--search" (str title " in:title")
                 "--limit"  "20"
                 "--json"   "number,title,url")]
       (json/parse-string (or out "[]") true)))

   :repo-id
   (fn [repo]
     (shell/gh! "repo" "view" repo
       "--json" "id"
       "--jq"   ".id"))

   :convert-draft!
   (fn [item-id repo-id]
     (let [out (shell/gh! "api" "graphql"
                 "-f" (str "query=" convert-mutation)
                 "-F" (str "itemId=" item-id)
                 "-F" (str "repositoryId=" repo-id))]
       (get-in (json/parse-string out true) [:data :convertProjectV2DraftIssueItemToIssue :item :content])))

   :known-labels
   (fn [repo]
     (let [out (shell/gh-out "label" "list"
                 "-R"      repo
                 "--limit" "200"
                 "--json"  "name")]
       (try (set (map :name (json/parse-string out true)))
            (catch Exception _ #{}))))

   :create-label!
   (fn [repo label]
     (zero? (:exit (shell/run "gh" "label" "create" label "-R" repo))))

   :file-issue!
   (fn [repo title body-args label-args assignee-args]
     (shell/last-line
      (apply shell/gh! "issue" "create"
        "-R"      repo
        "--title" title
        (concat body-args label-args assignee-args))))

   :create-draft!
   (fn [project-number owner title body]
     (let [out (apply shell/gh! "project" "item-create" project-number
                 "--owner"  owner
                 "--title"  title
                 "--format" "json"
                 (when body
                   ["--body" body]))]
       (:id (json/parse-string out true))))

   :item-add!
   (fn [project-number owner url]
     (try {:id (board/board-gh! "project" "item-add" project-number
                 "--owner"  owner
                 "--url"    url
                 "--format" "json"
                 "--jq"     ".id")}
          (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))

   :edit-issue!
   (fn [repo number args]
     (apply shell/gh! "issue" "edit" number "-R" repo args))

   :set-field!
   (fn [project-number owner url field option] (board/board-set-field project-number owner url field option))

   :size-label!
   (fn [repo number size]
     (shell/run "gh" "label" "create" (str "size:" size)
       "-R"            repo
       "--color"       "BFD4F2"
       "--description" "Task size (ADR-0002), mirrors the board field"
       "--force")
     (let [old  (some-> (shell/gh-out "issue" "view" number
                          "-R"     repo
                          "--json" "labels"
                          "--jq"   ".labels[].name | select(startswith(\"size:\"))")
                        str/split-lines)
           gone (mapcat (fn [l] ["--remove-label" l]) (remove #(or (str/blank? %) (= % (str "size:" size))) old))]
       (zero? (:exit (apply shell/run "gh" "issue" "edit" number
                       "-R"          repo
                       "--add-label" (str "size:" size)
                       gone)))))

   :linked-branch
   (fn [repo number]
     (some-> (shell/gh-out "issue" "develop"
               "--list" number
               "-R"     repo)
             str/split-lines
             first
             (str/split #"\t")
             first
             not-empty))

   :develop!
   (fn [repo number develop-args]
     (shell/last-line (apply shell/gh! "issue" "develop" number "-R" repo develop-args)))

   :current-branch
   (fn [] (some-> (shell/run "git" "branch" "--show-current") :out str/trim))})


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure ends the
  run: guessing would open a ready PR in a repository that has the workflow, or a draft that nothing marks ready."
  [repo base]
  (let [{:keys [exit out err]}
        (shell/run "gh" "api" (str "repos/" repo "/contents/" ready-workflow-path "?ref=" base) "--silent")]
    (cond
      (zero? exit) true
      (re-find #"404|Not Found" (str out err)) false
      :else
      (let [said (or (not-empty (str/trim (str out err))) (str "gh exit code " exit))]
        (shell/die
         (format
          "Could not look for %s on '%s' of %s (%s). Nothing was committed or pushed: run it again when GitHub answers, or check 'gh auth status'."
          ready-workflow-path
          base
          repo
          said))))))


(def shared-operations
  "Operations more than one command uses, with the same gh or git call."
  {:head-branch
   (fn [] (shell/git-out "rev-parse" "--abbrev-ref" "HEAD"))

   :open-pr-url
   (fn [repo branch state]
     (not-empty (shell/gh! "pr" "list"
                  "-R"      repo
                  "--head"  branch
                  "--state" state
                  "--json"  "url"
                  "--jq"    ".[0].url // empty")))

   :read-pr
   (fn [pr repo fail] (wait/read-pr pr repo fail))

   :required-checks
   (fn [pr repo fail] (wait/required-checks pr repo fail))

   :ready-workflow?
   (fn [repo base] (ready-workflow? repo base))

   ;; finish, merge and release turn auto-merge on with this one call; each step decides what a refusal means.
   :auto-merge!
   (fn [pr-url] (shell/run "gh" "pr" "merge" pr-url "--auto" "--squash"))})


(def finish-operations
  "kit finish's operations."
  {})


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
   (fn [issue-url]
     (shell/gh! "issue" "view" issue-url
       "--json" "state"
       "--jq"   ".state"))

   :close-issue!
   (fn [issue-url note]
     (shell/run "gh" "issue" "close" issue-url
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
  "kit checks' operations."
  {})


(def command-operations
  "Each command's operations by the command, the shared ones under :shared."
  {:shared  shared-operations
   :start   start-operations
   :finish  finish-operations
   :merge   merge-operations
   :release release-operations
   :checks  checks-operations})


(def operations
  "Every operation by name."
  (apply merge (vals command-operations)))


(defn real
  "The GitHub of a real run: (github :op args…) calls the operation of that name."
  [op & args]
  (let [operation (or (operations op) (throw (ex-info (str "no GitHub operation " op) {:op op})))]
    (apply operation args)))
