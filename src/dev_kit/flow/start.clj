(ns dev-kit.flow.start
  "kit start: files the issue or reuses one, puts it on the board with its fields and creates its branch; nothing is
  filed until every check has passed. `start` is the recipe, start! its edge."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]))


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


(declare check-input
         check-milestone
         check-retro
         note-graphql-budget
         find-or-file-issue
         put-on-board
         label-and-assign
         set-board-fields
         create-branch)


(defn start
  "kit start as a recipe. Nothing is filed before every check has passed; GitHub is (:github ctx), and the result is
  data that start! prints."
  [ctx input]
  (steps/run ctx input
    :check-input         check-input
    :check-milestone     check-milestone
    :check-retro         check-retro
    :note-graphql-budget note-graphql-budget
    :find-or-file-issue  find-or-file-issue
    :put-on-board        put-on-board
    :label-and-assign    label-and-assign
    :set-board-fields    set-board-fields
    :create-branch       create-branch))


;; ------------------------------------------------------------------ checks, before anything exists


(defn- check-input
  "What the flags alone can refuse: a run that files the issue and then stops on the priority leaves half-tracked
  work behind. It also names the repository the run works in (:repo), between the owner and the project, as the
  refusals have always come in that order."
  [{:keys [resolve-repo]} {:keys [title owner project-number mode skip-branch no-checkout priority] :as input}]
  (when (str/blank? title)
    (steps/fail! :start/no-title "--title is required"))
  (when (str/blank? owner)
    (steps/fail! :start/no-owner "no board owner: pass --owner, or add a kit.edn to this project (run kit init)"))
  (let [repo (resolve-repo (:repo input))]
    (when (str/blank? project-number)
      (steps/fail! :start/no-project
                   "no board number: pass --project-number, or add a kit.edn to this project (run kit init)"))
    (when-not (#{"issue" "draft-convert"} mode)
      (steps/fail! :start/bad-mode "--mode must be 'issue' or 'draft-convert'"))
    (when (and skip-branch no-checkout)
      (steps/fail!
       :start/branch-flags-conflict
       "--skip-branch and --no-checkout are mutually exclusive: one creates no branch, the other creates one without touching the working tree"))
    (cond (str/blank? priority)
          (steps/fail! :start/no-priority "--priority is required: Urgent|High|Medium|Low")

          (not (#{"Urgent" "High" "Medium" "Low"} priority))
          (steps/fail! :start/off-scale-priority
                       (str "Priority '" priority "' is not on the scale Urgent|High|Medium|Low")))
    {:repo repo}))


(defn- check-milestone
  "An unknown milestone stops the run before an issue exists. Milestones are never created here."
  [{:keys [github]} {:keys [repo milestone]}]
  (when milestone
    (let [open (github :open-milestones repo)]
      (when-not (some #{milestone} open)
        (steps/fail!
         :start/unknown-milestone
         (str "Milestone '" milestone "' is not an open milestone of '" repo "'. Open: " (str/join "/" open))
         {:milestone milestone}))))
  {})


(defn- check-retro
  "A merged task's retrospective comes before new work (#28, #56). When GitHub cannot be asked, say so and go on:
  the check guards the process, not data."
  [{:keys [github kit warn]} {:keys [repo skip-retro]}]
  (when-not skip-retro
    (let [{:keys [exit out err]} (github :retro-missing kit repo)
          said (str/trim (str out err))]
      (case (int exit)
        0 nil
        1 (steps/fail! :start/retro-missing said)
        (warn "Warning: could not check for merged issues without a retrospective:" said))))
  {})


(defn- note-graphql-budget
  "The GraphQL points left before the work, so start! can print what the run cost: a run that quietly eats a tenth
  of the hourly budget is only noticed an hour later. A step of its own, here, to keep gh's calls in their order."
  [{:keys [github]} _]
  {:graphql-before (github :graphql-remaining)})


;; ------------------------------------------------------------------ the issue


(defn- csv [s] (remove str/blank? (map str/trim (str/split (or s "") #","))))


(defn- label-args
  "flag and label for each of --labels, a label the repository lacks created first. The repository's labels are
  read once, on the first label, and travel on as :known-labels: the issue's edit later must not create them again."
  [github repo labels known-labels flag]
  (reduce (fn [{:keys [known-labels args]} label]
            (let [known-labels (or known-labels (github :known-labels repo))]
              (when-not (or (known-labels label) (github :create-label! repo label))
                (steps/fail!
                 :start/label-not-created
                 (str "Label '"
                      label
                      "' does not exist in "
                      repo
                      " and could not be created: create it on GitHub (Issues > Labels), or drop it from --labels")))
              {:known-labels (conj known-labels label) :args (conj args flag label)}))
          {:known-labels known-labels :args []}
          (csv labels)))


(defn- issue-named-by-flag
  "--issue N names the work: no title is matched, with reuse on or off (#12)."
  [github {:keys [repo] number :issue}]
  (when number
    (let [{:keys [exit out err]} (github :issue-url repo number)]
      (when-not (zero? exit)
        (steps/fail!
         :start/issue-not-found
         (str "Issue #"
              number
              " not found in "
              repo
              " ("
              (str/trim (str out err))
              "): check the number, start without --issue, or pass --repo owner/name if that is the wrong repository")))
      {:number number :url (str/trim out)})))


(defn- open-work?
  "One board serves several repositories, so a card only counts as an issue of this repo, or a draft, which belongs
  to no repo yet. Only open work counts (#12): a closed issue or a Done card with a recurring title is history."
  [{:keys [status content]} repo done-status]
  (and (or (= "DraftIssue" (:type content))
           (and (= "Issue" (:type content))
                (str/starts-with? (or (:url content) "") (str "https://github.com/" repo "/"))))
       (not= "CLOSED" (:state content))
       (not= done-status status)))


(defn- convert-draft
  "A draft card made into an issue of the repository; the card keeps its id."
  [github repo item-id]
  (let [repo-id (github :repo-id repo)]
    (when (str/blank? repo-id)
      (steps/fail! :start/no-repo-id "Failed to resolve repository id"))
    (let [{:keys [number url]} (github :convert-draft! item-id repo-id)]
      (when-not (and number url)
        (steps/fail! :start/draft-not-converted "Draft item conversion to issue failed"))
      {:number (str number) :url url :item-id item-id})))


(defn- card-with-title
  "Open work with this title on the board: its issue, or its draft made into one."
  [github {:keys [repo title owner project-number status-field done-status]}]
  (when-let [{:keys [id content]}
             (some #(when (and (= title (:title %)) (open-work? % repo done-status)) %)
                   (github :project-items owner project-number status-field))]
    (when-not id
      (steps/fail! :start/card-without-id "Existing project item has no id"))
    (case (:type content)
      "Issue"      (do (when-not (and (:number content) (:url content))
                         (steps/fail! :start/card-without-issue "Existing issue item is missing issue data"))
                       {:number (str (:number content)) :url (:url content) :item-id id})
      "DraftIssue" (convert-draft github repo id)
      (steps/fail! :start/card-not-an-issue (str "Existing project item '" title "' is not an issue or draft issue")))))


(defn- open-issue-with-title
  "An open issue with this title that is not on the board yet."
  [github {:keys [repo title]}]
  (when-let [issue (some #(when (= title (:title %)) %) (github :open-issues-titled repo title))]
    (when (str/blank? (:url issue))
      (steps/fail! :start/issue-without-data "Existing issue is missing issue data"))
    {:number (str (:number issue)) :url (:url issue)}))


(defn- file-new
  "A new issue, or a new draft made into one (--mode draft-convert)."
  [github {:keys [repo title body body-file labels assignees mode project-number owner]}]
  (if (= mode "issue")
    (let [{:keys [known-labels args]} (label-args github repo labels nil "--label")
          ;; gh issue create without a body asks for one, and without a terminal fails (#12)
          url (github :file-issue!
                      repo
                      title
                      (if body-file
                        ["--body-file" body-file]
                        ["--body" (or body "")])
                      args
                      (mapcat (fn [a] ["--assignee" a]) (csv assignees)))]
      (when (str/blank? url)
        (steps/fail! :start/issue-not-filed "Issue creation failed"))
      {:number (last (str/split url #"/")) :url url :filed? true :known-labels known-labels})
    (let [body (if body-file
                 (shell/file-text body-file)
                 (not-empty body))
          id   (not-empty (github :create-draft! project-number owner title body))]
      (when-not id
        (steps/fail! :start/draft-not-created "Draft item creation failed"))
      (convert-draft github repo id))))


(defn- find-or-file-issue
  "The issue this run is about: the one --issue names, else open work with this title, else a new one. Adds :number
  and :url; :item-id when it came from a card; :filed? when this run filed it."
  [{:keys [github]} facts]
  (or (issue-named-by-flag github facts)
      (when-not (:no-reuse facts)
        (or (card-with-title github facts)
            (open-issue-with-title github facts)))
      (file-new github facts)))


;; ------------------------------------------------------------------ the board, the labels, the branch


(defn- put-on-board
  "The issue's card on the board, unless it came from one."
  [{:keys [github]} {:keys [item-id url number filed? project-number owner]}]
  (when-not item-id
    (let [{:keys [id error]} (github :item-add! project-number owner url)]
      (when error
        (steps/fail! :start/board-refused
                     (if filed?
                       (str "#" number
                            " is filed but could not be put on the board: " (board/board-reason error)
                            ". Then run kit start again with --issue " number)
                       (str "#" number " could not be put on the board: " (board/board-reason error)))))
      {:item-id id})))


(defn- label-and-assign
  "The labels, assignees and milestone, on a reused issue as well as on a new one."
  [{:keys [github]} {:keys [repo number labels known-labels assignees milestone]}]
  (let [{:keys [known-labels args]} (label-args github repo labels known-labels "--add-label")
        edit (concat args
                     (mapcat (fn [a] ["--add-assignee" a]) (csv assignees))
                     (when milestone
                       ["--milestone" milestone]))]
    (when (seq edit)
      (github :edit-issue! repo number edit))
    {:known-labels known-labels}))


(defn- set-board-fields
  "Showcase, Priority, Size and Status on the card. A failed board update ends the run (#41): the issue exists, the
  board does not show it as asked. The Size is mirrored as a size:* label: CI's design approval gate reads labels, it
  cannot read a user's board (#26)."
  [{:keys [github warn]}
   {:keys [repo number url project-number owner showcase-field showcase priority-field priority size-field size
           status-field status]}]
  (let [field! (fn [field option]
                 (when-not (str/blank? option)
                   (when-let [e (github :set-field! project-number owner url field option)]
                     (steps/fail! :start/field-refused
                                  (str "#" number
                                       " is filed, but its " field
                                       " on the board is not '" option
                                       "': "
                                       (board/board-reason e)
                                       ". Then run kit start again with --issue " number)))))]
    (field! showcase-field showcase)
    (field! priority-field priority)
    (field! size-field size)
    (when-not (or (str/blank? size) (github :size-label! repo number size))
      (warn (str "Warning: could not set the label size:" size " on #" number)))
    (field! status-field status)
    {}))


(defn- create-branch
  "The issue's branch, from gh issue develop. Handed the branch already linked to the issue, it makes none twice:
  with no --name it would append -1 to a taken name. --no-checkout leaves the working tree alone, so the name comes
  from the URL gh prints."
  [{:keys [github]} {:keys [repo number skip-branch no-checkout base branch-name]}]
  (when-not skip-branch
    (let [named (or branch-name (github :linked-branch repo number))
          out   (github :develop!
                        repo
                        number
                        (concat (when-not no-checkout
                                  ["--checkout"])
                                (when-not (str/blank? base)
                                  ["--base" base])
                                (when named
                                  ["--name" named])))]
      {:branch (if no-checkout
                 (second (re-find #"/tree/(.*)$" (or out "")))
                 (github :current-branch))})))


;; ------------------------------------------------------------------ the edges


(defn read-input
  "The command line and the GHWF_* defaults as one map; env is (fn [name default]), shell/env in a run."
  [args env]
  (let [o (shell/parse-opts start-spec args)]
    (if (:help o)
      o
      (assoc o
             :owner          (or (:owner o) (env "GHWF_OWNER" nil))
             :project-number (or (:project-number o) (env "GHWF_PROJECT_NUMBER" nil))
             :showcase-field (or (:showcase-field o) (env "GHWF_SHOWCASE_FIELD" "Showcase"))
             :priority-field (or (:priority-field o) (env "GHWF_PRIORITY_FIELD" "Priority"))
             :size-field     (or (:size-field o) (env "GHWF_SIZE_FIELD" "Size"))
             :status-field   (or (:status-field o) (env "GHWF_STATUS_FIELD" "Status"))
             :base           (or (:base o) (env "GHWF_DEFAULT_BASE" "main"))
             :assignees      (or (:assignees o) (env "GHWF_DEFAULT_ASSIGNEES" "@me"))
             :showcase       (or (:showcase o) (env "GHWF_SHOWCASE" nil))
             :priority       (or (:priority o) (env "GHWF_DEFAULT_PRIORITY" nil))
             :size           (or (:size o) (env "GHWF_DEFAULT_SIZE" nil))
             :status         (or (:status o) (when (:issue o) "In progress") (env "GHWF_DEFAULT_STATUS" "Backlog"))
             :mode           (or (:mode o) "issue")
             :done-status    (env "GHWF_DONE_STATUS" "Done")))))


(defn- print-result
  "What the run made, in the lines the flow's readers expect, and the GraphQL points it cost."
  [github {:keys [url number item-id branch no-checkout graphql-before]}]
  (println (str "Issue URL: " url))
  (println (str "Issue Number: " number))
  (println (str "Project Item ID: " item-id))
  (when-not (str/blank? branch)
    (println (str (if no-checkout "Branch: " "Checked Out Branch: ") branch)))
  (when graphql-before
    (when-let [after (github :graphql-remaining)]
      (println (str "GraphQL Cost: " (- graphql-before after) " points (" after " left this hour)")))))


(defn start!
  "kit start: read the command line, run the recipe on the real GitHub, print what it made — or Error: and the
  reason, exit 1."
  [kit args]
  (let [input (read-input args shell/env)]
    (when (:help input)
      (println start-usage)
      (System/exit 0))
    (let [ctx    {:github github/real :warn shell/warn :kit kit :resolve-repo shell/resolve-repo}
          result (start ctx input)]
      (when (= :fail (::steps/status result))
        (shell/die (:message (::steps/problem result))))
      (print-result github/real result))))
