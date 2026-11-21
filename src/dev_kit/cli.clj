(ns dev-kit.cli
  "The command tree of kit, as data: every command with its words, what it does and its options. kit's one edge
  (dev-kit.main) reads the command line against it, and help and the refusal of a mistake on the command line are
  built from it, so a flag cannot be in the code and not in the help. Requires nothing but babashka.cli: it is
  loaded for every command, among them the hook that runs on every edit, so it must stay light.
  A flag with a value is `:coerce :string` (else `--title` at the end of the line becomes true); a spec is a
  vector of pairs (else help lists the flags in no order).")


(def repo
  [:repo {:coerce :string :desc "The repository" :default-desc "the folder's origin on GitHub, then its kit.edn"}])


(def tree
  [{:cmds ["init"]
    :doc  "Lay out the shared setup of the project and check it: kit.edn, the managed files and the agent identity."
    :spec [[:repo {:coerce :string :desc "owner/name of the project, written into a new kit.edn"}]
           [:showcase {:coerce :string :desc "Showcase field option of the project, written into a new kit.edn"}]
           [:modules {:coerce :string :desc "Modules of a new kit.edn, e.g. clojure"}]
           [:scaffold {:coerce :boolean :desc "Also create README.md, adr/ and docs/experiments/ if missing"}]
           [:local {:coerce :boolean :desc "Only the per-machine items (the SessionStart hook runs it)"}]]}
   {:cmds ["doctor"]
    :doc  "Check the setup against kit.edn and say what to fix."
    :spec [[:ci {:coerce :boolean :desc "Skip the checks that need a person's machine"}]
           [:quiet {:coerce :boolean :desc "Print nothing when all is well"}]
           [:edn {:coerce :boolean :desc "Print the findings as EDN"}]]}
   {:cmds ["modules"] :doc "Print which modules the project's kit.edn turns on."}
   {:cmds ["user-test"] :doc "Run the project's user-level test, :user-test in kit.edn."}
   {:cmds ["identity"]
    :doc  "Set up the agent's GitHub identity alone, for any repository."
    :spec [[:dir {:coerce :string :desc "The repository folder" :default-desc "this folder"}]]}
   {:cmds ["github"]
    :doc  "Check or set up the repository's main ruleset and access. The owner runs it in their own terminal."
    :spec [[:check {:coerce :string :desc "The one thing to check" :default-desc "checks"}]
           [:yes {:coerce :boolean :desc "Apply what is missing without asking"}]
           repo]}
   {:cmds ["gate"]
    :doc
    "The pull request gate: its shape rules, its size and the design approval. Pass --hook, --g1 or --shape, or --branch with --pr."
    :spec [[:branch {:coerce :string :desc "The pull request's branch"}]
           [:pr {:coerce :string :desc "The pull request's number"}]
           [:g1 {:coerce :string :desc "The issue whose design approval to read"}]
           [:hook {:coerce :boolean :desc "Answer as the edit hook, from Claude Code's input on stdin"}]
           [:shape {:coerce :boolean :desc "Only the shape rules, offline: with --branch and --title"}]
           [:title
            {:coerce :string
             :desc   "The pull request's title, for --shape"}]
           [:body-file {:coerce :string :desc "The pull request's body, for --shape"}]
           repo]}
   {:cmds ["hook"]
    :doc
    "What the project's entry hook says to one Bash command, without running it: allowed (exit 0), refused (2) or asks (3), with the reason."
    :args->opts [:command]
    :spec [[:command {:positional true :require true :coerce :string :desc "The Bash command, as one argument"}]]}
   {:cmds ["retro"]
    :doc
    "The friction data of a merged issue for its retrospective, or the merged issues that lack one (exit 1 when there are some)."
    :args->opts [:issue]
    :spec [[:issue {:positional true :coerce :string :desc "The merged issue's number; not with --missing"}]
           [:missing {:coerce :boolean :desc "Merged issues of the last 14 days without a retrospective"}]
           [:messages {:coerce :boolean :desc "Also the owner's marked messages"}]
           [:days {:coerce :string :desc "How many days back the friction log is read" :default-desc "14"}]
           [:now {:coerce :string :desc "The time to count back from, for a replay" :default-desc "the current time"}]
           repo]}
   {:cmds ["sync"]
    :doc  "The owner's main checkout after a merge: fast-forward the base, set .shared to its pin and run doctor."
    :spec [[:after-git {:coerce :boolean :desc "What the git hooks run after a pull, rebase or branch switch"}]]}
   {:cmds ["board"] :doc "The project board of the folder's kit.edn."}
   {:cmds ["board" "show"] :doc "Print the folder's board, from its kit.edn, as JSON."}
   {:cmds       ["board" "set"]
    :doc        "Set one field of one card."
    :args->opts [:project-number :owner :issue-url :field :option]
    :spec       [[:project-number {:positional true :require true :coerce :string :desc "The board's number"}]
                 [:owner {:positional true :require true :coerce :string :desc "The board's owner"}]
                 [:issue-url {:positional true :require true :coerce :string :desc "The issue's URL"}]
                 [:field {:positional true :require true :coerce :string :desc "The field's name"}]
                 [:option
                  {:positional true
                   :require    true
                   :coerce     :string
                   :desc       "The option to set"}]]}
   {:cmds ["start"]
    :doc
    "File the issue (or reuse one), put it on the board with its fields and create its branch. Nothing is filed until every check has passed."
    :spec
    [[:title
      {:coerce  :string
       :require true
       :desc    "Issue or draft title"}]
     [:body
      {:coerce :string
       :desc   "Body text"}]
     [:body-file {:coerce :string :desc "Body file path"}]
     [:labels {:coerce :string :desc "Comma-separated labels"}]
     [:assignees {:coerce :string :desc "Comma-separated assignees; @me is the agent" :default-desc "@me"}]
     [:issue {:coerce :string :desc "Reuse this issue number instead of creating one"}]
     [:showcase {:coerce :string :desc "Showcase field option" :default-desc "the folder's kit.edn"}]
     [:priority
      {:coerce :string
       :enum   ["Urgent" "High" "Medium" "Low"]
       :desc   "Priority field option. Required, here or as GHWF_DEFAULT_PRIORITY"}]
     [:size {:coerce :string :desc "Size field option to set"}]
     [:milestone {:coerce :string :desc "Open milestone to put the issue in (must already exist)"}]
     [:status
      {:coerce       :string
       :desc         "Status field option to set"
       :default-desc "In progress with --issue, else Backlog"}]
     [:mode {:coerce :string :enum ["issue" "draft-convert"] :desc "What to file" :default-desc "issue"}]
     [:owner {:coerce :string :desc "Project owner" :default-desc "the folder's kit.edn"}]
     repo
     [:project-number {:coerce :string :desc "Project number" :default-desc "the folder's kit.edn"}]
     [:showcase-field {:coerce :string :desc "Name of the Showcase field" :default-desc "Showcase"}]
     [:priority-field {:coerce :string :desc "Name of the Priority field" :default-desc "Priority"}]
     [:size-field {:coerce :string :desc "Name of the Size field" :default-desc "Size"}]
     [:status-field {:coerce :string :desc "Name of the Status field" :default-desc "Status"}]
     [:base {:coerce :string :desc "Base branch of the development branch" :default-desc "the folder's kit.edn"}]
     [:branch-name {:coerce :string :desc "Name of the development branch" :default-desc "<number>-<slug>"}]
     [:no-reuse-existing {:coerce :boolean :desc "Do not reuse an exact-title project item or issue"}]
     [:skip-branch {:coerce :boolean :desc "Do not create the development branch at all"}]
     [:no-checkout
      {:coerce :boolean
       :desc   "Create and link the branch, leave the calling worktree alone (not with --skip-branch)"}]
     [:skip-retro
      {:coerce :boolean
       :desc
       "Start although a merged issue lacks its retrospective. Only the owner waives this: a permission rule asks them first (#56)"}]]}])


(def dispatch-options
  "What every command reads its command line with: -h and --help print the help, a flag the command does not declare
  and a word it does not take are refused, a missing flag value too."
  {:prog "kit" :help true :restrict true :restrict-args true})
