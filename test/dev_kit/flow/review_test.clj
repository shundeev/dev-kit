(ns dev-kit.flow.review-test
  "kit review, told as the owner's stories (#139, ADR-0008): what the agent reads when it asks for the owner's review,
  and when it waits for it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.review :as review]
   [dev-kit.flow.steps :as steps]))


(defn- review-by
  [author state at & [body]]
  {:author author :state state :at at :body (or body "")})


(defn- thread
  [path line resolved? & comments]
  {:resolved? resolved?
   :path      path
   :line      line
   :comments  (mapv (fn [[author body]] {:author author :body body}) comments)})


(defn- kit-review
  "Runs kit review against the world. Returns what the owner reads (:printed), how it ended (:exit, :text of a refusal)
  and the pauses (:slept)."
  [world variables & args]
  (let [out     (java.io.StringWriter.)
        input   (review/read-input (vec args) (fn [variable default] (get variables variable default)))
        result  (binding [*out* out]
                  (fake/with-github world #(review/review input)))
        refusal (when (= :fail (::steps/status result))
                  (steps/refusal result))]
    {:printed (str/join "\n"
                        (concat (remove str/blank? (str/split-lines (str out)))
                                (when-not refusal
                                  (review/report-lines result))))
     :exit    (get refusal :exit 0)
     :text    (:text refusal)
     :slept   (fake/slept world)}))


(defn- the-pull-request
  [pr & {:as options}]
  (fake/new-world (merge {:current-branch "7-fix"
                          :prs [(merge {:branch "7-fix" :head "aaa1111" :reviews [] :threads []} pr)]}
                         options)))


(def ^:private owner {"GHWF_HUMAN" "the-owner"})


(defn- becomes
  "An event of the script: the pull request gets these fields, its reviews and threads added to."
  [{:keys [reviews threads state]}]
  (fn [w]
    (-> w
        (update-in [:prs 1 :reviews] into reviews)
        (update-in [:prs 1 :threads] into threads)
        (cond-> state (assoc-in [:prs 1 :state] state)))))


(deftest without-wait-it-prints-the-owners-latest-review-and-the-unresolved-threads
  (let [world  (the-pull-request
                {:reviews [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T10:00:00Z")
                           (review-by "the-agent" "COMMENTED" "2026-11-18T10:30:00Z" "replied")
                           (review-by "the-owner" "COMMENTED" "2026-11-18T11:00:00Z" "Two things left.")]
                 :threads [(thread "src/a.clj" 12 false ["the-owner" "Why this?"] ["the-agent" "Because\nof that."])
                           (thread "src/b.clj" 3 true ["the-owner" "Done, thanks"])
                           (thread "src/c.clj" nil false ["the-owner" "An old line"])]})
        result (kit-review world owner)]
    (is (= 0 (:exit result)))
    (is (= (str "Latest review by the-owner: COMMENTED at 2026-11-18T11:00:00Z.\n"
                "Two things left.\n"
                "2 unresolved review threads:\n"
                "src/a.clj:12\n"
                "  the-owner: Why this?\n"
                "  the-agent: Because\n    of that.\n"
                "src/c.clj\n"
                "  the-owner: An old line")
           (:printed result))
        "the latest review is the owner's, the resolved thread is left out")
    (is (= [] (:slept result)) "it did not wait")))


(deftest with-no-review-by-the-owner-it-says-so
  (let [result (kit-review (the-pull-request {:reviews [(review-by "someone" "APPROVED" "2026-11-18T10:00:00Z")]})
                           owner)]
    (is (= 0 (:exit result)))
    (is (= "No review by the-owner on https://github.com/o/r/pull/1 yet.\nNo unresolved review threads."
           (:printed result)))))


(deftest a-review-by-the-owner-after-the-hand-back-ends-the-wait-and-prints-its-open-threads
  (let [world  (the-pull-request
                {:committed "2026-11-18T10:30:00Z"
                 :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T10:00:00Z" "old")]
                 :threads   [(thread "src/old.clj" 1 true ["the-owner" "fixed"])]}
                :script
                [fake/nothing-happens
                 (becomes {:reviews [(review-by "the-owner" "COMMENTED" "2026-11-18T12:00:00Z" "Look here")]
                           :threads [(thread "src/new.clj" 5 false ["the-owner" "Rename"])]})])
        result (kit-review world (assoc owner "GHWF_MERGE_POLL" "20") "--wait")]
    (is (= 0 (:exit result)))
    (is (= (str "Waiting for a review by the-owner on https://github.com/o/r/pull/1 since 2026-11-18T10:30:00Z.\n"
                "Latest review by the-owner: COMMENTED at 2026-11-18T12:00:00Z.\n"
                "Look here\n"
                "1 unresolved review thread:\n"
                "src/new.clj:5\n"
                "  the-owner: Rename")
           (:printed result)))
    (is (= [20 20] (:slept result)) "it looked every poll period")))


(deftest a-review-between-the-push-and-the-start-is-reported-at-once
  (let [world  (the-pull-request
                {:committed "2026-11-18T10:00:00Z"
                 :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T11:00:00Z" "Two things.")]
                 :threads   [(thread "src/a.clj" 2 false ["the-owner" "Why?"])]})
        result (kit-review world owner "--wait")]
    (is (= 0 (:exit result)))
    (is (= (str "Latest review by the-owner: CHANGES_REQUESTED at 2026-11-18T11:00:00Z.\n"
                "Two things.\n"
                "1 unresolved review thread:\n"
                "src/a.clj:2\n"
                "  the-owner: Why?")
           (:printed result))
        "no waiting line: nothing is awaited")
    (is (= [] (:slept result)) "it did not wait")))


(deftest an-owner-that-had-not-reviewed-yet-ends-the-wait-with-their-first-review
  (let [world  (the-pull-request {}
                                 :script
                                 [(becomes {:reviews [(review-by "the-owner" "APPROVED" "2026-11-18T12:00:00Z")]})])
        result (kit-review world owner "--wait")]
    (is (= 0 (:exit result)))
    (is (str/starts-with? (:printed result) "Waiting for a review by the-owner on https://github.com/o/r/pull/1.\n"))
    (is (str/includes? (:printed result) "Latest review by the-owner: APPROVED"))))


(deftest the-agents-replies-and-other-peoples-reviews-do-not-end-the-wait
  (let [world  (the-pull-request
                {:committed "2026-11-18T09:00:00Z"
                 :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T10:00:00Z")
                             (review-by "the-agent" "COMMENTED" "2026-11-18T10:01:00Z")]}
                :script
                [(becomes {:reviews [(review-by "the-agent" "COMMENTED" "2026-11-18T10:05:00Z")]})
                 (becomes {:reviews [(review-by "a-colleague" "APPROVED" "2026-11-18T10:06:00Z")]})
                 (becomes {:reviews [(review-by "the-owner" "APPROVED" "2026-11-18T10:07:00Z")]})])
        result (kit-review world owner "--wait")]
    (is (= 0 (:exit result)))
    (is (= 3 (count (:slept result))) "it waited through both, and ended on the owner's")
    (is (str/includes? (:printed result) "Latest review by the-owner: APPROVED at 2026-11-18T10:07:00Z."))))


(deftest the-agents-reply-after-the-owners-review-makes-that-review-old
  (let [world  (the-pull-request
                {:committed "2026-11-18T10:00:00Z"
                 :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T11:00:00Z")
                             (review-by "the-agent" "COMMENTED" "2026-11-18T11:05:00Z")]}
                :script
                [(becomes {:reviews [(review-by "the-owner" "APPROVED" "2026-11-18T12:00:00Z")]})])
        result (kit-review world owner "--wait")]
    (is (= [10] (:slept result)) "the owner's review was answered, so it waited for the next")
    (is (str/includes? (:printed result) "Latest review by the-owner: APPROVED at 2026-11-18T12:00:00Z."))))


(deftest a-newer-push-makes-an-older-review-old
  (let [world  (the-pull-request
                {:committed "2026-11-18T12:00:00Z"
                 :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T11:00:00Z")]}
                :script
                [(becomes {:reviews [(review-by "the-owner" "APPROVED" "2026-11-18T13:00:00Z")]})])
        result (kit-review world owner "--wait")]
    (is (= [10] (:slept result)))
    (is (str/includes? (:printed result) "Latest review by the-owner: APPROVED at 2026-11-18T13:00:00Z."))))


(deftest a-review-older-than-the-hand-back-does-not-end-the-wait
  (let [world (the-pull-request
               {:committed "2026-11-18T10:30:00Z"
                :reviews   [(review-by "the-owner" "CHANGES_REQUESTED" "2026-11-18T10:00:00Z")]}
               :script
               [(becomes {:reviews [(review-by "the-owner" "COMMENTED" "2026-11-18T10:15:00Z")]})])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"script ran out" (kit-review world owner "--wait"))
        "the earlier review is not news: the wait goes on until GitHub has nothing planned")))


(deftest logins-are-compared-without-regard-to-case
  (let [world  (the-pull-request
                {:committed "2026-11-18T10:00:00Z"
                 :reviews   [(review-by "the-owner" "APPROVED" "2026-11-18T11:00:00Z")]}
                :viewer
                "The-Agent")
        result (kit-review world {"GHWF_HUMAN" "The-Owner"} "--wait")]
    (is (= [] (:slept result)))
    (is (str/includes? (:printed result) "Latest review by The-Owner: APPROVED"))))


(deftest an-owner-who-runs-kit-themself-does-not-hand-back-by-reviewing
  (let [world  (the-pull-request
                {:committed "2026-11-18T10:00:00Z"
                 :reviews   [(review-by "the-owner" "APPROVED" "2026-11-18T11:00:00Z")]}
                :viewer
                "the-owner")
        result (kit-review world owner "--wait")]
    (is (= [] (:slept result)) "their own review is the news, not a reply to it")))


(deftest the-pull-request-merging-or-closing-while-waiting-ends-it-with-exit-1
  (doseq [state ["MERGED" "CLOSED"]]
    (let [world  (the-pull-request {} :script [fake/nothing-happens (fake/pr-becomes 1 {:state state})])
          result (kit-review world owner "--wait")]
      (is (= 1 (:exit result)) state)
      (is (= (format "Error: The pull request is %s, so no review is coming: https://github.com/o/r/pull/1"
                     (str/lower-case state))
             (:text result))
          state))))


(deftest a-pull-request-already-merged-is-not-waited-for
  (let [result (kit-review (the-pull-request {:state "MERGED"}) owner "--wait")]
    (is (= 1 (:exit result)))
    (is (= [] (:slept result)))))


(deftest without-the-owners-login-it-refuses-with-the-fix
  (let [result (kit-review (the-pull-request {}) {} "--wait")]
    (is (= 1 (:exit result)))
    (is (= (str "Error: GHWF_HUMAN is not set, so the owner's reviews cannot be told from anyone else's. "
                "Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login.")
           (:text result)))))


(deftest github-that-cannot-be-read-ends-it-with-gh's-message
  (let [result (kit-review (the-pull-request {:reviews-unreadable "HTTP 502"}) owner)]
    (is (= 2 (:exit result)))
    (is (str/starts-with? (:text result) "gh api graphql failed: HTTP 502"))))


(deftest it-is-run-from-the-issue-branch-or-names-the-pull-request
  (let [world (the-pull-request {} :current-branch "main")]
    (is (= "Error: Run from the issue branch, not 'main', or name the pull request: kit review --pr <number>"
           (:text (kit-review world owner))))
    (is (= 0 (:exit (kit-review world owner "--pr" "1"))))))
