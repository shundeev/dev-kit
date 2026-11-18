(ns dev-kit.flow.release-test
  "kit release, told as the owner's stories (#139, ADR-0008): what the pull request is afterwards, and what the owner
  read. A refusal changes nothing."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.release :as release]
   [dev-kit.flow.steps :as steps]))


(def ^:private green [{:name "lint" :bucket "pass" :link "l"} {:name "docs" :bucket "skipping" :link "d"}])


(defn- held-draft [& {:as more}] (merge {:branch "7-held" :draft true :labels ["hold"] :checks green} more))


(defn- kit-release
  "Runs kit release with the command-line words. Returns the result, the lines printed under :printed, the warnings
  under :warnings."
  [world & args]
  (let [printed  (atom [])
        warnings (atom [])
        ctx      {:github       (fake/github world)
                  :out          #(swap! printed conj %)
                  :warn         (fn [& msg] (swap! warnings conj (str/join " " msg)))
                  :resolve-repo (fn [explicit] (or explicit "o/r"))}]
    (assoc (release/release ctx (release/read-input (vec args))) :printed @printed :warnings @warnings)))


(defn- said [result] (get-in result [::steps/problem :message] ""))


(defn- the-pr [world] (first (fake/prs world)))


(deftest a-held-draft-with-green-checks-is-freed-marked-ready-and-set-to-merge
  (let [world  (fake/new-world {:current-branch "7-held" :prs [(held-draft)]})
        result (kit-release world)]
    (is (= :ok (::steps/status result)))
    (is (= {:draft false :labels [] :auto-merge true} (select-keys (the-pr world) [:draft :labels :auto-merge])))
    (is (= ["Hold removed." "Marked ready: the owners are asked for a review."] (:printed result)))
    (is (= "https://github.com/o/r/pull/1" (:url result)) "the answer names the PR, for the Released line")
    (is (empty? (:warnings result)))))


(deftest a-ready-pull-request-only-gets-auto-merge
  (let [world  (fake/new-world {:current-branch "7-ready" :prs [{:branch "7-ready" :checks green}]})
        result (kit-release world)]
    (is (= :ok (::steps/status result)))
    (is (= {:auto-merge true} (select-keys (the-pr world) [:draft :auto-merge])))
    (is (empty? (:printed result)) "no hold was removed and nothing was marked ready")))


(deftest where-the-repository-forbids-auto-merge-the-release-stands-with-a-warning
  (let [world  (fake/new-world {:current-branch "7-held"
                                :prs [(held-draft :refuse-auto-merge "Auto merge is not allowed for this repository")]})
        result (kit-release world)]
    (is (= :ok (::steps/status result)))
    (is (false? (:draft (the-pr world))) "the PR is ready")
    (is (nil? (:auto-merge (the-pr world))))
    (is (= 2 (count (:warnings result))))
    (is (= "Warning: auto-merge could not be turned on: Auto merge is not allowed for this repository"
           (first (:warnings result))))
    (is (str/includes? (second (:warnings result)) "Allow auto-merge"))))


(deftest a-required-check-that-is-not-green-refuses-naming-it-and-changes-nothing
  (let [world  (fake/new-world {:current-branch "7-held"
                                :prs [(held-draft :checks
                                                  [{:name "lint" :bucket "fail"} {:name "test" :bucket "pending"}
                                                   {:name "docs" :bucket "pass"}])]})
        before (the-pr world)
        result (kit-release world)]
    (is (= :fail (::steps/status result)))
    (is (= :check-checks-passed (::steps/stopped-at result)))
    (is (str/includes? (said result) "is not green: lint (fail), test (pending)."))
    (is (str/includes? (said result) "run kit release again"))
    (is (= before (the-pr world)) "the hold, the draft and auto-merge are as they were")))


(deftest no-required-check-reported-is-refused
  (let [world  (fake/new-world {:current-branch "7-held" :prs [(held-draft :checks [])]})
        result (kit-release world)]
    (is (= :fail (::steps/status result)))
    (is (str/starts-with? (said result) "GitHub reports no required check on https://github.com/o/r/pull/1, so none"))
    (is (str/includes? (said result) "gh pr ready https://github.com/o/r/pull/1"))
    (is (= {:draft true :labels ["hold"]} (select-keys (the-pr world) [:draft :labels])))))


(deftest unreadable-checks-stop-the-release-with-gh-s-words
  (let [world  (fake/new-world {:current-branch "7-held" :prs [(held-draft :checks-unreadable "HTTP 502")]})
        result (kit-release world)]
    (is (= :fail (::steps/status result)))
    (is (str/starts-with? (said result) "gh pr checks failed: HTTP 502"))
    (is (true? (:draft (the-pr world))))))


(deftest a-merged-pull-request-has-nothing-to-release
  (let [world  (fake/new-world {:current-branch "7-held" :prs [(held-draft :state "MERGED")]})
        result (kit-release world "--pr" "1")]
    (is (= :fail (::steps/status result)))
    (is (= "https://github.com/o/r/pull/1 is MERGED, not open: nothing to release." (said result)))
    (is (= ["hold"] (:labels (the-pr world))))))


(deftest a-branch-without-an-open-pull-request-points-to-pr
  (let [world  (fake/new-world {:current-branch "7-held" :prs [(held-draft :state "MERGED")]})
        result (kit-release world)]
    (is (= :fail (::steps/status result)))
    (is (str/starts-with? (said result) "No open pull request for branch '7-held' in o/r."))
    (is (str/ends-with? (said result) "or name it with --pr <number>"))
    (is (= ["hold"] (:labels (the-pr world))) "nothing was changed")))


(deftest pr-names-the-pull-request-by-number-or-by-url-from-any-branch
  (doseq [named ["1" "https://github.com/o/r/pull/1"]]
    (let [world  (fake/new-world {:current-branch "main" :prs [(held-draft)]})
          result (kit-release world "--pr" named)]
      (is (= :ok (::steps/status result)) named)
      (is (= {:draft false :labels [] :auto-merge true} (select-keys (the-pr world) [:draft :labels :auto-merge]))
          named))))


(deftest the-command-line-names-the-repository-and-asks-for-help
  (is (= "owner/name" (:repo (release/read-input ["--repo" "owner/name"]))))
  (is (true? (:help (release/read-input ["--help"]))))
  (is (true? (:help (release/read-input ["-h"])))))
