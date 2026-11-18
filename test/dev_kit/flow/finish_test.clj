(ns dev-kit.flow.finish-test
  "kit finish, told as the owner's stories (#139, ADR-0008). Each runs the command against an in-memory GitHub and
  says what exists afterwards, and what the owner read."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.steps :as steps]))


(def ^:private variables
  {"GHWF_OWNER"          "u473t8"
   "GHWF_PROJECT_NUMBER" "3"
   "GHWF_REVIEWERS"      "shundeev/owners"
   "GHWF_HUMAN"          "the-human"})


(defn- file-with
  "A file holding the text, gone when the run ends."
  [text]
  (let [path (str (fs/create-temp-file))]
    (spit path text)
    (fs/delete-on-exit path)
    path))


(defn- kit-finish
  "Runs kit finish with the command-line words against the world and the variables of the owner's machine. Returns the
  result with the warnings under :warnings and what was printed under :printed."
  [world env & args]
  (let [warnings (atom [])
        ctx      {:github       (fake/github world)
                  :warn         (fn [& msg] (swap! warnings conj (str/join " " msg)))
                  :kit          "bin/kit"
                  :resolve-repo (fn [explicit] (or explicit "o/r"))}
        input    (finish/read-input (vec args) (fn [variable default] (get env variable default)))
        result   (finish/finish ctx input)]
    (assoc result
           :warnings @warnings
           :printed  (when (= :ok (::steps/status result))
                       (with-out-str (finish/print-result result))))))


(defn- problem
  [result]
  (get-in result [::steps/problem :problem]))


(defn- said
  [result]
  (get-in result [::steps/problem :message] ""))


(defn- on-branch-1
  "A world with issue #1 on the board, and the work on its branch."
  [options]
  (fake/new-world
   (merge {:issues [{:title "Add landing page" :on-board true}] :current-branch "1-add-landing-page"} options)))


(defn- the-pr
  [world]
  (first (filter #(= "1-add-landing-page" (:branch %)) (fake/prs world))))


(defn- nothing-committed-or-pushed?
  [world before]
  (and (= before (fake/commits world)) (empty? (fake/remote-branches world))))


(deftest a-first-finish-opens-a-ready-pr-asks-the-owners-and-moves-the-card-to-in-review
  (let [body   (file-with "What changed.\nADR: none")
        world  (on-branch-1 {})
        result (kit-finish world variables "--add" "page.html" "--pr-body-file" body)
        pr     (the-pr world)]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (= ["feat(GH-1): add landing page"] (fake/commits world)) "the staged work is committed under the subject")
    (is (= #{"1-add-landing-page"} (fake/remote-branches world)) "and pushed")
    (is (= "Closes #1\n\nWhat changed.\nADR: none" (:body pr)) "the PR closes the issue")
    (is (false? (:draft pr)) "it opens ready, where main has no ready workflow")
    (is (= ["shundeev/owners"] (:owners pr)) "the owners are asked at once")
    (is (= ["the-human"] (:assignees pr)) "the person behind the agent is the assignee")
    (is (true? (:auto-merge pr)) "auto-merge is on")
    (is (= "In review" (get-in (first (fake/cards world)) [:fields "Status"])) "the card is In review")
    (is (= (format "Subject: feat(GH-1): add landing page\nPR URL: %s\nStatus: In review\n" (:url pr))
           (:printed result))
        "the owner reads the subject, the PR and the status")))


(deftest where-main-holds-the-ready-workflow-the-pr-opens-as-a-draft-and-nobody-is-asked
  (let [world  (on-branch-1 {:ready-workflow true})
        result (kit-finish world variables "--add" "page.html" "--pr-body-file" (file-with "Body"))
        pr     (the-pr world)]
    (is (true? (:draft pr)) "the PR is a draft")
    (is (empty? (:owners pr)) "no owner is asked: the workflow asks when the checks pass")
    (is (false? (boolean (:auto-merge pr))) "auto-merge stays off until the workflow marks it ready")
    (is (= "In review" (get-in (first (fake/cards world)) [:fields "Status"])) "the card is In review all the same")
    (is (str/includes? (:printed result) "Draft: the owners are asked once its required checks pass")
        "the owner reads that it is a draft")
    (is (not (str/includes? (:printed result) "Held")) "and no word of a hold")))


(deftest hold-opens-the-pr-as-a-draft-with-the-label-made-when-missing
  (let [world  (on-branch-1 {:ready-workflow true})
        result (kit-finish world variables "--hold" "--add" "page.html" "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (= ["hold"] (fake/created-labels world)) "the label hold is made")
    (is (= ["hold"] (:labels (the-pr world))) "the PR carries it")
    (is (true? (:draft (the-pr world))) "as a draft")
    (is (str/includes? (:printed result) "Held: when the earlier PR has merged, run kit release.")
        "the owner reads when to release it")))


(deftest hold-uses-the-label-that-exists-and-stops-when-it-cannot-be-made
  (let [had    (on-branch-1 {:ready-workflow true :labels ["hold"]})
        cannot (on-branch-1 {:ready-workflow true :uncreatable ["hold"]})
        args   ["--hold" "--add" "page.html" "--pr-body-file" (file-with "Body")]]
    (is (= :ok (::steps/status (apply kit-finish had variables args))) "an existing label is no obstacle")
    (is (empty? (fake/created-labels had)) "and none is made again")
    (let [result (apply kit-finish cannot variables args)]
      (is (= :finish/hold-label (problem result)) "a label that cannot be made stops the run")
      (is (str/includes? (said result) "Label 'hold' does not exist in o/r and could not be created") "and says so")
      (is (nothing-committed-or-pushed? cannot []) "before the commit"))))


(deftest hold-on-an-open-ready-pr-is-refused-and-nothing-is-committed-or-pushed
  (let [world  (on-branch-1 {:ready-workflow true :prs [{:branch "1-add-landing-page" :draft false}]})
        result (kit-finish world variables "--hold" "--add" "page.html")]
    (is (= :finish/hold-on-ready-pr (problem result)) "the finish stops")
    (is (str/includes? (said result) "is ready already: the workflow only reads drafts") "and says why")
    (is (str/includes? (said result) "gh pr ready https://github.com/o/r/pull/2 --undo") "and what to run first")
    (is (nothing-committed-or-pushed? world []) "nothing is committed or pushed")))


(deftest hold-on-a-base-without-the-ready-workflow-is-refused-and-nothing-is-committed
  (let [world  (on-branch-1 {})
        result (kit-finish world variables "--hold" "--add" "page.html" "--pr-body-file" (file-with "Body"))]
    (is (= :finish/hold-without-workflow (problem result)) "the finish stops")
    (is (str/includes? (said result) "run kit finish without --hold") "and says what to do")
    (is (nothing-committed-or-pushed? world []) "nothing is committed or pushed")
    (is (empty? (fake/prs world)) "and no PR is opened")))


(deftest hold-on-an-open-draft-labels-it-without-opening-another
  (let [world  (on-branch-1 {:ready-workflow true :prs [{:branch "1-add-landing-page" :draft true}]})
        result (kit-finish world variables "--hold" "--add" "page.html")]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (= 1 (count (fake/prs world))) "no second PR is opened")
    (is (= ["hold"] (:labels (the-pr world))) "the open draft carries the label")))


(deftest an-open-pr-is-not-opened-twice-and-its-own-state-decides-who-is-asked
  (let [ready-pr (on-branch-1 {:ready-workflow true :prs [{:branch "1-add-landing-page" :draft false}]})
        draft-pr (on-branch-1 {:prs [{:branch "1-add-landing-page" :draft true}]})
        ready    (kit-finish ready-pr variables "--add" "page.html")
        draft    (kit-finish draft-pr variables "--add" "page.html")]
    (is (= :ok (::steps/status ready)) "no body file is needed for an open PR")
    (is (= 1 (count (fake/prs ready-pr))) "the PR is not opened twice")
    (is (= ["shundeev/owners"] (:owners (the-pr ready-pr))) "a ready PR asks the owners, whatever main holds")
    (is (true? (:auto-merge (the-pr ready-pr))) "and gets auto-merge")
    (is (empty? (:owners (the-pr draft-pr))) "a draft asks nobody, even where main has no workflow")
    (is (false? (boolean (:auto-merge (the-pr draft-pr)))) "and gets no auto-merge")
    (is (str/includes? (:printed draft) "Draft: the owners are asked") "the owner reads that it is a draft")))


(deftest the-shape-rules-see-the-body-only-when-it-becomes-a-new-prs-body
  (let [new-pr  (on-branch-1 {})
        open-pr (on-branch-1 {:prs [{:branch "1-add-landing-page"}]})
        body    (file-with "Body")]
    (kit-finish new-pr variables "--add" "page.html" "--pr-body-file" body)
    (kit-finish open-pr variables "--add" "page.html" "--pr-body-file" body)
    (is (= [{:branch "1-add-landing-page" :title "feat(GH-1): add landing page" :body-file body}]
           (fake/shape-checks new-pr))
        "a PR to open is checked with its body")
    (is (= [{:branch "1-add-landing-page" :title "feat(GH-1): add landing page" :body-file nil}]
           (fake/shape-checks open-pr))
        "an open PR keeps its own body, which is not checked")))


(deftest what-is-wrong-with-the-inputs-stops-the-finish-before-anything-is-committed
  (let [body (file-with "Body")
        rows [{:story   "a missing PR body file"
               :world   {}
               :args    ["--pr-body-file" "/nonexistent/body.md"]
               :problem :finish/no-body-file
               :text    "PR body file '/nonexistent/body.md' not found"}
              {:story   "a missing commit message file"
               :world   {}
               :args    ["--pr-body-file" body "--commit-message-file" "/nonexistent/message.txt"]
               :problem :finish/no-message-file
               :text    "Commit message file '/nonexistent/message.txt' not found"}
              {:story   "no body file where the PR has to be opened"
               :world   {}
               :args    []
               :problem :finish/body-file-required
               :text    "--pr-body-file is required to open the pull request"}
              {:story   "a title or body CI would refuse"
               :world   {:shape-refusal "  FAIL: fill in ## Review"}
               :args    ["--pr-body-file" body]
               :problem :finish/shape
               :text    "would fail CI's shape check; nothing was committed or pushed:\n  FAIL: fill in ## Review"}
              {:story   "a detached head"
               :world   {:current-branch "HEAD"}
               :args    ["--pr-body-file" body]
               :problem :finish/detached-head
               :text    "Detached HEAD is not supported"}
              {:story   "the base branch"
               :world   {:current-branch "main"}
               :args    ["--pr-body-file" body]
               :problem :finish/on-base
               :text    "On the base branch 'main': run from the issue branch"}
              {:story   "a branch with no issue number"
               :world   {:current-branch "landing-page"}
               :args    ["--pr-body-file" body]
               :problem :finish/branch-without-number
               :text    "The branch 'landing-page' is not <number>-<slug>: open the issue with kit start"}
              {:story   "a GitHub that cannot say whether main holds the ready workflow"
               :world   {:workflow-unreadable "HTTP 502: Bad Gateway"}
               :args    ["--pr-body-file" body]
               :problem :finish/workflow-unreadable
               :text    "(HTTP 502: Bad Gateway). Nothing was committed or pushed: run it again when GitHub answers"}]]
    (doseq [{:keys [story world args text] expected :problem} rows
            :let [story-world (on-branch-1 (merge {:commits ["feat(GH-1): earlier"]} world))
                  result      (apply kit-finish story-world variables "--add" "page.html" args)]]
      (is (= expected (problem result)) story)
      (is (str/includes? (said result) text) (format "%s: the answer says it" story))
      (is (nothing-committed-or-pushed? story-world ["feat(GH-1): earlier"])
          (format "%s: nothing is committed or pushed" story))
      (is (empty? (fake/staged story-world)) (format "%s: nothing was staged by the run" story)))))


(deftest nothing-staged-is-refused-and-no-commit-pushes-the-commits-already-there
  (let [world  (on-branch-1 {})
        result (kit-finish world variables "--pr-body-file" (file-with "Body"))
        pushed (on-branch-1 {:commits ["feat(GH-1): add landing page"]})
        again  (kit-finish pushed variables "--no-commit" "--pr-body-file" (file-with "Body"))]
    (is (= :finish/nothing-staged (problem result)) "with nothing staged the finish stops")
    (is (str/includes? (said result) "Nothing staged to commit (use --add <path>, or --no-commit)") "and says how")
    (is (empty? (fake/remote-branches world)) "nothing is pushed")
    (is (= :ok (::steps/status again)) "--no-commit goes on")
    (is (= ["feat(GH-1): add landing page"] (fake/commits pushed)) "it commits nothing more")
    (is (= #{"1-add-landing-page"} (fake/remote-branches pushed)) "it pushes what is on the branch")
    (is (some? (the-pr pushed)) "and opens the PR")))


(deftest the-type-comes-from-the-branchs-first-commit-that-is-not-docs
  (let [rows [["no commits" [] "feat"]
              ["a fix first" ["fix(GH-1): a" "docs(GH-1): b"] "fix"]
              ["docs first, then a refactor" ["docs(GH-1): b" "refactor(GH-1): c"] "refactor"]
              ["docs only" ["docs(GH-1): b"] "docs"]
              ["commits that follow no convention" ["wip" "Merge branch main"] "feat"]]]
    (doseq [[story commits expected] rows
            :let [world  (on-branch-1 {:commits commits :staged ["a"]})
                  result (kit-finish world variables "--summary" "do it" "--pr-body-file" (file-with "Body"))]]
      (is (= (format "%s(GH-1): do it" expected) (:subject result)) story))
    (is (= "docs(GH-1): do it"
           (:subject (kit-finish (on-branch-1 {:commits ["fix(GH-1): a"] :staged ["a"]})
                                 variables
                                 "--type"         "docs"
                                 "--summary"      "do it"
                                 "--pr-body-file" (file-with "Body"))))
        "--type wins over the commits")))


(deftest the-subject-is-the-summary-or-the-first-line-of-the-message-or-the-issue-title
  (let [message (file-with "fix(GH-1): from the message\n\nlonger text")
        body    (file-with "Body")
        subject #(:subject (apply kit-finish (on-branch-1 {:staged ["a"]}) variables "--pr-body-file" body %&))]
    (is (= "feat(GH-1): the summary" (subject "--summary" "the summary" "--commit-message-file" message))
        "the summary names the PR")
    (is (= "fix(GH-1): from the message" (subject "--commit-message-file" message))
        "the message's first line, verbatim")
    (is (= "feat(GH-1): add landing page" (subject)) "else the issue's title, its first letter in lower case")))


(deftest a-board-that-refuses-the-status-leaves-the-pr-open-and-names-the-command-to-set-it-alone
  (let [world  (on-branch-1 {:refuse-field "Status"})
        result (kit-finish world variables "--add" "page.html" "--pr-body-file" (file-with "Body"))]
    (is (= :finish/status-refused (problem result)) "the finish stops")
    (is (str/includes? (said result) "The pull request is open: https://github.com/o/r/pull/2.") "and names the PR")
    (is (str/includes? (said result) "But the board Status of #1 is not 'In review'") "and the status")
    (is (str/includes? (said result) "Set it alone: bb bin/kit board set 3 u473t8 https://github.com/o/r/issues/1")
        "and how to set it alone")
    (is (some? (the-pr world)) "the PR is open")))


(deftest without-an-assignee-variable-the-pr-opens-and-the-owner-reads-a-warning
  (let [world  (on-branch-1 {})
        result (kit-finish world
                           (dissoc variables "GHWF_HUMAN")
                           "--add" "page.html"
                           "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (empty? (:assignees (the-pr world))) "nobody is assigned")
    (is
     (=
      ["Warning: no GHWF_HUMAN, so the PR has no assignee. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login."]
      (:warnings result))
     "and the warning names the fix")))


(deftest a-repository-without-auto-merge-opens-the-pr-with-a-warning-that-names-the-fix
  (let [world  (on-branch-1 {:no-auto-merge true})
        result (kit-finish world variables "--add" "page.html" "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (some? (the-pr world)) "the PR is open")
    (is (str/starts-with? (first (:warnings result)) "Warning: auto-merge could not be turned on:")
        "the owner is warned")
    (is (str/includes? (second (:warnings result)) "Fix: the owner turns on Settings > General > 'Allow auto-merge'")
        "and told the fix")))


(deftest a-refused-review-request-is-a-warning-not-a-stop
  (let [world  (on-branch-1 {:refuse-pr-edit "HTTP 422: not a collaborator"})
        result (kit-finish world variables "--add" "page.html" "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish goes on")
    (is (= ["Warning: could not request the review or set the assignee: HTTP 422: not a collaborator"]
           (:warnings result))
        "with a warning")
    (is (true? (:auto-merge (the-pr world))) "and auto-merge is turned on")))


(deftest a-refused-hold-label-on-an-open-draft-stops-the-finish-with-the-fix
  (let [world  (on-branch-1 {:ready-workflow true
                             :prs [{:branch "1-add-landing-page" :draft true}]
                             :refuse-pr-edit "HTTP 403"})
        result (kit-finish world (dissoc variables "GHWF_HUMAN" "GHWF_REVIEWERS") "--hold" "--add" "page.html")]
    (is (= :finish/hold-label-refused (problem result)) "the finish stops")
    (is (str/includes? (said result) "could not get the label hold: HTTP 403. Fix: add the label on GitHub")
        "and says how to go on")))


(deftest an-issue-github-cannot-find-is-a-warning-and-the-status-is-not-set
  (let [world  (fake/new-world {:current-branch "7-add-landing-page"})
        result (kit-finish world
                           variables
                           "--summary"      "add landing page"
                           "--add"          "page.html"
                           "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (some #{"Warning: issue #7 not found, Status not set"} (:warnings result)) "the owner is warned")
    (is (empty? (fake/cards world)) "and no card is touched")
    (is (str/includes? (:printed result) "Status: In review") "the Status line is printed all the same")))


(deftest without-a-board-nothing-is-set-and-nothing-is-said
  (let [world  (on-branch-1 {})
        result (kit-finish world
                           (dissoc variables "GHWF_OWNER")
                           "--add" "page.html"
                           "--pr-body-file" (file-with "Body"))]
    (is (= :ok (::steps/status result)) "the finish succeeds")
    (is (empty? (:fields (first (fake/cards world)))) "the card's Status is left alone")
    (is (empty? (remove #(str/includes? % "GHWF_HUMAN") (:warnings result))) "and no warning is given")))


(deftest the-closes-line-is-added-unless-the-body-closes-that-very-issue
  (let [rows [["Closes #12" "Closes #12\nrest" false "the keyword for the issue is kept"]
              ["fixes in lower case" "fixes #12 and more" false "any case counts"]
              ["Resolved" "Resolved #12" false "resolved counts"]
              ["#12 is not #123" "Closes #123\nrest" true "another issue's number is not this one"]
              ["a mention is no close" "See #12" true "a bare mention is not a closing keyword"]
              ["no mention" "Body" true "a body without a keyword gets the line"]]]
    (doseq [[story text prefixed? why] rows
            :let [world (fake/new-world {:current-branch "12-landing"})]]
      (kit-finish world variables "--summary" "landing" "--add" "page.html" "--pr-body-file" (file-with text))
      (is (= (if prefixed?
               (format "Closes #12\n\n%s" text)
               text)
             (:body (first (fake/prs world))))
          (format "%s: %s" story why)))))
