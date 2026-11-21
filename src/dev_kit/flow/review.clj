(ns dev-kit.flow.review
  "kit review: what the owner said on the pull request, and the review threads still open; with --wait, after the
  owner's next review (dev-kit#139, the pilot's retrospective). An agent's own replies are reviews to GitHub too
  (COMMENTED), so a wait that watched any review fired on them: only the owner's login counts."
  (:require
   [clojure.string :as str]
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.steps :as steps]
   [dev-kit.flow.wait :as wait]
   [dev-kit.flow.watched-pr :as watched-pr]
   [dev-kit.problem :as problem]))


(def review-usage
  "Usage: kit review [options]

Prints the owner's latest review of the pull request (its state, time and text) and every review thread that is still
unresolved: where it is, and each comment as author: text. The owner is GHWF_HUMAN, the person's own login on this
machine; reviews by anyone else, the agent's own replies included, are not theirs.

With --wait it ends once the owner has a review submitted after the agent last gave the pull request back: the later of
the head commit's date and the latest review by the account running kit (its thread replies are reviews too). A review
of the owner's already after that is reported at once, so one submitted before the command started is not missed; any
state counts (approved, changes requested, commented). It looks every poll period and prints as above. Run it as a
background command: the agent is woken when it exits. Nothing bounds the wait.

  exit 0  the review was printed (with --wait: a newer one came)
  exit 1  GHWF_HUMAN is not set, or the pull request merged or closed while waiting: no review is coming
  exit 2  gh could not be read (its message is printed)

Options:
  --pr <number>                 The pull request (default: the open one of the current branch)
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --wait                        Wait for a review of the owner's after the last hand-back
  --help                        Show this help

Environment: GHWF_HUMAN, the owner's login (kit init or kit identity set it); GHWF_MERGE_POLL, seconds between looks
(default 10).")


(def review-spec
  {"--pr" [:pr :value] "--repo" [:repo :value] "--wait" [:wait :flag] "--help" [:help :flag] "-h" [:help :flag]})


(defn read-input
  "The command line and the GHWF_* variables kit review reads, as one map; env is (fn [name default])."
  [args env]
  (let [o (shell/parse-opts review-spec args)]
    (if (:help o)
      o
      (assoc o
             :human        (env "GHWF_HUMAN" nil)
             :poll-text    (env "GHWF_MERGE_POLL" "10")
             :default-base (env "GHWF_DEFAULT_BASE" "main")))))


(defn- check-owner
  "Refuses a run that does not know the owner's login."
  [{:keys [human]}]
  (when (str/blank? human)
    (problem/fail!
     :review/no-owner
     "GHWF_HUMAN is not set, so the owner's reviews cannot be told from anyone else's. Fix: run 'kit init' (or 'kit identity') on this machine; it reads your own gh login.")))


(defn- same-login?
  "GitHub logins are case-insensitive."
  [a b]
  (and a b (= (str/lower-case a) (str/lower-case b))))


(defn- reviews-by
  [login reviews]
  (filter #(same-login? login (:author %)) reviews))


(defn- latest
  "The review submitted last (times are UTC, in one format, so they sort as text)."
  [reviews]
  (last (sort-by :at reviews)))


(defn- handed-back-at
  "When the agent last gave the pull request back: the later of the head commit's date and the latest review by the
  account running kit (its thread replies are COMMENTED reviews). Nil when neither exists. Where that account is the
  owner's own, its reviews are the owner's and say nothing about a hand-back."
  [{:keys [head-committed viewer reviews]} human]
  (->> [head-committed
        (when-not (same-login? viewer human)
          (:at (latest (reviews-by viewer reviews))))]
       (remove nil?)
       sort
       last))


(defn- awaited-review
  "The owner's latest review submitted after the hand-back, or nil."
  [seen human]
  (let [since (handed-back-at seen human)]
    (->> (reviews-by human (:reviews seen))
         (filter #(or (nil? since) (pos? (compare (:at %) since))))
         latest)))


(defn- end-if-over!
  "No review is coming from a pull request that is no longer open."
  [{:keys [pr]} {:keys [state]}]
  (when (not= "OPEN" state)
    (problem/fail! :review/not-open
                   (format "The pull request is %s, so no review is coming: %s" (str/lower-case state) pr))))


(def ^:private reviews-query
  "A pull request's state, the head commit's date, its reviews and review threads (PullRequest.reviews, .reviewThreads
  and .commits, docs.github.com/en/graphql/reference/pulls), and who is asking (viewer). A thread's `line` is null once its code changed: `originalLine` then
  tells where it was."
  "query($owner: String!, $name: String!, $number: Int!, $reviewsAfter: String, $threadsAfter: String) {
      viewer { login }
      repository(owner: $owner, name: $name) {
        pullRequest(number: $number) {
          state
          commits(last: 1) { nodes { commit { committedDate } } }
          reviews(first: 100, after: $reviewsAfter) {
            pageInfo { hasNextPage endCursor }
            nodes { author { login } state submittedAt body }
          }
          reviewThreads(first: 100, after: $threadsAfter) {
            pageInfo { hasNextPage endCursor }
            nodes { isResolved path line originalLine comments(first: 100) { nodes { author { login } body } } }
          }
        }
      }
    }")


(defn- pr-coordinates
  "owner, name and number of a pull request given as a URL, or as a number with its repository."
  [pr repo]
  (if-let [[_ owner repo-name number] (re-find #"github\.com/([^/]+)/([^/]+)/pull/(\d+)" (str pr))]
    [owner repo-name number]
    (conj (str/split repo #"/" 2) (str pr))))


(defn- review-of
  [{:keys [author state submittedAt body]}]
  {:author (:login author) :state state :at submittedAt :body body})


(defn- thread-of
  [{:keys [isResolved path line originalLine comments]}]
  {:resolved? isResolved
   :path      path
   :line      (or line originalLine)
   :comments  (mapv (fn [{:keys [author body]}]
                      {:author (:login author) :body body})
                    (:nodes comments))})


(defn- graphql-reviews
  "gh api graphql: the pull request's {:state :viewer (the login of whoever runs kit) :head-committed (the head commit's date)
  :reviews :threads}, every page of both lists, one query for a page. A review is
  {:author :state :at :body} (a pending one, not submitted, is left out), a thread {:resolved? :path :line :comments}
  with its comments {:author :body}. The cursors of a list that has ended stay, and ask for the page after its last."
  [pr repo fail]
  (let [[owner repo-name number] (pr-coordinates pr repo)]
    (loop [reviews-after nil
           threads-after nil
           result        {:reviews [] :threads []}]
      (let [response (apply github/gh-json
                            fail
                            "api"
                            "graphql"
                            "-f"
                            (str "query=" reviews-query)
                            "-f"
                            (str "owner=" owner)
                            "-f"
                            (str "name=" repo-name)
                            "-F"
                            (str "number=" number)
                            (concat (when reviews-after
                                      ["-f" (str "reviewsAfter=" reviews-after)])
                                    (when threads-after
                                      ["-f" (str "threadsAfter=" threads-after)])))
            request  (get-in response [:data :repository :pullRequest])]
        (when-not request
          (fail (github/gh-failure-text "api graphql"
                                        (str (or (some-> response :errors first :message) "no such pull request")))))
        (let [{:keys [reviews reviewThreads]} request
              result (->
                      result
                      (assoc :state  (:state request)
                             :viewer (get-in response [:data :viewer :login])
                             :head-committed (get-in request [:commits :nodes 0 :commit :committedDate]))
                      (update :reviews into (comp (remove #(nil? (:submittedAt %))) (map review-of)) (:nodes reviews))
                      (update :threads into (map thread-of) (:nodes reviewThreads)))]
          (if (or (get-in reviews [:pageInfo :hasNextPage]) (get-in reviewThreads [:pageInfo :hasNextPage]))
            (recur (get-in reviews [:pageInfo :endCursor]) (get-in reviewThreads [:pageInfo :endCursor]) result)
            result))))))


(defn- read-reviews
  "The step that reads the pull request's reviews and threads."
  [{:keys [pr repo]}]
  {:seen (graphql-reviews pr repo watched-pr/no-result)})


(defn- announce-wait
  "With --wait, and no review of the owner's after the hand-back yet: what is awaited."
  [{waiting? :wait :keys [human pr seen]}]
  (when (and waiting? (not (awaited-review seen human)))
    (let [since (handed-back-at seen human)
          from  (if since
                  (format " since %s" since)
                  "")]
      (println (format "Waiting for a review by %s on %s%s." human pr from)))
    {}))


(defn- wait-for-review
  "With --wait, looks every poll period until the owner has a review after the hand-back, or the pull request is no
  longer open; the first look is the one read-reviews made."
  [{waiting? :wait :keys [human poll] :as facts}]
  (when waiting?
    (loop [seen (:seen facts)]
      (if (awaited-review seen human)
        {:seen seen}
        (do (end-if-over! facts seen)
            (wait/sleep! poll)
            (recur (:seen (read-reviews facts))))))))


(defn- find-the-review
  "What is printed: the owner's latest review (the awaited one, after a wait) and the threads still open."
  [{:keys [human seen]}]
  {:review  (latest (reviews-by human (:reviews seen)))
   :threads (vec (remove :resolved? (:threads seen)))})


(defn report-lines
  "What kit review prints."
  [{:keys [human pr review threads]}]
  (concat
   (if review
     (cons (format "Latest review by %s: %s at %s." human (:state review) (:at review))
           (when-not (str/blank? (:body review))
             [(:body review)]))
     [(format "No review by %s on %s yet." human pr)])
   (if (empty? threads)
     ["No unresolved review threads."]
     (cons (format "%d unresolved review thread%s:" (count threads) (if (> (count threads) 1) "s" ""))
           (mapcat (fn [{:keys [path line comments]}]
                     (cons (if line
                             (format "%s:%s" path line)
                             path)
                           (map (fn [{:keys [author body]}]
                                  (format "  %s: %s" author (str/replace (str body) "\n" "\n    ")))
                                comments)))
            threads)))))


(defn review
  "kit review as a recipe; the result is data that review! prints."
  [input]
  (steps/run (assoc input :command "review")
    :check-owner     check-owner
    :read-repo       watched-pr/read-repo
    :read-poll       watched-pr/read-poll
    :read-branch     watched-pr/read-branch
    :check-pr-option watched-pr/check-pr-option
    :find-pr         watched-pr/find-pr
    :read-reviews    read-reviews
    :announce-wait   announce-wait
    :wait-for-review wait-for-review
    :find-the-review find-the-review))


(defn review!
  [_kit args]
  (let [input (read-input args shell/env)]
    (when (:help input)
      (println review-usage)
      (System/exit 0))
    (let [result (review input)]
      (when (= :fail (::steps/status result))
        (shell/exit-with! (steps/refusal result)))
      (run! println (report-lines result)))))
