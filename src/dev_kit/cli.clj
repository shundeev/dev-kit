(ns dev-kit.cli
  "The command tree of kit, as data: every command with its words, what it does and its options. kit's one edge
  (dev-kit.main) reads the command line against it, and help and the refusal of a mistake on the command line are
  built from it, so a flag cannot be in the code and not in the help. Requires nothing but babashka.cli: it is
  loaded for every command, among them the hook that runs on every edit, so it must stay light.
  A flag with a value is `:coerce :string` (else `--title` at the end of the line becomes true); a spec is a
  vector of pairs (else help lists the flags in no order).
  A flag is the only layer this tree owns: a value left out (the board, the owner, the base, the Status names) is
  filled by the command's read-input from the folder's kit.edn, then the GHWF_* environment, then its default, which
  the flag's :default-desc names. The tree checks a choice (:enum) and a required option; no recipe checks them again.")


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
       "Start although a merged issue lacks its retrospective. Only the owner waives this: a permission rule asks them first (#56)"}]]}
   {:cmds ["finish"]
    :doc
    "Commit what is staged, push, open the pull request and set the card to In review. Never merges: that is kit merge.

Run it from the issue branch (<number>-<slug>, made by kit start). It commits what is staged, or the --add paths.

Where main of the repository holds .github/workflows/ready-when-green.yml (ADR-0007), the pull request opens as a draft: GitHub asks the owners for a review only when that workflow marks it ready, once the required checks have passed, and it turns auto-merge on then. Elsewhere the pull request opens ready, with auto-merge on, and the owners are asked at once.

Subjects follow Conventional Commits with the issue in the scope, feat(GH-12): add landing page. GitHub appends the pull request's number on squash."
    :spec
    [[:type
      {:coerce       :string
       :enum         ["feat" "fix" "docs" "refactor" "test" "perf" "build" "ci" "chore"]
       :desc         "Commit type of the subject"
       :default-desc "the type of the branch's first commit, else feat"}]
     [:summary
      {:coerce       :string
       :desc         "Subject text after \"type(GH-n): \""
       :default-desc "the issue's title"}]
     [:commit-message-file
      {:coerce :string
       :desc   "Full commit message (subject, body, trailers); overrides --summary for the commit"}]
     [:pr-body-file
      {:coerce :string
       :desc
       "The pull request's description. Required when the pull request is opened. Must contain a line \"ADR: none\" or \"ADR: adr/NNNN-....md\". \"Closes #<issue>\" is added if missing, \"Part of #<issue>\" for an issue of size L (it has several pull requests)"}]
     [:last
      {:coerce :boolean
       :desc
       "The last pull request of an issue of size L: it says \"Closes #<issue>\", and kit merge closes the issue. Harmless on any other size, which says \"Closes\" anyway"}]
     [:add {:coerce [:string] :desc "Stage this path before committing (repeatable)"}]
     [:hold
      {:coerce :boolean
       :desc
       "Open the pull request with the label hold, for one that overlaps a pull request still waiting: the workflow leaves it a draft until kit release. Needs the ready workflow on main"}]
     [:no-commit
      {:coerce :boolean :desc "Commit nothing; push and open the pull request for commits already on the branch"}]
     repo
     [:owner {:coerce :string :desc "Board owner" :default-desc "the folder's kit.edn"}]
     [:project-number {:coerce :string :desc "Board number" :default-desc "the folder's kit.edn"}]
     [:base {:coerce :string :desc "The pull request's base branch" :default-desc "the folder's kit.edn"}]
     [:status-field {:coerce :string :desc "Name of the Status field" :default-desc "Status"}]
     [:review-status {:coerce :string :desc "Status option to set" :default-desc "In review"}]]}
   {:cmds ["release"]
    :doc
    "Free a held pull request: remove the label hold, mark it ready and turn auto-merge on.

For a pull request held with the label hold (kit finish --hold), once the pull request it waited for has merged: removes the label, marks it ready (GitHub then asks the owners for a review) and turns on auto-merge (ADR-0007). It also frees a draft that was never held but that nothing marked ready. It refuses, naming the check and changing nothing, while a required check on the pull request's head is not green."
    :spec
    [[:pr
      {:coerce :string :desc "The pull request, a number or a URL" :default-desc "the open one of the current branch"}]
     repo]}
   {:cmds ["merge"]
    :doc
    "After the owner's Approve: merge the pull request, close the issue out and clean up.

Run it from the issue branch, as a background command: the agent is woken when it exits. When the pull request is open, it needs the owner's Approve of the latest commit. A branch behind the base is updated by merging the base into it, which keeps the approval. It turns on auto-merge (squash) and waits until GitHub reports the pull request MERGED, or until merging became impossible (closed, a required check failed, DIRTY, the approval gone); it then stops and names the reason.

When GitHub already merged it, only the closeout runs: board Status \"Done\", the branch deleted locally and on GitHub, back to the base branch; each issue the pull request closes that is still open is closed as completed (auto-merge merges as github-actions, and GitHub then closes none). An issue of size L is closed only when the pull request's body says \"Closes #<issue>\" (kit finish --last writes it); otherwise it stays open, its card keeps its Status, and a line says so. A pull request that did not merge keeps its branch."
    :epilog "Environment: GHWF_MERGE_POLL, seconds between looks (default 10). Nothing else bounds the wait."
    :spec [repo
           [:owner {:coerce :string :desc "Board owner" :default-desc "the folder's kit.edn"}]
           [:project-number {:coerce :string :desc "Board number" :default-desc "the folder's kit.edn"}]
           [:base {:coerce :string :desc "The pull request's base branch" :default-desc "the folder's kit.edn"}]
           [:status-field {:coerce :string :desc "Name of the Status field" :default-desc "Status"}]
           [:done-status {:coerce :string :desc "Status option to set" :default-desc "Done"}]]}
   {:cmds ["checks"]
    :doc
    "Wait until the pull request's required checks have finished and say how they ended.

Waits until the checks the base branch requires exist on the pull request's head commit and have finished, and, where the base has the ready workflow, until that workflow has decided (ADR-0007). The checks are read from the base's rulesets (classic branch protection needs admin to read); a base that requires none ends it at once. Run it as a background command: the agent is woken when it exits.

exit 0  every required check passed, or the base requires none. Where the base has the ready workflow, it also waits for the completion of that workflow's run for this head (not for the pull request to leave draft: its review request and auto-merge come after) and reports the pull request's state: ready (owners asked, auto-merge on), held (the label hold: kit release), or still a draft (the run's own decision line)
exit 1  a required check failed (or was cancelled): each is named with its link, at once
exit 2  no result: the pull request was closed or merged, its head moved (a push: the new commit's checks are the ones to await; run it again), or gh could not be read (its message is printed)"
    :epilog "Environment: GHWF_MERGE_POLL, seconds between looks (default 10). Nothing else bounds the wait."
    :spec [[:pr {:coerce :string :desc "The pull request" :default-desc "the open one of the current branch"}]
           repo]}
   {:cmds ["review"]
    :doc
    "The owner's latest review of the pull request and the review threads still open; with --wait, after the owner's next review.

Prints the owner's latest review of the pull request (its state, time and text) and every review thread that is still unresolved: where it is, and each comment as author: text. The owner is GHWF_HUMAN, the person's own login on this machine; reviews by anyone else, the agent's own replies included, are not theirs.

With --wait it ends once the owner has a review submitted after the agent last gave the pull request back: the later of the head commit's date and the latest review by the account running kit (its thread replies are reviews too). A review of the owner's already after that is reported at once, so one submitted before the command started is not missed; any state counts (approved, changes requested, commented). It looks every poll period and prints as above. Run it as a background command: the agent is woken when it exits. Nothing bounds the wait.

exit 0  the review was printed (with --wait: a newer one came)
exit 1  GHWF_HUMAN is not set, or the pull request merged or closed while waiting: no review is coming
exit 2  gh could not be read (its message is printed)"
    :epilog
    "Environment: GHWF_HUMAN, the owner's login (kit init or kit identity set it); GHWF_MERGE_POLL, seconds between looks (default 10)."
    :spec [[:pr {:coerce :string :desc "The pull request" :default-desc "the open one of the current branch"}]
           repo
           [:wait {:coerce :boolean :desc "Wait for a review of the owner's after the last hand-back"}]]}])


(def dispatch-options
  "What every command reads its command line with: -h and --help print the help, a flag the command does not declare
  and a word it does not take are refused, a missing flag value too."
  {:prog "kit" :help true :restrict true :restrict-args true})
