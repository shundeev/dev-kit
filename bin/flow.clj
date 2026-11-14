;; The GitHub steps of the delivery flow (#95, ADR-0005), loaded by bin/kit:
;;
;;   kit start    files the issue (or reuses one), puts it on the board with its fields, creates its branch
;;   kit finish   commits, pushes, opens the pull request (a draft where main holds the ready workflow, ADR-0007;
;;                otherwise ready, with auto-merge on), sets the card In review
;;   kit release  a held pull request: removes the label hold, marks it ready, turns auto-merge on (ADR-0007)
;;   kit merge    after the owner's Approve: waits for the merge, sets the card Done, removes the branch
;;   kit checks   waits until the pull request's required checks exist and have finished, and says how they ended (#109)
;;   kit board set <project-number> <owner> <issue-url> <field> <option>   one field of one card (#41)
;;
;; They were start_issue_flow.sh, finish_issue_flow.sh, merge_issue_flow.sh and board.sh: the same options, the
;; same output lines and exit codes. gh's JSON is read as data, so the flow needs no jq.
(ns flow
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))


;; ------------------------------------------------------------------ plumbing


(defn die
  [& msg]
  (binding [*out* *err*] (println (str "Error: " (str/join " " msg))))
  (System/exit 1))


(defn warn
  [& msg]
  (binding [*out* *err*] (println (str/join " " msg))))


(defn run
  "A command's {:exit :out :err}, its output captured."
  [& args]
  (apply p/sh {:continue true} args))


(defn shell
  "A command whose output goes to ours, as in a terminal; its exit code."
  [& args]
  (:exit @(apply p/process {:inherit true} args)))


(defn gh-out
  "gh's trimmed stdout; nil when it fails."
  [& args]
  (let [{:keys [exit out]} (apply run "gh" args)]
    (when (zero? exit) (str/trim out))))


(defn ok!
  "Ends the run with a failed command's own exit code, as the scripts did under `set -e`."
  [exit]
  (when-not (zero? exit) (System/exit exit)))


(defn gh!
  "gh's trimmed stdout, as `$(gh …)` read it: its messages go to our stderr, and a failure ends the run with gh's exit
  code. A failure that says nothing is named here: the command and its exit code."
  [& args]
  (let [{:keys [exit out err]} @(apply p/process {:out :string :err :string} "gh" args)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (print err)
        (flush)))
    (when (and (not (zero? exit)) (str/blank? err))
      (warn (str "Error: gh " (str/join " " (take 2 args))
                 " failed with exit code " exit
                 " and printed no message. "
                 "Run the same call by hand to see why.")))
    (ok! exit)
    (str/trim out)))


(defn file-text
  "A file as `$(cat file)` reads it: without its trailing newlines."
  [path]
  (str/replace (slurp path) #"\n+$" ""))


(defn last-line [s] (some-> s str/split-lines last (str/replace "\r" "") str/trim))


(defn shell-quote
  "A word as bash's printf %q would write it."
  [s]
  (if (re-matches #"[A-Za-z0-9_@%+=:,./-]+" s)
    s
    (str "'" (str/replace s "'" "'\\''") "'")))


(defn parse-opts
  "Options against a spec: {\"--flag\" [:key :value|:flag|:many]}. Unknown ones end the run."
  [spec args]
  (loop [[a & more] args
         opts       {}]
    (if (nil? a)
      opts
      (let [[k kind] (spec a)]
        (case kind
          :value (recur (rest more) (assoc opts k (first more)))
          :many  (recur (rest more) (update opts k (fnil conj []) (first more)))
          ;; --help answers at once, as the scripts' `case` did, whatever follows it.
          :flag  (if (= k :help)
                   {:help true}
                   (recur more (assoc opts k true)))
          (die "Unknown argument:" a))))))


(defn env [k default] (or (not-empty (System/getenv k)) default))


(defn resolve-repo
  "owner/name: --repo, then the folder's GitHub origin, then GHWF_REPO. The folder comes before the session (#81):
  a session opened in one project may work in another repository's worktree."
  [repo]
  (or
   (not-empty repo)
   (let [url (some-> (run "git" "remote" "get-url" "origin")
                     :out
                     str/trim
                     (str/replace #"/$" "")
                     (str/replace #"\.git$" ""))]
     (second (re-find #"github\.com[:/]([^/]+/[^/]+)$" (or url ""))))
   (not-empty (System/getenv "GHWF_REPO"))
   (do
     (binding [*out* *err*]
       (println
        "Error: no repository: this folder has no GitHub origin and GHWF_REPO is not set; run inside the clone, or pass --repo owner/name"))
     (System/exit 1))))


(defn issue-branch-number [branch] (second (re-matches #"(\d+)-.*" branch)))


;; ------------------------------------------------------------------ board (#22, #41)


(defn board-hint
  "The likely cause of a board error and its fix, or nil."
  [msg]
  (cond
    (re-find #"INSUFFICIENT_SCOPES|required scopes|missing required scope" msg)
    (str "the gh login in use lacks the project scope: in your own terminal, GH_CONFIG_DIR="
         (env "GH_CONFIG_DIR" "~/.config/gh")
         " gh auth refresh -h github.com -s project")

    (re-find #"Could not resolve to a ProjectV2|Could not resolve to a User|NOT_FOUND" msg)
    "no access to the board: check that it exists and that this gh account can write to it (the board's Settings > Manage access)"

    (re-find #"HTTP 5|Bad Gateway|Service Unavailable|timed out|timeout|connection reset|Something went wrong" msg)
    "GitHub had an error: run the command again"))


(defn board-reason [msg] (str msg (some->> (board-hint msg) (str "; "))))


(defn- board-gh!
  "gh, run once more after a passing GitHub error (#41). Its trimmed stdout, or an exception with its message."
  [& args]
  (let [once #(apply run "gh" args)
        r    (once)
        r    (if (and (not (zero? (:exit r))) (some-> (board-hint (:err r)) (str/starts-with? "GitHub had an error")))
               (once)
               r)]
    (if (zero? (:exit r))
      (str/trim (:out r))
      (throw (ex-info (str/trim (:err r)) {:board true})))))


(def ^:private board-cache (atom {}))


(defn board-set-field
  "Sets a single-select field of an issue's card. The card is found through the project: `item-add` returns the card
  an issue already has and adds a missing one. gh's by-URL mode found it through the issue, and an organization's
  issue does not list a user's board (#22). nil, or why it failed."
  [number owner url field option]
  (try
    (let [number (str number)
          item   (board-gh! "project" "item-add" number "--owner" owner "--url" url "--format" "json" "--jq" ".id")
          pid    (or (:pid @board-cache)
                     (:pid (swap! board-cache assoc
                             :pid
                             (board-gh! "project" "view" number "--owner" owner "--format" "json" "--jq" ".id"))))
          fields (or (:fields @board-cache)
                     (:fields (swap! board-cache assoc
                                :fields
                                (json/parse-string (board-gh! "project"
                                                              "field-list" number
                                                              "--owner"    owner
                                                              "--format"   "json"
                                                              "--limit"    "100")
                                                   true))))
          f      (some #(when (= field (:name %)) %) (:fields fields))
          o      (some #(when (= option (:name %)) %) (:options f))]
      (cond
        (nil? f) (str "board "
                      number
                      " has no field '"
                      field
                      "'; its fields: "
                      (str/join ", " (map :name (:fields fields)))
                      ". Use one of them, or add the field on the board")
        (nil? o) (str "the field '"
                      field
                      "' of board "
                      number
                      " has no option '"
                      option
                      "'; its options: "
                      (str/join ", " (map :name (:options f)))
                      ". Use one of them, or add the option on the board")
        :else    (do (board-gh! "project"      "item-edit"
                                "--id"         item
                                "--project-id" pid
                                "--field-id"   (:id f)
                                "--single-select-option-id" (:id o))
                     nil)))
    (catch clojure.lang.ExceptionInfo e (ex-message e))))


(defn board-set-command
  "The command that sets one field alone, as an error prints it."
  [kit number owner url field option]
  (str/join " " (map shell-quote ["bb" kit "board" "set" (str number) owner url field option])))


(defn board!
  [_kit args]
  (when-not (and (= "set" (first args)) (= 6 (count args)))
    (binding [*out* *err*] (println "Usage: kit board set <project-number> <owner> <issue-url> <field> <option>"))
    (System/exit 2))
  (let [[_ number owner url field option] args]
    (when-let [e (board-set-field number owner url field option)]
      (binding [*out* *err*] (println e))
      (System/exit 1))
    (println (str field ": " option))))


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
                 (apply gh!
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
  (let [done (env "GHWF_DONE_STATUS" "Done")]
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
    [o            (parse-opts start-spec args)
     _ (when (:help o) (println start-usage) (System/exit 0))
     title        (:title o)
     owner        (or (:owner o) (env "GHWF_OWNER" nil))
     project-number (or (:project-number o) (env "GHWF_PROJECT_NUMBER" nil))
     showcase-field (or (:showcase-field o) (env "GHWF_SHOWCASE_FIELD" "Showcase"))
     priority-field (or (:priority-field o) (env "GHWF_PRIORITY_FIELD" "Priority"))
     size-field   (or (:size-field o) (env "GHWF_SIZE_FIELD" "Size"))
     status-field (or (:status-field o) (env "GHWF_STATUS_FIELD" "Status"))
     base         (or (:base o) (env "GHWF_DEFAULT_BASE" "main"))
     assignees    (or (:assignees o) (env "GHWF_DEFAULT_ASSIGNEES" "@me"))
     showcase     (or (:showcase o) (env "GHWF_SHOWCASE" nil))
     priority     (or (:priority o) (env "GHWF_DEFAULT_PRIORITY" nil))
     size         (or (:size o) (env "GHWF_DEFAULT_SIZE" nil))
     status       (or (:status o) (when (:issue o) "In progress") (env "GHWF_DEFAULT_STATUS" "Backlog"))
     mode         (or (:mode o) "issue")
     _ (when (str/blank? title) (die "--title is required"))
     _ (when (str/blank? owner) (die "--owner or GHWF_OWNER is required"))
     repo         (resolve-repo (:repo o))
     _ (when (str/blank? project-number) (die "--project-number or GHWF_PROJECT_NUMBER is required"))
     _ (when-not (#{"issue" "draft-convert"} mode) (die "--mode must be 'issue' or 'draft-convert'"))
     _
     (when (and (:skip-branch o) (:no-checkout o))
       (die
        "--skip-branch and --no-checkout are mutually exclusive: one creates no branch, the other creates one without touching the working tree"))]
    ;; Checked before anything is created: a run that files the issue and then dies on the priority leaves
    ;; half-tracked work behind.
    (cond (str/blank? priority) (die "--priority is required: Urgent|High|Medium|Low")
          (not (#{"Urgent" "High" "Medium" "Low"} priority))
          (die (str "Priority '" priority "' is not on the scale Urgent|High|Medium|Low")))
    ;; An unknown milestone stops the run before an issue exists. Milestones are never created here.
    (when-let [m (:milestone o)]
      (let [open (str/split-lines
                  (gh! "api" (str "repos/" repo "/milestones?state=open") "--paginate" "--jq" ".[].title"))]
        (when-not (some #{m} open)
          (die (str "Milestone '" m "' is not an open milestone of '" repo "'. Open: " (str/join "/" open))))))
    ;; A merged task's retrospective comes before new work (#28, #56). When GitHub cannot be asked, say so and go
    ;; on: the check guards the process, not data.
    (when-not (:skip-retro o)
      (let [{:keys [exit out err]} (run "bb" kit "retro" "--missing" "--repo" repo)
            said (str/trim (str out err))]
        (case (int exit)
          0 nil
          1 (die said)
          (warn "Warning: could not check for merged issues without a retrospective:" said))))
    (let
      [budget #(some-> (gh-out "api" "rate_limit" "--jq" ".resources.graphql.remaining") not-empty parse-long)
       before (budget)
       labels (atom nil)
       known? (fn [l]
                (when-not @labels
                  (reset! labels (try (set (map :name
                                                (json/parse-string
                                                 (gh-out "label" "list" "-R" repo "--limit" "200" "--json" "name")
                                                 true)))
                                      (catch Exception _ #{}))))
                (@labels l))
       csv #(remove str/blank? (map str/trim (str/split (or % "") #",")))
       label-args
       (fn [flag]
         (mapcat (fn [l]
                   (when-not (known? l)
                     (when-not (zero? (:exit (run "gh" "label" "create" l "-R" repo)))
                       (die
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
         (try (board-gh! "project" "item-add" project-number "--owner" owner "--url" url "--format" "json" "--jq" ".id")
              (catch clojure.lang.ExceptionInfo e (die (str what (board-reason (ex-message e)) then)))))
       convert
       (fn [item-id]
         (let
           [rid (gh! "repo" "view" repo "--json" "id" "--jq" ".id")
            _ (when (str/blank? rid) (die "Failed to resolve repository id"))
            ;; The schema (introspected, #12): input {itemId, repositoryId}, payload {item: ProjectV2Item}.
            q
            "mutation($itemId:ID!,$repositoryId:ID!){ convertProjectV2DraftIssueItemToIssue(input:{itemId:$itemId,repositoryId:$repositoryId}) { item { content { ... on Issue { number url } } } } }"
            c (get-in (json/parse-string (gh! "api" "graphql"
                                              "-f"  (str "query=" q)
                                              "-F"  (str "itemId=" item-id)
                                              "-F"  (str "repositoryId=" rid))
                                         true)
                      [:data :convertProjectV2DraftIssueItemToIssue :item :content])]
           (when-not (and (:number c) (:url c)) (die "Draft item conversion to issue failed"))
           [(str (:number c)) (:url c)]))
       use-item (fn [item]
                  (let [id (or (:id item) (die "Existing project item has no id"))]
                    (case (get-in item [:content :type])
                      "Issue"      (let [n (get-in item [:content :number])
                                         u (get-in item [:content :url])]
                                     (when-not (and n u) (die "Existing issue item is missing issue data"))
                                     [(str n) u id])
                      "DraftIssue" (conj (convert id) id)
                      (die (str "Existing project item '" title "' is not an issue or draft issue")))))
       ;; --issue N names the work: no title is matched, with reuse on or off (#12).
       [number url item-id]
       (cond
         (:issue o)
         (let [n (:issue o)
               {:keys [exit out err]} (run "gh" "issue" "view" n "-R" repo "--json" "url" "--jq" ".url")
               u (str/trim out)]
           (when-not (zero? exit)
             (die
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
                                  (json/parse-string (or (gh-out "issue" "list"
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
               (when (str/blank? u) (die "Existing issue is missing issue data"))
               [n u (item-add u (str "#" n " could not be put on the board: "))]))))
       [number url item-id]
       (cond
         number [number url item-id]

         (= mode "issue")
         (let [u (last-line (apply gh!
                                   (concat ["issue" "create" "-R" repo "--title" title]
                                           ;; gh issue create without a body asks for one, and without a terminal fails (#12)
                                           (if (:body-file o)
                                             ["--body-file" (:body-file o)]
                                             ["--body" (or (:body o) "")])
                                           (label-args "--label")
                                           (assignee-args "--assignee"))))
               _ (when (str/blank? u) (die "Issue creation failed"))
               n (last (str/split u #"/"))]
           [n u
            (item-add u
                      (str "#" n " is filed but could not be put on the board: ")
                      (str ". Then run kit start again with --issue " n))])

         :else
         (let [body (if (:body-file o)
                      (file-text (:body-file o))
                      (not-empty (:body o)))
               item (json/parse-string (apply gh!
                                              (concat ["project" "item-create" project-number "--owner" owner "--title"
                                                       title "--format" "json"]
                                                      (when body ["--body" body])))
                                       true)
               id   (or (not-empty (:id item)) (die "Draft item creation failed"))]
           (conj (convert id) id)))]
      (let [edit (concat (label-args "--add-label")
                         (assignee-args "--add-assignee")
                         (when (:milestone o) ["--milestone" (:milestone o)]))]
        (when (seq edit)
          (apply gh! "issue" "edit" number "-R" repo edit)))
      ;; A failed board update ends the run (#41): the issue exists, the board does not show it as asked.
      (let [field! (fn [field option]
                     (when-not (str/blank? option)
                       (when-let [e (board-set-field project-number owner url field option)]
                         (die (str "#" number
                                   " is filed, but its " field
                                   " on the board is not '" option
                                   "': "
                                   (board-reason e)
                                   ". Then run kit start again with --issue " number)))))]
        (field! showcase-field showcase)
        (field! priority-field priority)
        (field! size-field size)
        ;; The board's Size, mirrored as a size:* label: CI's design approval gate reads labels, it cannot read a
        ;; user's board (#26). Other size labels are removed.
        (when-not (str/blank? size)
          (run "gh"
               "label"
               "create"
               (str "size:" size)
               "-R"
               repo
               "--color"
               "BFD4F2"
               "--description"
               "Task size (ADR-0002), mirrors the board field" "--force")
          (let [old  (some-> (gh-out "issue"
                                     "view" number
                                     "-R" repo
                                     "--json" "labels"
                                     "--jq"
                                     ".labels[].name | select(startswith(\"size:\"))")
                             str/split-lines)
                gone (mapcat (fn [l] ["--remove-label" l]) (remove #(or (str/blank? %) (= % (str "size:" size))) old))]
            (when-not (zero? (:exit
                              (apply run "gh" "issue" "edit" number "-R" repo "--add-label" (str "size:" size) gone)))
              (warn (str "Warning: could not set the label size:" size " on #" number)))))
        (field! status-field status))
      (let [branch
            (when-not (:skip-branch o)
              ;; `gh issue develop` with no --name appends -1 to a name already taken, so a rerun would fork a
              ;; duplicate branch. Handing it the branch already linked to the issue makes the run idempotent.
              (let [named (or (:branch-name o)
                              (some-> (gh-out "issue" "develop" "--list" number "-R" repo)
                                      str/split-lines
                                      first
                                      (str/split #"\t")
                                      first
                                      not-empty))
                    out   (last-line (apply gh!
                                            (concat ["issue" "develop" number "-R" repo]
                                                    (when-not (:no-checkout o) ["--checkout"])
                                                    (when-not (str/blank? base) ["--base" base])
                                                    (when named ["--name" named]))))]
                ;; --no-checkout leaves the working tree alone, so the name comes from the URL gh prints.
                (if (:no-checkout o)
                  (second (re-find #"/tree/(.*)$" (or out "")))
                  (some-> (run "git" "branch" "--show-current") :out str/trim))))]
        (println (str "Issue URL: " url))
        (println (str "Issue Number: " number))
        (println (str "Project Item ID: " item-id))
        (when-not (str/blank? branch)
          (println (str (if (:no-checkout o) "Branch: " "Checked Out Branch: ") branch)))
        ;; A run that quietly eats a tenth of the hourly budget is only noticed an hour later. Print the price.
        (when before
          (when-let [after (budget)]
            (println (str "GraphQL Cost: " (- before after) " points (" after " left this hour)"))))))))


;; ------------------------------------------------------------------ kit finish


(def finish-usage
  "Usage: kit finish [options]

Run from the issue branch (<number>-<slug>, made by kit start).

Commits what is staged (or the --add paths), pushes, opens the pull request and sets the
board Status to \"In review\". Never merges: merging is kit merge.

Where main of the repository holds .github/workflows/ready-when-green.yml (ADR-0007), the PR
opens as a draft: GitHub asks the owners for a review only when that workflow marks it ready,
once the required checks have passed, and it turns auto-merge on then. Elsewhere the PR opens
ready, with auto-merge on, and the owners are asked at once.

Subjects follow Conventional Commits with the issue in the scope:
  feat(GH-12): add landing page
GitHub appends the PR number on squash: \"feat(GH-12): add landing page (#13)\".

Options:
  --type <type>                 feat|fix|docs|refactor|test|perf|build|ci|chore (default: the type of
                                the branch's first commit, else feat)
  --summary <text>              Subject text after \"type(GH-n): \" (default: the issue title)
  --commit-message-file <path>  Full commit message (subject, body, trailers); overrides --summary for the commit
  --pr-body-file <path>         PR description. Required when the PR is opened. Must contain a line
                                \"ADR: none\" or \"ADR: adr/NNNN-....md\". \"Closes #<issue>\" is added if missing.
  --add <path>                  Stage this path before committing (repeatable)
  --hold                        Open the PR with the label hold, for a PR that overlaps one still waiting: the
                                workflow leaves it a draft until kit release. Needs the ready workflow on main
  --no-commit                   Commit nothing; push and open the PR for commits already on the branch
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --review-status <option>      Default: In review
  --help                        Show this help")


(def finish-spec
  {"--type" [:type :value]
   "--summary" [:summary :value]
   "--commit-message-file" [:message-file :value]
   "--pr-body-file" [:body-file :value]
   "--add" [:add :many]
   "--no-commit" [:no-commit :flag]
   "--hold" [:hold :flag]
   "--repo" [:repo :value]
   "--owner" [:owner :value]
   "--project-number" [:project-number :value]
   "--base" [:base :value]
   "--status-field" [:status-field :value]
   "--review-status" [:review-status :value]
   "--help" [:help :flag]
   "-h" [:help :flag]})


(defn- git-out [& args] (some-> (apply run "git" args) :out str/trim))


(def ready-workflow-path ".github/workflows/ready-when-green.yml")


(defn- ready-workflow?
  "Whether `base` of `repo` holds the ready workflow (ADR-0007). Not-found is a plain no; any other failure ends the
  run: guessing would open a ready PR in a repository that has the workflow, or a draft that nothing marks ready."
  [repo base]
  (let [{:keys [exit out err]}
        (run "gh" "api" (str "repos/" repo "/contents/" ready-workflow-path "?ref=" base) "--silent")]
    (cond (zero? exit) true
          (re-find #"404|Not Found" (str out err)) false
          :else
          (die (str
                "Could not look for "
                ready-workflow-path
                " on '"
                base
                "' of "
                repo
                " ("
                (let [m (str/trim (str out err))]
                  (if (str/blank? m)
                    (str "gh exit code " exit)
                    m))
                "). Nothing was committed or pushed: run it again when GitHub answers, or check 'gh auth status'.")))))


(defn- ensure-hold-label!
  "The label hold exists in the repository; made when it does not."
  [repo]
  (let [{:keys [exit out err]} (run "gh"      "label"
                                    "create"  "hold"
                                    "-R"      repo
                                    "--color" "FBCA04"
                                    "--description" "Stays a draft until kit release (ADR-0007)")]
    (when-not (or (zero? exit) (re-find #"already exists" (str out err)))
      (die (str "Label 'hold' does not exist in "
                repo
                " and could not be created: create it on GitHub (Issues > Labels), then run again")))))


(defn- turn-on-auto-merge!
  "Auto-merge (squash) on a ready PR; a repository that does not allow it gets a warning with the fix."
  [pr-url]
  (let [r (run "gh" "pr" "merge" pr-url "--auto" "--squash")]
    (when-not (zero? (:exit r))
      (warn "Warning: auto-merge could not be turned on:" (str/trim (str (:out r) (:err r))))
      (warn
       "  Fix: the owner turns on Settings > General > 'Allow auto-merge' (dev-kit#37); until then kit merge merges after the Approve."))))


(defn finish!
  [kit args]
  (let [o              (parse-opts finish-spec args)
        _ (when (:help o) (println finish-usage) (System/exit 0))
        owner          (or (:owner o) (env "GHWF_OWNER" nil))
        project-number (or (:project-number o) (env "GHWF_PROJECT_NUMBER" nil))
        base           (or (:base o) (env "GHWF_DEFAULT_BASE" "main"))
        status-field   (or (:status-field o) (env "GHWF_STATUS_FIELD" "Status"))
        review-status  (or (:review-status o) (env "GHWF_REVIEW_STATUS" "In review"))
        ;; Without --type the PR follows the branch: its first `type(GH-n): …` commit that is not docs, else the first, else feat.
        ctype          (or (:type o)
                           (let [types (keep #(second (re-find #"^([a-z]+)\(GH-[0-9]+\): " %))
                                             (str/split-lines (git-out "log" "--reverse"
                                                                       "--format=%s" (str "origin/" base "..HEAD"))))]
                             (or (first (remove #{"docs"} types)) (first types)))
                           "feat")
        repo           (resolve-repo (:repo o))
        branch         (git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (= branch "HEAD") (die "Detached HEAD is not supported"))
        _ (when (= branch base) (die (str "On the base branch '" base "': run from the issue branch")))
        ;; The issue is the branch's number: CI's pull request gate accepts no other (#21).
        number         (or
                        (issue-branch-number branch)
                        (die
                         (str
                          "The branch '"
                          branch
                          "' is not <number>-<slug>: open the issue with kit start and move the work to its branch")))
        ;; Everything that can be wrong with the inputs is checked before the commit and the push: a run that dies
        ;; after the push leaves a branch with no pull request and a confusing rerun.
        _ (when (and (:body-file o) (not (fs/readable? (:body-file o))))
            (die (str "PR body file '" (:body-file o) "' not found")))
        _ (when (and (:message-file o) (not (fs/readable? (:message-file o))))
            (die (str "Commit message file '" (:message-file o) "' not found")))
        ;; Only an open pull request counts: `gh pr view <branch>` also returns closed and merged ones.
        pr-url         (not-empty (gh! "pr"      "list"
                                       "-R"      repo
                                       "--head"  branch
                                       "--state" "open"
                                       "--json"  "url"
                                       "--jq"    ".[0].url // empty"))
        _ (when-not (or pr-url (:body-file o)) (die "--pr-body-file is required to open the pull request"))
        subject        (cond
                         (:summary o)      (str ctype "(GH-" number "): " (:summary o))
                         (:message-file o) (first (str/split-lines (slurp (:message-file o))))
                         :else             (let [t
                                                 (gh! "issue" "view" number "-R" repo "--json" "title" "--jq" ".title")]
                                             (str ctype
                                                  "(GH-"
                                                  number
                                                  "): "
                                                  (str/lower-case (subs t 0 (min 1 (count t))))
                                                  (subs t (min 1 (count t))))))
        ;; The title and the body pass the rules CI checks on the pull request (#21): one implementation, in kit.
        ;; The body is checked only when it becomes a new PR's body; an open PR keeps its own.
        shape          (apply run
                              "bb"
                              kit
                              "gate"
                              "--shape"
                              "--branch"
                              branch
                              "--title"
                              subject
                              (when (and (not pr-url) (:body-file o)) ["--body-file" (:body-file o)]))
        _ (when-not (zero? (:exit shape))
            (die (str "The pull request would fail CI's shape check; nothing was committed or pushed:\n"
                      (str/trimr (str (:out shape) (:err shape))))))
        ;; ADR-0007: with the ready workflow on main the PR opens as a draft and the workflow marks it ready.
        base-draft?    (ready-workflow? repo base)
        ;; An open PR is judged by its own state, not by the base's: a ready PR gets what a ready PR gets, and a
        ;; draft is left to the workflow.
        draft?         (if pr-url
                         (= "true" (gh! "pr" "view" pr-url "--json" "isDraft" "--jq" ".isDraft"))
                         base-draft?)
        _
        (when (and (:hold o) pr-url (not draft?))
          (die
           (str
            pr-url
            " is ready already: the workflow only reads drafts, so --hold would hold nothing. Nothing was committed or "
            "pushed: make it a draft first (gh pr ready " pr-url
            " --undo; that turns auto-merge off and keeps the owners' "
            "request) and run kit finish --hold again, or run kit finish without --hold.")))
        _ (when (and (:hold o) (not base-draft?))
            (die
             (str
              "--hold works through "
              ready-workflow-path
              ", which '"
              base
              "' of "
              repo
              " does not hold yet. Nothing was "
              "committed or pushed: run kit finish without --hold (the PR opens ready, the owners are asked at once), "
              "or after the dev-kit update PR with the file has merged.")))
        _ (when (:hold o) (ensure-hold-label! repo))]
    (when-not (:no-commit o)
      (when (seq (:add o)) (ok! (apply shell "git" "add" "--" (:add o))))
      (when (zero? (:exit (run "git" "diff" "--cached" "--quiet")))
        (die "Nothing staged to commit (use --add <path>, or --no-commit)"))
      (ok! (if (:message-file o)
             (shell "git" "commit" "-F" (:message-file o))
             (shell "git" "commit" "-m" subject))))
    (ok! (shell "git" "push" "-u" "origin" branch))
    (let [existing? (boolean pr-url)
          pr-url    (or pr-url
                        (let [body (file-text (:body-file o))
                              body (if (re-find (re-pattern
                                                 (str "(?i)(close[sd]?|fix(e[sd])?|resolve[sd]?) #" number "\\b"))
                                                body)
                                     body
                                     (str "Closes #" number "\n\n" body))]
                          (last-line (apply gh!
                                            "pr"
                                            "create"
                                            "-R"
                                            repo
                                            "--base"
                                            base
                                            "--head"
                                            branch
                                            (concat (when base-draft? ["--draft"])
                                                    (when (:hold o) ["--label" "hold"])
                                                    ["--title" subject "--body" body])))))
          reviewers (not-empty (System/getenv "GHWF_REVIEWERS"))
          human     (not-empty (System/getenv "GHWF_HUMAN"))
          ;; A draft asks nobody: the owners are requested when the ready workflow marks it ready (ADR-0007), and
          ;; asking now would send the notification this change moves to the passed checks.
          edit      (concat (when (and reviewers (not draft?)) ["--add-reviewer" reviewers])
                            (when human ["--add-assignee" human]))]
      ;; On the reviewers' desk, assigned to the person behind the agent (#52). Repeating it is harmless.
      (when-not human
        (warn
         "Warning: no GHWF_HUMAN, so the PR has no assignee. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login."))
      (when (seq edit)
        (let [r (apply run "gh" "pr" "edit" pr-url edit)]
          (when-not (zero? (:exit r))
            (warn "Warning: could not request the review or set the assignee:" (str/trim (str (:out r) (:err r)))))))
      ;; An open PR is held by its own label: the workflow reads it when CI completes.
      (when (and (:hold o) existing?)
        (let [r (run "gh" "pr" "edit" pr-url "--add-label" "hold")]
          (when-not (zero? (:exit r))
            (die (str pr-url
                      " could not get the label hold: "
                      (str/trim (str (:out r) (:err r)))
                      ". Fix: add the label on GitHub, or run kit finish --hold again.")))))
      ;; The owner's Approve is the word to merge: with auto-merge on, GitHub merges once the owner approved the
      ;; latest commit, the checks passed and the branch contains the latest base. A draft cannot have it: the
      ;; ready workflow turns it on after it marks the PR ready (ADR-0007).
      (when-not draft?
        (turn-on-auto-merge! pr-url))
      ;; A failed board update ends the run (#41). The PR is open by now, so only the status is left to set.
      (when (not-any? str/blank? [owner project-number review-status])
        (let [url (gh-out "issue" "view" number "-R" repo "--json" "url" "--jq" ".url")]
          (if (str/blank? url)
            (warn (str "Warning: issue #" number " not found, Status not set"))
            (when-let [e (board-set-field project-number owner url status-field review-status)]
              (die (str "The pull request is open: " pr-url
                        ". But the board " status-field
                        " of #" number
                        " is not '"
                        review-status
                        "': " (board-reason e)
                        ". Set it alone: "
                        (board-set-command kit project-number owner url status-field review-status)))))))
      (println (str "Subject: " subject))
      (println (str "PR URL: " pr-url))
      (when draft?
        (println
         (str
          "Draft: the owners are asked once its required checks pass; the ready workflow marks it ready and turns on auto-merge."
          (when (:hold o) " Held: when the earlier PR has merged, run kit release."))))
      (println (str "Status: " review-status)))))


;; ------------------------------------------------------------------ waiting on a pull request


;; Nothing here waits on a clock (#109). A wait ends when what it waits for happens, or when that became impossible,
;; and it says which. The only number is the pause between two looks at GitHub, which no event replaces: GitHub cannot
;; call a process on this machine.


(defn- pause
  "The pause between two looks, in seconds: GHWF_MERGE_POLL, for kit merge and kit checks alike."
  []
  (let [v (env "GHWF_MERGE_POLL" "10")]
    (or (parse-long v)
        (die (str "GHWF_MERGE_POLL is '" v "': give it a whole number of seconds, or unset it for 10")))))


(defn- pr-args
  "The pull request as gh takes it: a URL alone, a number with its repository."
  [pr repo]
  (if (re-matches #"\d+" (str pr))
    [(str pr) "-R" repo]
    [pr]))


(defn- gh-json
  "A gh call's JSON output. A call that fails goes to `fail` (die, or a stop with its own exit code) with gh's message."
  [fail & args]
  (let [{:keys [exit out err]} (apply run "gh" args)]
    (if (zero? exit)
      (json/parse-string out true)
      (fail (str "gh "
                 (str/join " " (take 2 args))
                 " failed: "
                 (str/trim (str out err))
                 ". Fix: run it by hand to see why; an unreachable GitHub and an expired token both show there.")))))


(defn- read-pr
  "A pull request's JSON fields: :state, :decision (nil when there is no review decision), :merge-state, :head, :base."
  [pr repo fail]
  (let [j (apply gh-json
                 fail
                 "pr"
                 "view"
                 (concat (pr-args pr repo) ["--json" "state,reviewDecision,mergeStateStatus,headRefOid,baseRefName"]))]
    {:state       (:state j)
     :decision    (not-empty (:reviewDecision j))
     :merge-state (:mergeStateStatus j)
     :head        (:headRefOid j)
     :base        (:baseRefName j)}))


(defn- required-checks
  "The required checks registered on the pull request's head commit, as {:name :bucket :link}; [] when none is."
  [pr repo fail]
  (let [{:keys [out err]}
        (apply run "gh" "pr" "checks" (concat (pr-args pr repo) ["--required" "--json" "name,bucket,link"]))
        parsed (try (json/parse-string out true) (catch Exception _ nil))]
    (cond (sequential? parsed) parsed
          (re-find #"(?i)no (required )?checks reported" (str out err)) []
          :else
          (fail (str "gh pr checks failed: "
                     (str/trim (str out err))
                     ". Fix: run `gh pr checks "
                     (first (pr-args pr repo))
                     " --required` by hand to see why; an unreachable GitHub and an expired token both show there.")))))


(defn- failed-text
  [failed where]
  (str (count failed)
       " required check" (when (> (count failed) 1) "s")
       " failed " where
       ":\n"
       (str/join "\n" (map #(str "  " (:name %) ": " (:link %)) failed))))


(defn- failed-checks [checks] (filter #(#{"fail" "cancel"} (:bucket %)) checks))


;; ------------------------------------------------------------------ kit checks


(def checks-usage
  "Usage: kit checks [options]

Waits until the checks the base branch requires exist on the pull request's head commit and have
finished. They are read from the base's rulesets (classic branch protection needs admin to read);
a base that requires none ends it at once. Run it as a background command: the agent is woken when it exits.

  exit 0  every required check passed, or the base requires none
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
  (let [o         (parse-opts checks-spec args)
        _ (when (:help o) (println checks-usage) (System/exit 0))
        repo      (resolve-repo (:repo o))
        poll      (pause)
        stop      (fn [code & msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit code))
        no-result #(stop 2 (str %))
        branch    (git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (and (:pr o) (not (re-matches #"\d+" (:pr o))))
            (die (str "--pr is '" (:pr o) "': give the pull request's number")))
        pr        (or (:pr o)
                      (do (when (#{(env "GHWF_DEFAULT_BASE" "main") "HEAD"} branch)
                            (die (str "Run from the issue branch, not '"
                                      branch
                                      "', or name the pull request: kit checks --pr <number>")))
                          (not-empty (gh! "pr"      "list"
                                          "-R"      repo
                                          "--head"  branch
                                          "--state" "open"
                                          "--json"  "url"
                                          "--jq"    ".[0].url // empty")))
                      (die (str "No open pull request for branch '"
                                branch
                                "' in "
                                repo
                                ". Fix: open it with kit finish, or name it: kit checks --pr <number>")))
        {:keys [head base]} (read-pr pr repo no-result)
        sha7      #(subs (str %) 0 (min 7 (count (str %))))
        ;; What the base requires, once: a check that is not registered yet is told from one the base never asks for.
        wanted    (->> (gh-json no-result "api" (str "repos/" repo "/rules/branches/" base))
                       (filter #(= "required_status_checks" (:type %)))
                       (mapcat #(get-in % [:parameters :required_status_checks]))
                       (map :context)
                       distinct)]
    (if (empty? wanted)
      (println (str "No required checks on " base "; nothing to wait for: " pr))
      (do
        (println (str "Waiting for " (str/join ", " wanted) " on " (sha7 head) " (" pr ")."))
        (loop [shown nil]
          (let [checks  (required-checks pr repo no-result)
                now     (read-pr pr repo no-result)
                failed  (failed-checks checks)
                missing (remove (set (map :name checks)) wanted)
                pending (filter #(= "pending" (:bucket %)) checks)
                status  (cond (seq missing) (str "Not registered yet: " (str/join ", " missing) ".")
                              (seq pending) (str (count pending)
                                                 " of " (count checks)
                                                 " required checks have not finished: "
                                                 (str/join ", " (map :name pending))))]
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
              (die (str (failed-text failed (str "on " (sha7 head)))
                        "\nFix: open the link, fix the cause, push; then run kit checks again for the new commit."))

              status (do (when (not= status shown) (println status))
                         (Thread/sleep (* 1000 poll))
                         (recur status))

              :else (println (str "All " (count checks) " required checks passed on " (sha7 head) ": " pr)))))))))


;; ------------------------------------------------------------------ kit release


(def release-usage
  "Usage: kit release [options]

For a pull request held with the label hold (kit finish --hold), once the PR it waited for has
merged: removes the label, marks the PR ready (GitHub then asks the owners for a review) and
turns on auto-merge (ADR-0007). It also frees a draft that was never held but that nothing marked ready. It refuses,
naming the check and changing nothing, while a required check on the PR's head is not green.

Options:
  --pr <number|url>             The pull request (default: the open PR of the current branch)
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --help                        Show this help")


(def release-spec
  {"--pr" [:pr :value] "--repo" [:repo :value] "--help" [:help :flag] "-h" [:help :flag]})


(defn- checks-not-green
  "The required checks of a PR that are not green, as \"name (bucket)\"; empty when all are. Dies when GitHub reports none."
  [repo pr]
  (let [checks (required-checks pr repo die)]
    (when (empty? checks)
      (die (str "GitHub reports no required check on " pr
                ", so none can be called green. Fix: run it again once CI has started, or gh pr ready " pr)))
    (->> checks (remove #(#{"pass" "skipping"} (:bucket %))) (map #(str (:name %) " (" (:bucket %) ")")))))


(defn release!
  [_kit args]
  (let [o      (parse-opts release-spec args)
        _ (when (:help o) (println release-usage) (System/exit 0))
        repo   (resolve-repo (:repo o))
        branch (git-out "rev-parse" "--abbrev-ref" "HEAD")
        pr     (or (:pr o)
                   (not-empty (gh! "pr"      "list"
                                   "-R"      repo
                                   "--head"  branch
                                   "--state" "open"
                                   "--json"  "url"
                                   "--jq"    ".[0].url // empty"))
                   (die (str "No open pull request for branch '"
                             branch
                             "' in "
                             repo
                             ". Fix: run it from the held PR's branch, or name it with --pr <number>")))
        {:keys [state isDraft labels url]}
        (json/parse-string (gh! "pr" "view" pr "-R" repo "--json" "state,isDraft,labels,url") true)
        url    (or url pr)
        held?  (boolean (some #(= "hold" (:name %)) labels))
        _ (when-not (= state "OPEN") (die (str url " is " state ", not open: nothing to release.")))
        bad    (checks-not-green repo pr)]
    (when (seq bad)
      (die (str "A required check of " url
                " is not green: "      (str/join ", " bad)
                ". Nothing was changed: wait for it "
                "(kit checks, in the background), then run kit release again; a failed check is fixed first.")))
    (when held?
      (ok! (shell "gh" "pr" "edit" pr "-R" repo "--remove-label" "hold"))
      (println "Hold removed."))
    (when isDraft
      (ok! (shell "gh" "pr" "ready" pr "-R" repo))
      (println "Marked ready: the owners are asked for a review."))
    (turn-on-auto-merge! url)
    (println (str "Released: " url))))


;; ------------------------------------------------------------------ kit merge


(def merge-usage
  "Usage: kit merge [options]

Run from the issue branch, as a background command: the agent is woken when it exits. When the pull
request is open, it needs the owner's Approve of the latest commit. A branch behind the base is
updated by merging the base into it, which keeps the approval. It turns on auto-merge (squash) and
waits until GitHub reports the pull request MERGED, or until merging became impossible (closed, a
required check failed, DIRTY, the approval gone); it then stops and names the reason.
When GitHub already merged it, only the closeout runs: board Status \"Done\", the branch deleted
locally and on GitHub, back to the base branch; each issue the PR closes that is still open is closed as completed
(auto-merge merges as github-actions, and GitHub then closes none). A pull request that did not merge keeps its branch.

Options:
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --owner <login>               Board owner (default: GHWF_OWNER)
  --project-number <number>     Board number (default: GHWF_PROJECT_NUMBER)
  --base <branch>               Default: GHWF_DEFAULT_BASE or main
  --status-field <name>         Default: GHWF_STATUS_FIELD or Status
  --done-status <option>        Default: Done
  --help                        Show this help

Environment: GHWF_MERGE_POLL, seconds between looks (default 10). Nothing else bounds the wait.")


(def merge-spec
  {"--repo"        [:repo :value]
   "--owner"       [:owner :value]
   "--project-number" [:project-number :value]
   "--base"        [:base :value]
   "--status-field" [:status-field :value]
   "--done-status" [:done-status :value]
   "--help"        [:help :flag]
   "-h"            [:help :flag]})


(defn- dirty-die
  [url base]
  (die (str "The branch conflicts with "
            base
            ": "
            url
            ". Fix: merge "
            base
            " into the branch (git fetch origin && "
            "git merge origin/" base
            ") and resolve the conflicts, then push; the push dismisses the owner's Approve, "
            "so ask for it again.")))


(defn- close-linked-issues!
  "A pull request merged by auto-merge is merged by github-actions, and GitHub then closes none of its linked issues.
  Closes each one the PR names as closing that is still open, as completed, naming the PR; one line per issue."
  [pr-url]
  (doseq [{:keys [number url]} (:closingIssuesReferences
                                (gh-json die "pr" "view" pr-url "--json" "closingIssuesReferences"))]
    (if (= "OPEN" (gh! "issue" "view" url "--json" "state" "--jq" ".state"))
      (let [r (run "gh"        "issue"
                   "close"     url
                   "--reason"  "completed"
                   "--comment" (str "Closed by " pr-url ", which merged."))]
        (if (zero? (:exit r))
          (println (str "Closed #" number ": " url))
          (warn (str "Warning: could not close #"
                     number
                     ": "
                     (str/trim (str (:out r) (:err r)))
                     ". Fix: gh issue close "
                     url
                     " --reason completed"))))
      (println (str "#" number " was closed already: " url)))))


(defn merge!
  [kit args]
  (let [o (parse-opts merge-spec args)
        _ (when (:help o) (println merge-usage) (System/exit 0))
        owner (or (:owner o) (env "GHWF_OWNER" nil))
        project-number (or (:project-number o) (env "GHWF_PROJECT_NUMBER" nil))
        base (or (:base o) (env "GHWF_DEFAULT_BASE" "main"))
        status-field (or (:status-field o) (env "GHWF_STATUS_FIELD" "Status"))
        done-status (or (:done-status o) (env "GHWF_DONE_STATUS" "Done"))
        repo (resolve-repo (:repo o))
        branch (git-out "rev-parse" "--abbrev-ref" "HEAD")
        _ (when (#{base "HEAD"} branch) (die (str "Run from the issue branch, not '" branch "'")))
        number (or (issue-branch-number branch) (die (str "Branch '" branch "' is not <number>-<slug>")))
        poll (pause)
        pr-list #(not-empty
                  (gh! "pr" "list" "-R" repo "--head" branch "--state" % "--json" "url" "--jq" ".[0].url // empty"))
        open-url (pr-list "open")
        shown (atom nil)
        ;; One look at the pull request. It ends the run when merging is impossible, and brings a branch that is behind
        ;; up to date. It returns the pull request as last read.
        look!
        (fn [url first?]
          (let [pr   (read-pr url repo die)
                ms   (:merge-state pr)
                seen (str (:state pr) " " ms)]
            (when (not= seen @shown) (println (str "GitHub reports " (:state pr) ", " ms ": " url)) (reset! shown seen))
            (cond
              (= "MERGED" (:state pr)) pr

              (= "CLOSED" (:state pr))
              (die (str "The pull request was closed without merging: "
                        url
                        ". Branch '"
                        branch
                        "' is kept. Fix: reopen it "
                        "(gh pr reopen "
                        url
                        ") if it was closed by mistake, then run kit merge again."))

              (not= "APPROVED" (:decision pr))
              (die
               (str (if first? "Waiting for the owner's Approve of the latest commit" "The owner's approval is gone")
                    " (review: " (str/lower-case (or (:decision pr) "none"))
                    "): "        url
                    ". Fix: ask the owner to approve the "
                    "latest commit, then run kit merge again."))

              (= "DIRTY" ms) (dirty-die url base)

              :else
              (let [failed (when (= "BLOCKED" ms) (failed-checks (required-checks url repo die)))]
                (when (seq failed)
                  (die (str (failed-text failed (str "on " url))
                            "\nFix: open the link and fix the cause, push, ask the owner to approve the new commit, "
                            "then run kit merge again.")))
                (if (= "BEHIND" ms)
                  ;; GitHub may have merged it since the last read, and then its branch is gone: look again first (#99).
                  ;; A refused update is no end: the next look reads why (a conflict, a merge that went through).
                  (let [again (read-pr url repo die)]
                    (when (and (= "OPEN" (:state again)) (= "BEHIND" (:merge-state again)))
                      (println (str "The branch is behind " base ": merging " base " into it; the approval stays."))
                      (when-not (zero? (shell "gh" "pr" "update-branch" url))
                        (warn "The update was refused; looking again.")))
                    again)
                  pr)))))
        pr-url
        (if-not open-url
          ;; Merged by GitHub's auto-merge after the owner's Approve: only the closeout is left.
          (let [u (or (pr-list "merged")
                      (die (str "No open or merged pull request for branch '"
                                branch
                                "' in "
                                repo
                                "; if that is the wrong repository, pass --repo owner/name")))]
            (println (str "Already merged by GitHub: " u))
            u)
          (do
            (when-not (= "MERGED" (:state (look! open-url true)))
              ;; Auto-merge merges at once when everything is in place, or as soon as the checks pass. Without
              ;; auto-merge in the repository, a plain squash merge works when nothing is pending.
              (let [r (run "gh" "pr" "merge" open-url "--auto" "--squash")]
                ;; A refusal is read against the PR's state first: one GitHub already merged needs no second try (#97).
                (when (and (not (zero? (:exit r)))
                           (not= "MERGED" (:state (read-pr open-url repo die))))
                  (warn (str "Auto-merge not available ("
                             (str/trim (str (:out r) (:err r)))
                             "); trying a plain squash merge."))
                  (when-not (zero? (shell "gh" "pr" "merge" open-url "--squash"))
                    (die
                     (str
                      "Could not merge "
                      open-url
                      ": auto-merge is off in this repository, so nothing merges by itself. "
                      "Fix: if checks are still running, run this again when they pass; otherwise the owner turns on "
                      "Settings > General > 'Allow auto-merge' (dev-kit#37).")))))
              ;; Only a confirmed merge may delete the branch: cleaning up after a failed merge closes the pull request with
              ;; the work still in it. A branch can fall behind while it waits, when another pull request merges first;
              ;; auto-merge then waits for ever, since the base requires up-to-date branches, so every look updates it (#72).
              ;; The wait ends only in MERGED, or in a look that finds merging impossible (it ends the run).
              (loop []
                (when-not (= "MERGED" (:state (look! open-url false)))
                  (Thread/sleep (* 1000 poll))
                  (recur))))
            open-url))
        ;; A failed board update ends the run with an error (#41), after the closeout below: the merge is done.
        board-error
        (when (not-any? str/blank? [owner project-number])
          (let [url (gh! "issue" "view" number "-R" repo "--json" "url" "--jq" ".url")]
            (when-let [e (board-set-field project-number owner url status-field done-status)]
              (str "The board " status-field
                   " of #"      number
                   " is not '"  done-status
                   "': "        (board-reason e)
                   ". Set it alone: "
                   (board-set-command kit project-number owner url status-field done-status)))))]
    ;; Leave the issue branch. When the base branch is checked out in another worktree, this one cannot switch to
    ;; it; it stays detached on the merged base instead.
    (ok! (shell "git" "fetch" "-q" "origin" base))
    (if (some #{(str "branch refs/heads/" base)} (str/split-lines (git-out "worktree" "list" "--porcelain")))
      (ok! (shell "git" "switch" "-q" "--detach" (str "origin/" base)))
      (do (ok! (shell "git" "switch" "-q" base))
          (ok! (shell "git" "pull" "-q" "--ff-only"))))
    (ok! (shell "git" "branch" "-D" branch))
    (when (zero? (:exit (run "git" "ls-remote" "--exit-code" "--heads" "origin" branch)))
      (ok! (shell "git" "push" "origin" "--delete" branch)))
    (println (str "Merged: " pr-url))
    (close-linked-issues! pr-url)
    (when board-error (die board-error))
    (println (str "Status: " done-status))
    (println (str "Next: post the retrospective: kit retro " number))))


;; ------------------------------------------------------------------ entry


(defn main
  "kit start | finish | merge | checks | board, with the path of bin/kit for the commands that call it back."
  [cmd args kit]
  (case cmd
    "start"   (start! kit args)
    "finish"  (finish! kit args)
    "release" (release! kit args)
    "merge"   (merge! kit args)
    "checks"  (checks! kit args)
    "board"   (board! kit args)))
