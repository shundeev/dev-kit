(ns dev-kit.flow.fake-github
  "An in-memory GitHub for the stories (#139): the same named operations as `dev-kit.flow.github/operations`, kept
  in the same maps by command, with the same arguments and the same answers, over an atom that holds what exists:
  issues, the board's cards with their fields, labels, milestones, linked branches, pull requests with their checks,
  and what happens while a command waits. A story reads the world afterwards instead of listing calls."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.checks :as checks]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.merge :as merge-cmd]
   [dev-kit.flow.release :as release]
   [dev-kit.flow.review :as review]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.start :as start]
   [dev-kit.flow.wait :as wait]
   [dev-kit.main :as main]))


(def ^:private known-options
  #{:labels :uncreatable :milestones :issues :drafts :refuse-field :refuse-board :unretrospected :retro-unreadable
    :current-branch :graphql
    :refuse-size-label :prs :ready-workflow :script})


(defn new-world
  "An empty GitHub, or one with things in it. Options:
  :labels            labels the repository has
  :uncreatable       labels that cannot be created
  :milestones        open milestones
  :issues            issues that already exist: {:title :labels :state :on-board}; numbered from 1 in this order
  :drafts            titles of draft cards on the board
  :refuse-field      a board field (\"Showcase\") that refuses every option
  :refuse-board      true when the board refuses to take any issue
  :unretrospected    merged issues without their retrospective, as [number title] (default none)
  :retro-unreadable  gh's message when the retrospective search cannot be read
  :current-branch    the branch checked out now
  :graphql           the GraphQL points left this hour (nil: GitHub does not say)
  :refuse-size-label true when the size:* label cannot be set
  :prs               pull requests that exist, numbered after the issues in this order: {:branch :state (\"OPEN\")
                     :draft :labels :head :base (\"main\") :decision :merge-state :auto-merge :owners :checks
                     :unreadable :checks-unreadable}; :checks are the required ones, {:name :bucket :link};
                     :unreadable and :checks-unreadable are gh's output when reading it fails
  :ready-workflow    true when the base branch holds the ready workflow
  :script            what happens on GitHub while a command waits: one function of the world per pause, in order;
                     a pause the script does not plan is an error
  Any other option goes into the world as it is: a command's operations name the ones they read."
  [{:keys [labels uncreatable milestones issues drafts refuse-field refuse-board unretrospected retro-unreadable
           current-branch graphql
           refuse-size-label prs ready-workflow script]
    :as   options}]
  (let [world (atom
               {:issues           {}
                :cards            []
                :labels           (set labels)
                :created-labels   []
                :uncreatable      (set uncreatable)
                :milestones       (vec milestones)
                :linked           {}
                :current-branch   (or current-branch "main")
                :next-number      1
                :next-card        1
                :refuse-field     refuse-field
                :refuse-board     refuse-board
                :unretrospected   (vec unretrospected)
                :retro-unreadable retro-unreadable
                :graphql          graphql
                :refuse-size-label refuse-size-label
                :label-reads      0
                :prs              {}
                :ready-workflow   (boolean ready-workflow)
                :script           (vec script)
                :slept            []})]
    (swap! world merge (apply dissoc options known-options))
    (doseq [issue issues]
      (swap! world (fn [w]
                     (let [n (:next-number w)]
                       (cond-> (assoc-in w
                                [:issues n]
                                {:number    n
                                 :title     (:title issue)
                                 :url       (str "https://github.com/o/r/issues/" n)
                                 :labels    (vec (:labels issue))
                                 :assignees []
                                 :milestone nil
                                 :state     (or (:state issue) "OPEN")})
                         :always (update :next-number inc)
                         (:on-board issue) (update :cards conj {:id (str "PVTI_" (:next-card w)) :issue n :fields {}})
                         (:on-board issue) (update :next-card inc))))))
    (doseq [title drafts]
      (swap! world (fn [w]
                     (-> w
                         (update :cards conj {:id (str "PVTI_" (:next-card w)) :draft title :fields {}})
                         (update :next-card inc)))))
    (doseq [pr prs]
      (swap! world (fn [w]
                     (let [n (:next-number w)]
                       (-> w
                           (assoc-in [:prs n]
                            (merge {:state "OPEN" :base "main" :labels [] :owners [] :checks []}
                                   pr
                                   {:number n :url (str "https://github.com/o/r/pull/" n)}))
                           (update :next-number inc))))))
    world))


;; ------------------------------------------------------------------ reading the world


(defn issues
  "The issues, by number."
  [world]
  (->> (:issues @world)
       (sort-by key)
       (mapv val)))


(defn cards
  "The board's cards, each with its title, the issue it is (nil for a draft), and its fields by name."
  [world]
  (let [w @world]
    (mapv (fn [card]
            {:id     (:id card)
             :title  (if (:issue card)
                       (get-in w [:issues (:issue card) :title])
                       (:draft card))
             :issue  (:issue card)
             :draft? (boolean (:draft card))
             :fields (:fields card)})
          (:cards w))))


(defn linked-branches
  "The branches linked to issues: {issue number branch}."
  [world]
  (:linked @world))


(defn checked-out
  "The branch checked out now."
  [world]
  (:current-branch @world))


(defn created-labels
  "The labels created in the repository since the start, in order."
  [world]
  (:created-labels @world))


(defn prs
  "The pull requests, by number."
  [world]
  (->> (:prs @world)
       (sort-by key)
       (mapv val)))


(defn local-branches
  "The branches the clone has."
  [world]
  (set (:local-branches @world)))


(defn remote-branches
  "The branches GitHub has."
  [world]
  (set (:remote-branches @world)))


(defn slept
  "The pauses a command took while it waited, in seconds, in order."
  [world]
  (:slept @world))


;; ------------------------------------------------------------------ what happens while a command waits


(defn clock
  "The clock a story substitutes for wait/sleep!: each pause runs the next event of the world's script."
  [world]
  (fn [seconds]
    (swap! world (fn [w]
                   (let [[event & later] (:script w)]
                     (when-not event
                       (throw (ex-info (str "the story's script ran out: the command paused "
                                            (inc (count (:slept w)))
                                            " times, the script plans "
                                            (count (:slept w)))
                                       {:slept (:slept w)})))
                     (-> (event w)
                         (assoc :script (vec later))
                         (update :slept conj seconds)))))
    nil))


(defn pr-becomes
  "An event of the script: the pull request numbered n takes these fields, e.g. {:state \"MERGED\"}."
  [n fields]
  (fn [w] (update-in w [:prs n] merge fields)))


(defn github-fails
  "An event of the script: from now on every read a wait makes is answered with this gh output, e.g. \"HTTP 503\"."
  [output]
  (fn [w] (assoc w :github-error output)))


(defn github-recovers
  "An event of the script: GitHub answers again."
  [w]
  (dissoc w :github-error))


(def nothing-happens
  "An event of the script: a pause after which GitHub is as before."
  identity)


;; ------------------------------------------------------------------ the operations


(defn- number-of
  [n]
  (if (string? n)
    (parse-long n)
    n))


(defn- slug
  [title]
  (-> title
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))


(defn- flag-values
  "The values that follow a flag in a gh argument list: [\"--label\" \"a\" \"--label\" \"b\"] → [\"a\" \"b\"]."
  [flag args]
  (->> (partition 2 1 args)
       (keep (fn [[a b]] (when (= a flag) b)))
       vec))


(defn- issue-of-url [w url] (some #(when (= url (:url %)) %) (vals (:issues w))))


(defn- card-index
  [w url]
  (let [issue (issue-of-url w url)]
    (first (keep-indexed (fn [i c] (when (and issue (= (:number issue) (:issue c))) i)) (:cards w)))))


(defn- need-label!
  [w label]
  (when-not (contains? (:labels w) label)
    (throw (ex-info (str "the fake GitHub has no label '" label "': gh would refuse") {:label label}))))


(defn- new-card
  [w card]
  (-> w
      (update :cards conj (assoc card :id (str "PVTI_" (:next-card w)) :fields {}))
      (update :next-card inc)))


(defn- file-issue
  [w repo title labels assignees]
  (doseq [l labels] (need-label! w l))
  (let [n (:next-number w)]
    (-> w
        (assoc-in [:issues n]
         {:number    n
          :title     title
          :url       (str "https://github.com/" repo "/issues/" n)
          :labels    (vec labels)
          :assignees (vec assignees)
          :milestone nil
          :state     "OPEN"})
        (update :next-number inc))))


(defn- edit-issue
  [w number args]
  (let [n      (number-of number)
        adds   (flag-values "--add-label" args)
        gone   (set (flag-values "--remove-label" args))
        people (flag-values "--add-assignee" args)
        ms     (first (flag-values "--milestone" args))]
    (doseq [l adds] (need-label! w l))
    (-> w
        (update-in [:issues n :labels] #(vec (distinct (concat (remove gone %) adds))))
        (update-in [:issues n :assignees] #(vec (distinct (concat % people))))
        (cond-> ms (assoc-in [:issues n :milestone] ms)))))


(defn- pr-of
  "The pull request a gh argument names: its number or its URL."
  [w pr]
  (if (re-matches #"\d+" (str pr))
    (get-in w [:prs (parse-long (str pr))])
    (some #(when (= pr (:url %)) %) (vals (:prs w)))))


(defn- shared-substitutes
  "What the world answers in place of the functions several commands share, by the var of each."
  [world]
  {#'github/head-branch
   (fn [] (:current-branch @world))

   #'github/open-pr-url
   (fn [_repo branch state]
     (some #(when (and (= branch (:branch %)) (= (str/upper-case state) (:state %))) (:url %)) (prs world)))

   #'github/read-pr
   (fn [pr _repo fail]
     (let [found (pr-of @world pr)]
       (cond (:github-error @world)
             (fail (github/gh-failure-message "pr view" (:github-error @world)) github/gh-failure-fix)

             (nil? found)
             (fail (github/gh-failure-message
                    "pr view"
                    (str "GraphQL: Could not resolve to a PullRequest with the number of " pr "."))
                   github/gh-failure-fix)
             (:unreadable found) (fail (github/gh-failure-message "pr view" (:unreadable found))
                                       github/gh-failure-fix)
             :else
             {:state       (:state found)
              :decision    (:decision found)
              :merge-state (:merge-state found)
              :head        (:head found)
              :base        (:base found)
              :draft       (boolean (:draft found))
              :hold        (boolean (some #{"hold"} (:labels found)))
              :owners      (:owners found)
              :auto-merge  (boolean (:auto-merge found))})))

   #'github/required-checks
   (fn [pr _repo fail]
     (let [found (pr-of @world pr)]
       (if-let [output (or (:github-error @world) (:checks-unreadable found))]
         (fail (github/checks-failure-message output) (github/checks-failure-fix pr))
         (vec (:checks found)))))

   #'github/ready-workflow?
   (fn
     ([_repo _base] (:ready-workflow @world))
     ([repo base fail]
      (if-let [message (:workflow-unreadable @world)]
        (fail (github/ready-workflow-failure repo base 1 message) github/ready-workflow-fix)
        (:ready-workflow @world))))

   #'github/auto-merge!
   (fn [pr-url]
     (let [found (pr-of @world pr-url)
           turn  (fn [fields] (swap! world update-in [:prs (:number found)] merge fields))]
       (if-let [message
                (or
                 (:refuse-auto-merge found)
                 (when (or (:no-auto-merge @world) (:draft found))
                   "GraphQL: Pull request Auto merge is not allowed for this repository (enablePullRequestAutoMerge)"))]
         (do (when (:merges-meanwhile found)
               (turn {:state "MERGED"}))
             {:exit 1 :out "" :err message})
         (do (turn {:auto-merge true}) {:exit 0 :out "" :err ""}))))

   #'github/issue-size
   (fn [_repo number]
     (some #(second (re-matches #"size:(.+)" %)) (get-in @world [:issues (number-of number) :labels])))

   #'github/issue-url
   (fn [_repo number]
     (if-let [issue (get-in @world [:issues (number-of number)])]
       {:exit 0 :out (str (:url issue) "\n") :err ""}
       {:exit 1 :out "" :err (str "Could not resolve to an issue with the number of " number ".")}))

   #'board/board-set-field
   (fn [_project-number _owner url field option]
     (if (= field (:refuse-field @world))
       (str "GraphQL: the fake board has no option '" option "' in " field)
       (do (swap! world (fn [w] (assoc-in w [:cards (card-index w url) :fields field] option))) nil)))

   #'shell/resolve-repo
   (fn [explicit] (or explicit "o/r"))

   #'wait/sleep!
   (clock world)})


(defn commits
  "The commit subjects on the branch, oldest first: what kit finish has made, after those the story started with."
  [world]
  (vec (:commits @world)))


(defn staged
  "The paths staged and not yet committed."
  [world]
  (vec (:staged @world)))


(defn- update-pr
  "The pull request a gh argument names, changed by `change`."
  [world pr change]
  (let [number (:number (pr-of @world pr))]
    (swap! world update-in [:prs number] change)))


(defn- git-refuses!
  [message]
  (throw (ex-info (format "git would refuse: %s" message) {})))


(defn start-substitutes
  "What the world answers in place of kit start's own calls, by the var of each: the functions right above its steps."
  [world]
  {#'start/item-add!
   (fn [_project-number _owner url]
     (if (:refuse-board @world)
       {:error "GraphQL: Could not resolve to a ProjectV2 (the fake board refuses)"}
       (let [w (swap! world (fn [w]
                              (if (card-index w url)
                                w
                                (new-card w {:issue (:number (issue-of-url w url))}))))]
         {:id (:id (get (:cards w) (card-index w url)))})))

   #'start/open-milestones
   (fn [_repo] (:milestones @world))

   #'base/gh!
   (fn [& args]
     (when-let [message (:retro-unreadable @world)]
       (throw (ex-info message {:args args})))
     (json/generate-string
      {"data" {"search" {"nodes" (for [[number title] (:unretrospected @world)]
                                   {"number"   number
                                    "title"    title
                                    "comments" {"nodes" []}
                                    "closedByPullRequestsReferences" {"nodes" [{"mergedAt"
                                                                                "2026-11-11T10:00:00Z"}]}})}}}))

   #'start/graphql-remaining
   (fn [] (:graphql @world))

   #'start/project-items
   (fn [_owner _number _status-field]
     (let [w @world]
       (vec (for [card (:cards w)
                  :let [issue (get-in w [:issues (:issue card)])]]
              {:id      (:id card)
               :title   (if issue
                          (:title issue)
                          (:draft card))
               :status  (get-in card [:fields "Status"] "")
               :content (if issue
                          {:type "Issue" :number (:number issue) :url (:url issue) :state (:state issue)}
                          {:type "DraftIssue" :number nil :url nil :state nil})}))))

   #'start/open-issues-titled
   (fn [_repo title]
     (vec (for [issue (issues world)
                :when (and (= "OPEN" (:state issue)) (str/includes? (:title issue) title))]
            (select-keys issue [:number :title :url]))))

   #'start/repo-id
   (fn [_repo] "R_1")

   #'start/convert-draft!
   (fn [item-id _repo-id]
     (let [w     @world
           index (first (keep-indexed (fn [i c] (when (= item-id (:id c)) i)) (:cards w)))
           card  (get (:cards w) index)]
       (when (:draft card)
         (let [w2 (file-issue w "o/r" (:draft card) [] [])
               n  (:next-number w)]
           (reset! world (-> w2
                             (update-in [:cards index] #(-> % (dissoc :draft) (assoc :issue n)))))
           (select-keys (get-in @world [:issues n]) [:number :url])))))

   #'start/repo-labels
   (fn [_repo] (:labels (swap! world update :label-reads inc)))

   #'start/create-label!
   (fn [_repo label]
     (if (contains? (:uncreatable @world) label)
       false
       (do (swap! world #(-> % (update :labels conj label) (update :created-labels conj label))) true)))

   #'start/file-issue!
   (fn [repo title _body-args label-args assignee-args]
     (let [w (swap! world file-issue
               repo
               title
               (flag-values "--label" label-args)
               (flag-values "--assignee" assignee-args))]
       (:url (get-in w [:issues (dec (:next-number w))]))))

   #'start/create-draft!
   (fn [_project-number _owner title _body]
     (:id (last (:cards (swap! world new-card {:draft title})))))

   #'start/edit-issue!
   (fn [_repo number args] (swap! world edit-issue number args) "")

   #'start/size-label!
   (fn [_repo number size]
     (let [label (str "size:" size)]
       (if (:refuse-size-label @world)
         false
         (do
           (swap! world (fn [w]
                          (let [n    (number-of number)
                                gone (filter #(and (str/starts-with? % "size:") (not= % label))
                                             (get-in w [:issues n :labels]))]
                            (-> w
                                (update :labels conj label)
                                (edit-issue n
                                            (concat ["--add-label" label]
                                                    (mapcat vector (repeat "--remove-label") gone)))))))
           true))))

   #'start/linked-branch
   (fn [_repo number] (get-in @world [:linked (number-of number)]))

   #'start/develop!
   (fn [_repo number develop-args]
     (let [n      (number-of number)
           w      @world
           named  (first (flag-values "--name" develop-args))
           branch (or named (get-in w [:linked n]) (str n "-" (slug (get-in w [:issues n :title]))))]
       (swap! world (fn [w]
                      (cond-> (assoc-in w [:linked n] branch)
                        (some #{"--checkout"} develop-args) (assoc :current-branch branch))))
       (str "https://github.com/o/r/tree/" branch)))

   #'start/current-branch
   (fn [] (:current-branch @world))})


(defn merge-substitutes
  "What the world answers in place of kit merge's own calls, by var. Besides the
  world's pull requests, it reads these options of new-world:
  :local-branches    the branches the clone has
  :remote-branches   the branches GitHub has
  :other-worktrees   the branches checked out in the clone's other worktrees
  :unclosable        numbers of issues that GitHub refuses to close
  and these of a pull request: :body (its description), :closes (numbers of the issues GitHub lists as closed by it), :refuse-update (gh refuses to update the
  branch), :refuse-auto-merge (the message gh gives), :merges-meanwhile (GitHub merges it while it refuses auto-merge),
  :refuse-squash. It keeps what was done to a pull request: :updates (how often its branch was updated), :squashed."
  [world]
  {#'merge-cmd/update-branch!
   (fn [pr-url]
     (if (:refuse-update (pr-of @world pr-url))
       1
       (do (update-pr world pr-url #(-> % (assoc :merge-state "CLEAN") (update :updates (fnil inc 0)))) 0)))

   #'merge-cmd/squash-merge!
   (fn [pr-url]
     (if (:refuse-squash (pr-of @world pr-url))
       1
       (do (update-pr world pr-url #(assoc % :state "MERGED" :squashed true)) 0)))

   #'merge-cmd/closing-issues
   (fn [pr-url _fail]
     (vec (for [number (:closes (pr-of @world pr-url))]
            (select-keys (get-in @world [:issues number]) [:number :url]))))

   #'merge-cmd/pr-body
   (fn [pr-url _fail] (:body (pr-of @world pr-url) ""))

   #'merge-cmd/issue-state
   (fn [issue-url] (:state (issue-of-url @world issue-url)))

   #'merge-cmd/close-issue!
   (fn [issue-url note]
     (let [issue (issue-of-url @world issue-url)]
       (if (contains? (set (:unclosable @world)) (:number issue))
         {:exit 1 :out "" :err "GraphQL: the fake GitHub refuses to close it"}
         (do (swap! world update-in [:issues (:number issue)] assoc :state "CLOSED" :closed-with note)
             {:exit 0 :out "" :err ""}))))

   #'merge-cmd/fetch-base!
   (fn [_base] nil)

   #'merge-cmd/worktree-branches
   (fn []
     (let [current @world]
       (concat (:other-worktrees current)
               (when-not (= "HEAD" (:current-branch current))
                 [(:current-branch current)]))))

   #'merge-cmd/detach-at-base!
   (fn [_base] (swap! world assoc :current-branch "HEAD") nil)

   #'merge-cmd/switch-to!
   (fn [branch]
     (when (some #{branch} (:other-worktrees @world))
       (git-refuses! (format "'%s' is checked out in another worktree" branch)))
     (swap! world assoc :current-branch branch)
     nil)

   #'merge-cmd/pull-ff-only!
   (fn [] nil)

   #'merge-cmd/delete-local-branch!
   (fn [branch]
     (when (= branch (:current-branch @world))
       (git-refuses! (format "cannot delete the branch '%s' it is checked out" branch)))
     (swap! world update :local-branches #(disj (set %) branch))
     nil)

   #'merge-cmd/remote-branch?
   (fn [branch] (contains? (set (:remote-branches @world)) branch))

   #'merge-cmd/delete-remote-branch!
   (fn [branch] (swap! world update :remote-branches #(disj (set %) branch)) nil)})


(defn label-reads
  "How often the repository's labels were read."
  [world]
  (:label-reads @world))


;; ------------------------------------------------------------------ the world in place of the real one, by var


(defn finish-substitutes
  "What the world answers in place of kit finish's own calls, by the var of each: the functions right above its
  steps. Options of the world they read, besides the ones new-world knows:
  :commits           subjects of the commits on the branch, oldest first (what git log origin/main..HEAD shows)
  :staged            paths staged already
  :workflow-unreadable  gh's message when GitHub cannot say whether the base holds the ready workflow
  :refuse-pr-edit    gh's message when it refuses to edit a pull request
  :no-auto-merge     true when the repository does not allow auto-merge
  The branch checked out is :current-branch; a push or a commit that git would refuse ends the real run inside the
  call, so the in-memory one has no such case."
  [world]
  {#'finish/branch-commit-types
   (fn [_base] (:commits @world []))

   #'finish/issue-title
   (fn [_repo number]
     (or (get-in @world [:issues (number-of number) :title])
         (throw (ex-info (format "the fake GitHub has no issue %s: gh would end the run" number) {:number number}))))

   #'finish/pr-draft?
   (fn [pr-url] (boolean (:draft (pr-of @world pr-url))))

   #'finish/create-hold-label!
   (fn [_repo]
     (let [current @world]
       (cond (contains? (:labels current) "hold")
             {:exit 1 :out "" :err "label with name \"hold\" already exists"}
             (contains? (:uncreatable current) "hold")
             {:exit 1 :out "" :err "HTTP 403: the fake GitHub does not let this token create labels"}
             :else (do (swap! world #(-> % (update :labels conj "hold") (update :created-labels conj "hold")))
                       {:exit 0 :out "" :err ""}))))

   #'finish/stage!
   (fn [paths] (swap! world update :staged #(vec (distinct (concat % paths)))) nil)

   #'finish/staged?
   (fn [] (boolean (seq (:staged @world))))

   #'finish/commit!
   (fn [_message-file subject]
     (swap! world #(-> % (update :commits (fnil conj []) subject) (assoc :staged [])))
     nil)

   #'finish/push!
   (fn [branch] (swap! world update :remote-branches (fnil conj []) branch) nil)

   #'finish/create-pr!
   (fn [repo base branch flags title body]
     (let [current @world]
       (when-not (some #{branch} (:remote-branches current))
         (throw (ex-info (format "the branch %s is not on GitHub: gh pr create would refuse" branch) {:branch branch})))
       (let [number (:next-number current)
             url    (format "https://github.com/%s/pull/%s" repo number)]
         (swap! world #(-> %
                           (assoc-in [:prs number]
                            {:number number
                             :url    url
                             :branch branch
                             :base   base
                             :state  "OPEN"
                             :draft  (boolean (some #{"--draft"} flags))
                             :labels (flag-values "--label" flags)
                             :owners []
                             :checks []
                             :title  title
                             :body   body})
                           (update :next-number inc)))
         url)))

   #'finish/edit-pr!
   (fn [pr-url args]
     (if-let [refusal (:refuse-pr-edit @world)]
       {:exit 1 :out "" :err refusal}
       (let [{:keys [number]} (pr-of @world pr-url)]
         (swap! world update-in
           [:prs number]
           (fn [pr]
             (-> pr
                 (update :owners #(vec (distinct (concat % (flag-values "--add-reviewer" args)))))
                 (update :assignees #(vec (distinct (concat % (flag-values "--add-assignee" args)))))
                 (update :labels #(vec (distinct (concat % (flag-values "--add-label" args))))))))
         {:exit 0 :out "" :err ""})))})


(defn release-substitutes
  "What the world answers in place of kit release's own calls, by var. A pull request that does not exist is an error,
  as gh's would end the run. A PR's :refuse-auto-merge is the text GitHub answers when it does not allow auto-merge
  (the shared auto-merge!)."
  [world]
  (let [pr-or-throw (fn [pr]
                      (or (pr-of @world pr) (throw (ex-info (str "no pull request " pr) {:pr pr}))))
        change-pr   (fn [pr change] (swap! world update-in [:prs (:number (pr-or-throw pr))] change))]
    {#'release/view-pr-for-release
     (fn [pr _repo]
       (let [found (pr-or-throw pr)]
         {:state (:state found)
          :draft (boolean (:draft found))
          :hold  (boolean (some #{"hold"} (:labels found)))
          :url   (:url found)}))

     #'release/remove-label!
     (fn [pr _repo label] (change-pr pr #(update % :labels (fn [labels] (vec (remove #{label} labels))))))

     #'release/mark-ready!
     (fn [pr _repo] (change-pr pr #(assoc % :draft false)))}))


(defn checks-substitutes
  "What the world answers in place of kit checks' own calls, by var. The world's options: :required-contexts, the checks
  the base requires (default none); :rules-unreadable, gh's output when reading the base's rules fails; :ready-runs,
  the ready workflow's runs {:databaseId :status :log}, which an event of the script may change."
  [world]
  {#'checks/required-contexts
   (fn [repo base fail]
     (if-let [output (:rules-unreadable @world)]
       (fail (github/gh-failure-message (str "api repos/" repo "/rules/branches/" base) output)
             github/gh-failure-fix)
       (vec (distinct (:required-contexts @world)))))

   #'checks/ready-runs
   (fn [_repo _fail] (mapv #(select-keys % [:databaseId :status]) (:ready-runs @world)))

   #'checks/run-log
   (fn [_repo id] (some #(when (= id (:databaseId %)) (:log %)) (:ready-runs @world)))})


(defn review-substitutes
  "What the world answers in place of kit review's own calls, by var. The world's :viewer is who runs kit (default
  \"the-agent\"); a pull request's :committed is its head commit's date. A pull request's :reviews are {:author :state
  :at :body} and its :threads {:resolved? :path :line :comments [{:author :body}]}, as the real read gives them;
  :reviews-unreadable is gh's output when reading them fails."
  [world]
  {#'review/graphql-reviews
   (fn [pr _repo fail]
     (let [found (pr-of @world pr)]
       (if-let [output (or (:github-error @world) (:reviews-unreadable found))]
         (fail (github/gh-failure-message "api graphql" output) github/gh-failure-fix)
         {:state          (:state found)
          :viewer         (:viewer @world "the-agent")
          :head-committed (:committed found)
          :reviews        (vec (:reviews found))
          :threads        (vec (:threads found))})))})


(def ^:private real-world-primitives
  "The calls that reach gh, git or a process, with the name a refusal gives."
  [[#'shell/run "shell/run"]
   [#'shell/run-on-terminal "shell/run-on-terminal"]
   [#'shell/gh! "shell/gh!"]
   [#'shell/gh-out "shell/gh-out"]
   [#'shell/git-out "shell/git-out"]
   [#'board/board-gh! "board/board-gh!"]
   [#'base/gh! "base/gh!"]])


(defn- real-world-guards
  "Each real-world primitive, replaced by a function that fails the story naming the call. An Error, not an
  exception: a step's `catch Exception` must not hide that a substitute is missing."
  []
  (into {}
        (for [[v label] real-world-primitives]
          [v
           (fn [& args]
             (throw (AssertionError. (str "the story reached the real world: " label " " (str/join " " args)))))])))


(defn options-of
  "The options a flow command receives from the words typed after it, read against the command tree as kit reads them."
  [command args]
  (:opts (main/command-line (into [command] args))))


(defn with-github
  "Runs `f` with the world in place of GitHub and git: the shared functions, the command's own, the clock, all by
  var, over the world's atom. A call with no substitute reaches a guard that fails the story naming the call. The
  substitution is global while `f` runs, so stories run one after another."
  [world f]
  (with-redefs-fn (merge (real-world-guards)
                         (shared-substitutes world)
                         (start-substitutes world)
                         (finish-substitutes world)
                         (merge-substitutes world)
                         (release-substitutes world)
                         (checks-substitutes world)
                         (review-substitutes world))
    f))
