(ns dev-kit.flow.start-test
  "kit start, told as the owner's stories (#139, ADR-0008). Each runs the command against an in-memory GitHub and
  says what exists afterwards: a test names what the owner sees, not which calls were made."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.start :as start]
   [dev-kit.flow.steps :as steps]))


(def ^:private variables
  {"GHWF_OWNER" "u473t8"
   "GHWF_PROJECT_NUMBER" "3"})


(defn- kit-start
  "Runs kit start with the command-line words against the world, its output captured. Returns the result, with the
  lines printed on stderr, the warnings, under :warnings."
  [world & args]
  (let [out    (java.io.StringWriter.)
        err    (java.io.StringWriter.)
        input  (assoc (start/read-input (vec args) (fn [variable default] (get variables variable default)))
                      :kit
                      "bin/kit")
        result (binding [*out* out
                         *err* err]
                 (fake/with-github world #(start/start input)))]
    (assoc result :warnings (str/split-lines (str err)))))


(defn- nothing-exists?
  [world]
  (and (empty? (fake/issues world)) (empty? (fake/cards world)) (empty? (fake/linked-branches world))))


(defn- said
  [result]
  (str/replace-first (:text (steps/refusal result)) #"^Error: " ""))


(deftest a-new-task-gets-an-issue-a-card-with-its-fields-and-a-branch-named-after-it
  (let [world  (fake/new-world {})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--size" "S" "--showcase" "Yes")]
    (is (= :ok (::steps/status result)) "the start succeeds")
    (is (= ["Add landing page"] (map :title (fake/issues world))) "one issue is filed, with the title")
    (is (= ["@me"] (:assignees (first (fake/issues world)))) "and it is assigned to the owner")
    (is (= [{:title  "Add landing page"
             :issue  1
             :fields {"Showcase" "Yes" "Priority" "High" "Size" "S" "Status" "Backlog"}}]
           (map #(select-keys % [:title :issue :fields]) (fake/cards world)))
        "one card is on the board, and it carries the showcase, priority, size and the default status")
    (is (= "1-add-landing-page" (:branch result)) "the answer names the branch after the task")
    (is (= {1 "1-add-landing-page"} (fake/linked-branches world)) "that branch is linked to the issue")
    (is (= "1-add-landing-page" (fake/checked-out world)) "and it is the one checked out")))


(deftest starting-the-same-task-twice-files-nothing-twice
  (let [world (fake/new-world {})
        args  ["--title" "Add landing page" "--priority" "High"]
        once  (apply kit-start world args)
        again (apply kit-start world args)]
    (is (= :ok (::steps/status again)) "the second start succeeds")
    (is (= 1 (count (fake/issues world))) "still one issue")
    (is (= 1 (count (fake/cards world))) "still one card")
    (is (= (:number once) (:number again)) "the second start picks up the same issue")
    (is (= (:branch once) (:branch again)) "and the same branch")
    (is (= {1 (:branch once)} (fake/linked-branches world)) "no second branch is linked")))


(deftest contradictory-branch-flags-stop-before-anything-exists
  (let [world  (fake/new-world {})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--skip-branch" "--no-checkout")]
    (is (= :fail (::steps/status result)) "the start stops")
    (is (= :start/branch-flags-conflict (get-in result [::steps/problem :problem])) "and says the flags contradict")
    (is (str/includes? (said result) "mutually exclusive") "in today's words")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest an-unknown-milestone-stops-before-anything-exists-and-the-answer-names-it
  (let [world  (fake/new-world {:milestones ["v1"]})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--milestone" "v2")]
    (is (= :fail (::steps/status result)) "the start stops")
    (is (= :start/unknown-milestone (get-in result [::steps/problem :problem])) "because the milestone is unknown")
    (is (str/includes? (said result) "'v2'") "the answer names the milestone asked for")
    (is (str/includes? (said result) "v1") "and lists the open ones")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest a-missing-priority-and-one-off-the-scale-stop-before-anything-exists
  (let [world   (fake/new-world {})
        missing (kit-start world "--title" "Add landing page")
        off     (kit-start world "--title" "Add landing page" "--priority" "Critical")]
    (is (= :start/no-priority (get-in missing [::steps/problem :problem])) "no priority is refused")
    (is (str/includes? (said missing) "Urgent|High|Medium|Low") "and the answer lists the scale")
    (is (= :start/off-scale-priority (get-in off [::steps/problem :problem])) "a priority off the scale is refused")
    (is (str/includes? (said off) "'Critical'") "and the answer names it")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest a-start-without-a-title-stops-before-anything-exists
  (let [world  (fake/new-world {})
        result (kit-start world "--priority" "High")]
    (is (= :start/no-title (get-in result [::steps/problem :problem])) "no title is refused")
    (is (str/includes? (said result) "--title is required") "in today's words")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest when-the-board-refuses-a-field-the-answer-names-the-issue-already-filed-and-no-branch-is-made
  (let [world  (fake/new-world {:refuse-field "Size"})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--size" "S")]
    (is (= :fail (::steps/status result)) "the start stops")
    (is (= :start/field-refused (get-in result [::steps/problem :problem])) "because the board refused the field")
    (is (str/includes? (said result) "#1 is filed, but its Size")
        "the answer names the issue already filed and the field")
    (is (str/includes? (said result) "Fix: run kit start again with --issue 1") "and says how to go on")
    (is (= 1 (count (fake/issues world))) "the issue exists")
    (is (empty? (fake/linked-branches world)) "no branch is made")
    (is (= "main" (fake/checked-out world)) "and the working tree is where it was")))


(deftest when-the-board-will-not-take-the-issue-the-answer-names-the-issue-already-filed
  (let [world  (fake/new-world {:refuse-board true})
        result (kit-start world "--title" "Add landing page" "--priority" "High")]
    (is (= :start/board-refused (get-in result [::steps/problem :problem])) "the board's refusal is the problem")
    (is (str/includes? (said result) "#1 is filed but could not be put on the board") "the answer names the issue")
    (is (str/includes? (said result) "Fix: run kit start again with --issue 1") "and says how to go on")
    (is (= 1 (count (fake/issues world))) "the issue exists")
    (is (empty? (fake/linked-branches world)) "no branch is made")))


(deftest a-draft-card-with-the-same-title-becomes-the-issue-not-a-second-one
  (let [world  (fake/new-world {:drafts ["Add landing page"]})
        result (kit-start world "--title" "Add landing page" "--priority" "High")
        [card] (fake/cards world)]
    (is (= :ok (::steps/status result)) "the start succeeds")
    (is (= 1 (count (fake/cards world))) "the board still has one card")
    (is (false? (:draft? card)) "and it is an issue now, not a draft")
    (is (= 1 (count (fake/issues world))) "one issue exists")
    (is (= "1" (:number result)) "the answer names that issue")
    (is (= "High" (get-in card [:fields "Priority"])) "and the card carries the priority")))


(deftest an-issue-number-wins-over-a-title-match-and-files-nothing-new
  (let [world  (fake/new-world {:issues [{:title "Add landing page" :on-board true} {:title "Fix the footer"}]})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--issue" "2")
        by-n   (into {} (map (juxt :issue identity)) (fake/cards world))]
    (is (= :ok (::steps/status result)) "the start succeeds")
    (is (= "2" (:number result)) "the answer is the issue named by number, not the one with the title")
    (is (= 2 (count (fake/issues world))) "nothing new is filed")
    (is (= "High" (get-in by-n [2 :fields "Priority"])) "issue 2 is on the board with the priority")
    (is (= "In progress" (get-in by-n [2 :fields "Status"])) "and in progress, as work already begun")
    (is (= {} (get-in by-n [1 :fields])) "the issue with the matching title is left alone")
    (is (= {2 "2-fix-the-footer"} (fake/linked-branches world)) "the branch is the named issue's")))


(deftest an-issue-number-the-repository-lacks-stops-before-anything-is-made
  (let [world  (fake/new-world {})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--issue" "99")]
    (is (= :start/issue-not-found (get-in result [::steps/problem :problem])) "the missing issue is the problem")
    (is (str/includes? (said result) "Issue #99 not found in o/r") "the answer names the number and the repository")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest a-label-the-repository-lacks-is-created-once-and-put-on-the-issue
  (let [world  (fake/new-world {:labels ["bug"]})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--labels" "bug,design")]
    (is (= :ok (::steps/status result)) "the start succeeds")
    (is (= ["design"] (fake/created-labels world)) "only the missing label is created, and once")
    (is (= 1 (fake/label-reads world)) "the repository's labels are read once, for the issue and its edit")
    (is (= ["bug" "design"] (:labels (first (fake/issues world)))) "both labels are on the issue")))


(deftest a-label-that-cannot-be-created-stops-before-anything-exists
  (let [world  (fake/new-world {:uncreatable ["design"]})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--labels" "design")]
    (is (= :start/label-not-created (get-in result [::steps/problem :problem])) "the label is the problem")
    (is (str/includes? (said result) "'design' does not exist in o/r") "the answer names the label and the repository")
    (is (nothing-exists? world) "no issue, card or branch exists")))


(deftest the-size-is-mirrored-as-a-size-label-and-an-old-size-label-goes
  (let [fresh   (fake/new-world {})
        _ (kit-start fresh "--title" "Add landing page" "--priority" "High" "--size" "M")
        resized (fake/new-world {:issues [{:title "Add landing page" :labels ["size:S" "bug"] :on-board true}]
                                 :labels ["bug"]})
        _ (kit-start resized "--title" "Add landing page" "--priority" "High" "--size" "M" "--issue" "1")]
    (is (= ["size:M"] (:labels (first (fake/issues fresh)))) "a new task carries the label of its size")
    (is (= ["bug" "size:M"] (sort (:labels (first (fake/issues resized)))))
        "a task resized from S to M carries size:M, not size:S, and keeps its other labels")))


(deftest a-size-label-that-cannot-be-set-gives-a-warning-and-the-start-goes-on
  (let [world  (fake/new-world {:refuse-size-label true})
        result (kit-start world "--title" "Add landing page" "--priority" "High" "--size" "M")]
    (is (= :ok (::steps/status result)) "the start goes on: the board shows the size")
    (is (some #(str/includes? % "could not set the label size:M on #1") (:warnings result))
        "and the warning names the label and the issue")
    (is (= "1-add-landing-page" (:branch result)) "the branch is still made")))


(deftest the-graphql-budget-is-noted-before-the-work-so-its-price-can-be-printed
  (let [world  (fake/new-world {:graphql 4990})
        result (kit-start world "--title" "Add landing page" "--priority" "High")]
    (is (= 4990 (:graphql-before result)) "the points left before the work are kept for start! to print the price")))


(deftest a-merged-task-without-its-retrospective-stops-the-start-unless-it-is-skipped
  (let [missing {:exit 1 :out "#5 is merged and has no retrospective" :err ""}
        blocked (fake/new-world {:retro-missing missing})
        refused (kit-start blocked "--title" "Add landing page" "--priority" "High")
        skipped (fake/new-world {:retro-missing missing})
        went-on (kit-start skipped "--title" "Add landing page" "--priority" "High" "--skip-retro")
        unasked (fake/new-world {:retro-missing {:exit 2 :out "" :err "no network"}})
        warned  (kit-start unasked "--title" "Add landing page" "--priority" "High")]
    (is (= :start/retro-missing (get-in refused [::steps/problem :problem])) "the missing retrospective is the problem")
    (is (str/includes? (said refused) "#5 is merged and has no retrospective") "the answer names the task")
    (is (nothing-exists? blocked) "no issue, card or branch exists")
    (is (= :ok (::steps/status went-on)) "with --skip-retro the start goes on")
    (is (= 1 (count (fake/issues skipped))) "and files the issue")
    (is (= :ok (::steps/status warned)) "when GitHub cannot be asked, the start goes on")
    (is (some #(str/includes? % "no network") (:warnings warned)) "and warns, with the reason")))


(deftest skip-branch-makes-no-branch-and-no-checkout-makes-one-without-checking-it-out
  (let [skipping (fake/new-world {})
        skipped  (kit-start skipping "--title" "Add landing page" "--priority" "High" "--skip-branch")
        keeping  (fake/new-world {})
        kept     (kit-start keeping "--title" "Add landing page" "--priority" "High" "--no-checkout")]
    (is (= :ok (::steps/status skipped)) "--skip-branch starts")
    (is (str/blank? (:branch skipped)) "and the answer has no branch")
    (is (empty? (fake/linked-branches skipping)) "none is linked")
    (is (= "main" (fake/checked-out skipping)) "and the working tree is where it was")
    (is (= "1-add-landing-page" (:branch kept)) "--no-checkout answers with the branch it made")
    (is (= {1 "1-add-landing-page"} (fake/linked-branches keeping)) "it is linked to the issue")
    (is (= "main" (fake/checked-out keeping)) "but the working tree is where it was")))


(deftest a-call-the-story-did-not-substitute-fails-it-naming-the-call
  (let [world (fake/new-world {})]
    (is (thrown-with-msg? AssertionError
                          #"the story reached the real world: shell/gh! api rate_limit"
                          (fake/with-github world #(shell/gh! "api" "rate_limit")))
        "a primitive called directly is refused, with its arguments")
    (is (thrown-with-msg? AssertionError
                          #"the story reached the real world: shell/run bb bin/kit retro --missing --repo o/r"
                          (with-redefs [fake/start-substitutes (fn [_world] {})]
                            (kit-start world "--title" "Add landing page" "--priority" "High")))
        "a command whose own call lacks its substitute stops at that call and does not touch GitHub")))
