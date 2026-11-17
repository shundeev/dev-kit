(ns dev-kit.flow.start
  "kit start: files the issue or reuses one, puts it on the board and creates its branch."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]))


;; ------------------------------------------------------------------ kit start


(def start-usage
  "Usage: kit start --title <title> [options]

Create work in GitHub Project, assign fields, ensure issue exists, and create/checkout dev branch.

Options:
  --title <text>                Required issue or draft title
  --body <text>                 Body text
  --body-file <path>            Body file path
  --labels <csv>                Comma-separated labels
  --assignees <csv>             Comma-separated assignees (supports @me)
  --issue <number>              Reuse existing issue number instead of creating one
  --showcase <option>           Showcase field option (default: GHWF_SHOWCASE)
  --priority <option>           Required. Priority field option: Urgent|High|Medium|Low
  --size <option>               Size field option to set
  --milestone <title>           Open milestone to put the issue in (must already exist)
  --status <option>             Status field option to set (default: In progress with --issue, else
                                GHWF_DEFAULT_STATUS or Backlog)
  --mode <issue|draft-convert>  Default: issue
  --owner <login>               Project owner (default: GHWF_OWNER)
  --repo <owner/repo>           Repo (default: the folder's origin on GitHub, then GHWF_REPO)
  --project-number <number>     Project number (default: GHWF_PROJECT_NUMBER)
  --showcase-field <name>       Field name (default: GHWF_SHOWCASE_FIELD or Showcase)
  --priority-field <name>       Field name (default: GHWF_PRIORITY_FIELD or Priority)
  --size-field <name>           Field name (default: GHWF_SIZE_FIELD or Size)
  --status-field <name>         Field name (default: GHWF_STATUS_FIELD or Status)
  --base <branch>               Base branch for dev branch (default: GHWF_DEFAULT_BASE or main)
  --branch-name <name>          Override branch name
  --no-reuse-existing           Do not reuse exact-title project item or issue
  --skip-branch                 Do not create the development branch at all
  --no-checkout                 Create and link the branch, leave the calling worktree alone
                                (mutually exclusive with --skip-branch)
  --skip-retro                  Start although a merged issue lacks its retrospective. Only the owner
                                waives this: a permission rule asks them first (#56)
  --help                        Show this help")


(def start-spec
  {"--title"          [:title :value]
   "--body"           [:body :value]
   "--body-file"      [:body-file :value]
   "--labels"         [:labels :value]
   "--assignees"      [:assignees :value]
   "--issue"          [:issue :value]
   "--showcase"       [:showcase :value]
   "--priority"       [:priority :value]
   "--size"           [:size :value]
   "--milestone"      [:milestone :value]
   "--status"         [:status :value]
   "--mode"           [:mode :value]
   "--owner"          [:owner :value]
   "--repo"           [:repo :value]
   "--project-number" [:project-number :value]
   "--showcase-field" [:showcase-field :value]
   "--priority-field" [:priority-field :value]
   "--size-field"     [:size-field :value]
   "--status-field"   [:status-field :value]
   "--base"           [:base :value]
   "--branch-name"    [:branch-name :value]
   "--no-reuse-existing" [:no-reuse :flag]
   "--skip-branch"    [:skip-branch :flag]
   "--no-checkout"    [:no-checkout :flag]
   "--skip-retro"     [:skip-retro :flag]
   "--help"           [:help :flag]
   "-h"               [:help :flag]})


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
    (let [page  (json/parse-string
                 (apply shell/gh!
                        "api"
                        "graphql"
                        "-f"
                        (str "query=" items-query)
                        "-F"
                        (str "owner=" owner)
                        "-F"
                        (str "number=" number)
                        "-f"
                        (str "status=" status-field)
                        (when after ["-F" (str "after=" after)]))
                 true)
          conn  (get-in page [:data :owner :projectV2 :items])
          items (into items
                      (for [n     (:nodes conn)
                            :when (not (:isArchived n))]
                        {:id      (:id n)
                         :title   (get-in n [:content :title] "")
                         :status  (get-in n [:status :name] "")
                         :content {:type   (get-in n [:content :__typename])
                                   :number (get-in n [:content :number])
                                   :url    (get-in n [:content :url])
                                   :state  (get-in n [:content :state])}}))]
      (if (get-in conn [:pageInfo :hasNextPage])
        (recur (get-in conn [:pageInfo :endCursor]) items)
        items))))


(defn find-item-by-title
  "One board serves several repositories, so a title only matches an issue of this repo, or a draft, which belongs to
  no repo yet. Only open work counts (#12): a closed issue or a Done card with a recurring title is history."
  [items title repo]
  (let [done (shell/env "GHWF_DONE_STATUS" "Done")]
    (some #(when (and (= title (:title %))
                      (or (= "DraftIssue" (get-in % [:content :type]))
                          (and (= "Issue" (get-in % [:content :type]))
                               (str/starts-with? (or (get-in % [:content :url]) "")
                                                 (str "https://github.com/" repo "/"))))
                      (not= "CLOSED" (get-in % [:content :state]))
                      (not= done (:status %)))
             %)
          items)))


(defn start!
  [kit args]
  (let
    [o            (shell/parse-opts start-spec args)
     _ (when (:help o) (println start-usage) (System/exit 0))
     title        (:title o)
     owner        (or (:owner o) (shell/env "GHWF_OWNER" nil))
     project-number (or (:project-number o) (shell/env "GHWF_PROJECT_NUMBER" nil))
     showcase-field (or (:showcase-field o) (shell/env "GHWF_SHOWCASE_FIELD" "Showcase"))
     priority-field (or (:priority-field o) (shell/env "GHWF_PRIORITY_FIELD" "Priority"))
     size-field   (or (:size-field o) (shell/env "GHWF_SIZE_FIELD" "Size"))
     status-field (or (:status-field o) (shell/env "GHWF_STATUS_FIELD" "Status"))
     base         (or (:base o) (shell/env "GHWF_DEFAULT_BASE" "main"))
     assignees    (or (:assignees o) (shell/env "GHWF_DEFAULT_ASSIGNEES" "@me"))
     showcase     (or (:showcase o) (shell/env "GHWF_SHOWCASE" nil))
     priority     (or (:priority o) (shell/env "GHWF_DEFAULT_PRIORITY" nil))
     size         (or (:size o) (shell/env "GHWF_DEFAULT_SIZE" nil))
     status       (or (:status o) (when (:issue o) "In progress") (shell/env "GHWF_DEFAULT_STATUS" "Backlog"))
     mode         (or (:mode o) "issue")
     _ (when (str/blank? title) (shell/die "--title is required"))
     _ (when (str/blank? owner) (shell/die "--owner or GHWF_OWNER is required"))
     repo         (shell/resolve-repo (:repo o))
     _ (when (str/blank? project-number) (shell/die "--project-number or GHWF_PROJECT_NUMBER is required"))
     _ (when-not (#{"issue" "draft-convert"} mode) (shell/die "--mode must be 'issue' or 'draft-convert'"))
     _
     (when (and (:skip-branch o) (:no-checkout o))
       (shell/die
        "--skip-branch and --no-checkout are mutually exclusive: one creates no branch, the other creates one without touching the working tree"))]
    ;; Checked before anything is created: a run that files the issue and then dies on the priority leaves
    ;; half-tracked work behind.
    (cond (str/blank? priority) (shell/die "--priority is required: Urgent|High|Medium|Low")
          (not (#{"Urgent" "High" "Medium" "Low"} priority))
          (shell/die (str "Priority '" priority "' is not on the scale Urgent|High|Medium|Low")))
    ;; An unknown milestone stops the run before an issue exists. Milestones are never created here.
    (when-let [m (:milestone o)]
      (let [open (str/split-lines
                  (shell/gh! "api" (str "repos/" repo "/milestones?state=open") "--paginate" "--jq" ".[].title"))]
        (when-not (some #{m} open)
          (shell/die (str "Milestone '" m "' is not an open milestone of '" repo "'. Open: " (str/join "/" open))))))
    ;; A merged task's retrospective comes before new work (#28, #56). When GitHub cannot be asked, say so and go
    ;; on: the check guards the process, not data.
    (when-not (:skip-retro o)
      (let [{:keys [exit out err]} (shell/run "bb" kit "retro" "--missing" "--repo" repo)
            said (str/trim (str out err))]
        (case (int exit)
          0 nil
          1 (shell/die said)
          (shell/warn "Warning: could not check for merged issues without a retrospective:" said))))
    (let
      [budget #(some-> (shell/gh-out "api" "rate_limit" "--jq" ".resources.graphql.remaining") not-empty parse-long)
       before (budget)
       labels (atom nil)
       known? (fn [l]
                (when-not @labels
                  (reset! labels (try (set (map :name
                                                (json/parse-string
                                                 (shell/gh-out "label" "list" "-R" repo "--limit" "200" "--json" "name")
                                                 true)))
                                      (catch Exception _ #{}))))
                (@labels l))
       csv #(remove str/blank? (map str/trim (str/split (or % "") #",")))
       label-args
       (fn [flag]
         (mapcat (fn [l]
                   (when-not (known? l)
                     (when-not (zero? (:exit (shell/run "gh" "label" "create" l "-R" repo)))
                       (shell/die
                        (str
                         "Label '"
                         l
                         "' does not exist in "
                         repo
                         " and could not be created: create it on GitHub (Issues > Labels), or drop it from --labels")))
                     (swap! labels conj l))
                   [flag l])
          (csv (:labels o))))
       assignee-args (fn [flag] (mapcat (fn [a] [flag a]) (csv assignees)))
       item-add
       (fn [url what & [then]]
         (try (board/board-gh! "project"
                               "item-add" project-number
                               "--owner"  owner
                               "--url"    url
                               "--format" "json"
                               "--jq"     ".id")
              (catch clojure.lang.ExceptionInfo e (shell/die (str what (board/board-reason (ex-message e)) then)))))
       convert
       (fn [item-id]
         (let
           [rid (shell/gh! "repo" "view" repo "--json" "id" "--jq" ".id")
            _ (when (str/blank? rid) (shell/die "Failed to resolve repository id"))
            ;; The schema (introspected, #12): input {itemId, repositoryId}, payload {item: ProjectV2Item}.
            q
            "mutation($itemId:ID!,$repositoryId:ID!){ convertProjectV2DraftIssueItemToIssue(input:{itemId:$itemId,repositoryId:$repositoryId}) { item { content { ... on Issue { number url } } } } }"
            c (get-in (json/parse-string (shell/gh! "api" "graphql"
                                                    "-f"  (str "query=" q)
                                                    "-F"  (str "itemId=" item-id)
                                                    "-F"  (str "repositoryId=" rid))
                                         true)
                      [:data :convertProjectV2DraftIssueItemToIssue :item :content])]
           (when-not (and (:number c) (:url c)) (shell/die "Draft item conversion to issue failed"))
           [(str (:number c)) (:url c)]))
       use-item (fn [item]
                  (let [id (or (:id item) (shell/die "Existing project item has no id"))]
                    (case (get-in item [:content :type])
                      "Issue"      (let [n (get-in item [:content :number])
                                         u (get-in item [:content :url])]
                                     (when-not (and n u) (shell/die "Existing issue item is missing issue data"))
                                     [(str n) u id])
                      "DraftIssue" (conj (convert id) id)
                      (shell/die (str "Existing project item '" title "' is not an issue or draft issue")))))
       ;; --issue N names the work: no title is matched, with reuse on or off (#12).
       [number url item-id]
       (cond
         (:issue o)
         (let [n (:issue o)
               {:keys [exit out err]} (shell/run "gh" "issue" "view" n "-R" repo "--json" "url" "--jq" ".url")
               u (str/trim out)]
           (when-not (zero? exit)
             (shell/die
              (str
               "Issue #"
               n
               " not found in "
               repo
               " ("
               (str/trim (str out err))
               "): check the number, start without --issue, or pass --repo owner/name if that is the wrong repository")))
           [n u (item-add u (str "#" n " could not be put on the board: "))])

         (not (:no-reuse o))
         (if-let [item (find-item-by-title (project-items owner project-number status-field) title repo)]
           (use-item item)
           (when-let [issue (some #(when (= title (:title %)) %)
                                  (json/parse-string (or (shell/gh-out "issue" "list"
                                                                       "-R" repo
                                                                       "--state" "open"
                                                                       "--search"
                                                                       (str title " in:title")
                                                                       "--limit" "20"
                                                                       "--json" "number,title,url")
                                                         "[]")
                                                     true))]
             (let [n (str (:number issue))
                   u (:url issue)]
               (when (str/blank? u) (shell/die "Existing issue is missing issue data"))
               [n u (item-add u (str "#" n " could not be put on the board: "))]))))
       [number url item-id]
       (cond
         number [number url item-id]

         (= mode "issue")
         (let [u (shell/last-line (apply shell/gh!
                                         (concat ["issue" "create" "-R" repo "--title" title]
                                                 ;; gh issue create without a body asks for one, and without a terminal fails (#12)
                                                 (if (:body-file o)
                                                   ["--body-file" (:body-file o)]
                                                   ["--body" (or (:body o) "")])
                                                 (label-args "--label")
                                                 (assignee-args "--assignee"))))
               _ (when (str/blank? u) (shell/die "Issue creation failed"))
               n (last (str/split u #"/"))]
           [n u
            (item-add u
                      (str "#" n " is filed but could not be put on the board: ")
                      (str ". Then run kit start again with --issue " n))])

         :else
         (let [body (if (:body-file o)
                      (shell/file-text (:body-file o))
                      (not-empty (:body o)))
               item (json/parse-string (apply shell/gh!
                                              (concat ["project" "item-create" project-number "--owner" owner "--title"
                                                       title "--format" "json"]
                                                      (when body ["--body" body])))
                                       true)
               id   (or (not-empty (:id item)) (shell/die "Draft item creation failed"))]
           (conj (convert id) id)))]
      (let [edit (concat (label-args "--add-label")
                         (assignee-args "--add-assignee")
                         (when (:milestone o) ["--milestone" (:milestone o)]))]
        (when (seq edit)
          (apply shell/gh! "issue" "edit" number "-R" repo edit)))
      ;; A failed board update ends the run (#41): the issue exists, the board does not show it as asked.
      (let [field! (fn [field option]
                     (when-not (str/blank? option)
                       (when-let [e (board/board-set-field project-number owner url field option)]
                         (shell/die (str "#" number
                                         " is filed, but its " field
                                         " on the board is not '" option
                                         "': "
                                         (board/board-reason e)
                                         ". Then run kit start again with --issue " number)))))]
        (field! showcase-field showcase)
        (field! priority-field priority)
        (field! size-field size)
        ;; The board's Size, mirrored as a size:* label: CI's design approval gate reads labels, it cannot read a
        ;; user's board (#26). Other size labels are removed.
        (when-not (str/blank? size)
          (shell/run "gh"
                     "label"
                     "create"
                     (str "size:" size)
                     "-R"
                     repo
                     "--color"
                     "BFD4F2"
                     "--description"
                     "Task size (ADR-0002), mirrors the board field" "--force")
          (let [old  (some-> (shell/gh-out "issue"
                                           "view" number
                                           "-R" repo
                                           "--json" "labels"
                                           "--jq"
                                           ".labels[].name | select(startswith(\"size:\"))")
                             str/split-lines)
                gone (mapcat (fn [l] ["--remove-label" l]) (remove #(or (str/blank? %) (= % (str "size:" size))) old))]
            (when-not (zero?
                       (:exit
                        (apply shell/run "gh" "issue" "edit" number "-R" repo "--add-label" (str "size:" size) gone)))
              (shell/warn (str "Warning: could not set the label size:" size " on #" number)))))
        (field! status-field status))
      (let [branch
            (when-not (:skip-branch o)
              ;; `gh issue develop` with no --name appends -1 to a name already taken, so a rerun would fork a
              ;; duplicate branch. Handing it the branch already linked to the issue makes the run idempotent.
              (let [named (or (:branch-name o)
                              (some-> (shell/gh-out "issue" "develop" "--list" number "-R" repo)
                                      str/split-lines
                                      first
                                      (str/split #"\t")
                                      first
                                      not-empty))
                    out   (shell/last-line (apply shell/gh!
                                                  (concat ["issue" "develop" number "-R" repo]
                                                          (when-not (:no-checkout o) ["--checkout"])
                                                          (when-not (str/blank? base) ["--base" base])
                                                          (when named ["--name" named]))))]
                ;; --no-checkout leaves the working tree alone, so the name comes from the URL gh prints.
                (if (:no-checkout o)
                  (second (re-find #"/tree/(.*)$" (or out "")))
                  (some-> (shell/run "git" "branch" "--show-current") :out str/trim))))]
        (println (str "Issue URL: " url))
        (println (str "Issue Number: " number))
        (println (str "Project Item ID: " item-id))
        (when-not (str/blank? branch)
          (println (str (if (:no-checkout o) "Branch: " "Checked Out Branch: ") branch)))
        ;; A run that quietly eats a tenth of the hourly budget is only noticed an hour later. Print the price.
        (when before
          (when-let [after (budget)]
            (println (str "GraphQL Cost: " (- before after) " points (" after " left this hour)"))))))))
