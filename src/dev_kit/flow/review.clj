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


(defn read-input
  "The options of the command line and the GHWF_* variables kit review reads, as one map; env is (fn [name default])."
  [opts env]
  (assoc opts
         :human        (env "GHWF_HUMAN" nil)
         :poll-text    (env "GHWF_MERGE_POLL" "10")
         :default-base (env "GHWF_DEFAULT_BASE" "main")))


(defn- check-owner
  "Refuses a run that does not know the owner's login."
  [{:keys [human]}]
  (when (str/blank? human)
    (problem/fail! :review/no-owner
                   "GHWF_HUMAN is not set, so the owner's reviews cannot be told from anyone else's."
                   {:fix "run 'kit init' (or 'kit identity') on this machine; it reads your own gh login."})))


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
                   (format "The pull request is %s, so no review is coming: %s" (str/lower-case state) pr)
                   {:fix "open a new pull request with kit finish, or name another one: kit review --pr <number>"})))


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
          (fail (github/gh-failure-message "api graphql"
                                           (str (or (some-> response :errors first :message) "no such pull request")))
                github/gh-failure-fix))
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
  longer open; the first look is the one read-reviews made. A passing GitHub error is no answer this time: it asks
  again at the next look (wait/look!)."
  [{waiting? :wait :keys [human poll] :as facts}]
  (when waiting?
    (loop [seen   (:seen facts)
           errors 0]
      (if (awaited-review seen human)
        {:seen seen}
        (do (end-if-over! facts seen)
            (wait/sleep! poll)
            (let [look (wait/look! errors poll #(read-reviews facts))]
              (recur (or (get-in look [:answer :seen]) seen) (:errors look))))))))


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
  [opts]
  (let [result (steps/complete! (review (read-input opts shell/env)))]
    (run! println (report-lines result))))
