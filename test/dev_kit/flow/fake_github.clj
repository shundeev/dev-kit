(ns dev-kit.flow.fake-github
  "An in-memory GitHub for the stories (#139): the same named operations as `dev-kit.flow.github/operations`, the
  same arguments and the same answers, over an atom that holds what exists: issues, the board's cards with their
  fields, labels, milestones and linked branches. A story reads the world afterwards instead of listing calls."
  (:require
   [clojure.string :as str]))


(defn new-world
  "An empty GitHub, or one with things in it. Options:
  :labels            labels the repository has
  :uncreatable       labels that cannot be created
  :milestones        open milestones
  :issues            issues that already exist: {:title :labels :state :on-board}; numbered from 1 in this order
  :drafts            titles of draft cards on the board
  :refuse-field      a board field (\"Showcase\") that refuses every option
  :refuse-board      true when the board refuses to take any issue
  :retro-missing     the answer of the retrospective check, {:exit :out :err}
  :current-branch    the branch checked out now
  :graphql           the GraphQL points left this hour (nil: GitHub does not say)
  :refuse-size-label true when the size:* label cannot be set"
  [{:keys [labels uncreatable milestones issues drafts refuse-field refuse-board retro-missing current-branch graphql
           refuse-size-label]}]
  (let [world (atom {:issues            {}
                     :cards             []
                     :labels            (set labels)
                     :created-labels    []
                     :uncreatable       (set uncreatable)
                     :milestones        (vec milestones)
                     :linked            {}
                     :current-branch    (or current-branch "main")
                     :next-number       1
                     :next-card         1
                     :refuse-field      refuse-field
                     :refuse-board      refuse-board
                     :retro-missing     (or retro-missing {:exit 0 :out "" :err ""})
                     :graphql           graphql
                     :refuse-size-label refuse-size-label
                     :label-reads       0})]
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


(defn- operations-over
  "Each operation of the real GitHub, as a function of the world's atom and the same arguments."
  [world]
  {:open-milestones
   (fn [_repo] (:milestones @world))

   :retro-missing
   (fn [_kit _repo] (:retro-missing @world))

   :graphql-remaining
   (fn [] (:graphql @world))

   :issue-url
   (fn [_repo number]
     (if-let [issue (get-in @world [:issues (number-of number)])]
       {:exit 0 :out (str (:url issue) "\n") :err ""}
       {:exit 1 :out "" :err (str "Could not resolve to an issue with the number of " number ".")}))

   :project-items
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

   :open-issues-titled
   (fn [_repo title]
     (vec (for [issue (issues world)
                :when (and (= "OPEN" (:state issue)) (str/includes? (:title issue) title))]
            (select-keys issue [:number :title :url]))))

   :repo-id
   (fn [_repo] "R_1")

   :convert-draft!
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

   :known-labels
   (fn [_repo] (:labels (swap! world update :label-reads inc)))

   :create-label!
   (fn [_repo label]
     (if (contains? (:uncreatable @world) label)
       false
       (do (swap! world #(-> % (update :labels conj label) (update :created-labels conj label))) true)))

   :file-issue!
   (fn [repo title _body-args label-args assignee-args]
     (let [w (swap! world file-issue
               repo
               title
               (flag-values "--label" label-args)
               (flag-values "--assignee" assignee-args))]
       (:url (get-in w [:issues (dec (:next-number w))]))))

   :create-draft!
   (fn [_project-number _owner title _body]
     (:id (last (:cards (swap! world new-card {:draft title})))))

   :item-add!
   (fn [_project-number _owner url]
     (if (:refuse-board @world)
       {:error "GraphQL: Could not resolve to a ProjectV2 (the fake board refuses)"}
       (let [w (swap! world (fn [w]
                              (if (card-index w url)
                                w
                                (new-card w {:issue (:number (issue-of-url w url))}))))]
         {:id (:id (get (:cards w) (card-index w url)))})))

   :edit-issue!
   (fn [_repo number args] (swap! world edit-issue number args) "")

   :set-field!
   (fn [_project-number _owner url field option]
     (if (= field (:refuse-field @world))
       (str "GraphQL: the fake board has no option '" option "' in " field)
       (do (swap! world (fn [w] (assoc-in w [:cards (card-index w url) :fields field] option))) nil)))

   :size-label!
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

   :linked-branch
   (fn [_repo number] (get-in @world [:linked (number-of number)]))

   :develop!
   (fn [_repo number develop-args]
     (let [n      (number-of number)
           w      @world
           named  (first (flag-values "--name" develop-args))
           branch (or named (get-in w [:linked n]) (str n "-" (slug (get-in w [:issues n :title]))))]
       (swap! world (fn [w]
                      (cond-> (assoc-in w [:linked n] branch)
                        (some #{"--checkout"} develop-args) (assoc :current-branch branch))))
       (str "https://github.com/o/r/tree/" branch)))

   :current-branch
   (fn [] (:current-branch @world))})


(def operation-names
  "The operations this GitHub answers; a story checks them against the real GitHub's."
  (set (keys (operations-over (atom {})))))


(defn label-reads
  "How often the repository's labels were read."
  [world]
  (:label-reads @world))


(defn github
  "The GitHub of the world, called as the real one: (github :op args…)."
  [world]
  (let [ops (operations-over world)]
    (fn [op & args]
      (apply (or (ops op) (throw (ex-info (str "no GitHub operation " op) {:op op}))) args))))
