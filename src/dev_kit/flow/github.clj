(ns dev-kit.flow.github
  "The GitHub that kit start talks to, as named operations over data (#139, the pilot). A step calls
  `((:github ctx) :op args…)`; the real operations below are today's gh calls, moved unchanged and in the same
  order, since the selftest's fake gh checks them as strings. Tests hand an in-memory GitHub with the same
  operations instead. An operation returns data and decides nothing: refusing is the step's."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]))


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


(def operations
  "Each operation by name. Arguments and results are plain data; a failing gh call that is not the step's to judge
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


(defn real
  "The GitHub of a real run: (github :op args…) calls the operation of that name."
  [op & args]
  (let [operation (or (operations op) (throw (ex-info (str "no GitHub operation " op) {:op op})))]
    (apply operation args)))
