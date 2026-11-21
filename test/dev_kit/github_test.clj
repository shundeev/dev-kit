(ns dev-kit.github-test
  "kit github, told as stories (#139, step 8): what the owner reads when the repository's main ruleset and the
  agent's access are brought to what dev-kit expects, and what is written to GitHub. GitHub is a map of what it
  holds; a story reads what it was asked to change."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.base :as base]
   [dev-kit.gate :as gate]
   [dev-kit.github :as github]
   [dev-kit.identity :as ident]
   [dev-kit.problem :as problem]))


(def ^:private repo "shundeev/kit-selftest")


(def ^:private agent-dir "/home/agent-gh")


(def ^:private config
  {:owners ["shundeev/owners"]
   :agent  {:login "kit-agent" :email "a@example.invalid" :gh-config-dir agent-dir}})


(defn- ruleset-as-dev-kit-wants
  "The main ruleset as kit github makes it for this repository, as GitHub shows it with its own fields added."
  [& [{:keys [check] :or {check "checks"}}]]
  (-> (github/expected-ruleset check [42] 1)
      (assoc :id 7 :current_user_can_bypass "always")
      (update :rules
              (partial mapv
                       #(cond-> %
                          (= "pull_request" (:type %)) (assoc-in [:parameters :automatic_copilot_code_review_enabled]
                                                        false))))))


(defn- github-holding
  "GitHub before the run, with what the story changes: :rulesets, :ruleset (the main one), :permissions by login,
  :teams, :invitations (to collaborate), :agent-invitations, :repo, and the faults :write-fails, :accept-fails,
  :unreachable."
  [held]
  (atom (merge {:repo        {"permissions" {"admin" true}}
                :rulesets    []
                :permissions {}
                :teams       []
                :invitations []
                :agent-invitations []
                :calls       []
                :writes      {}
                :agent-calls []}
               held)))


(defn- answer
  "What GitHub answers to `gh <args>`: {:exit :out :err}. The world records every call, and the bodies written."
  [world args stdin env]
  (let [line     (str/join " " args)
        [_ path] args
        method   (second (drop-while #(not= "-X" %) args))
        held     @world
        body     (fn [data] {:exit 0 :out (json/generate-string data)})
        failed   {:exit 1 :err "HTTP 422: Validation Failed"}]
    (swap! world update :calls conj line)
    (cond
      (:unreachable held)
      {:exit 1 :err "error connecting to api.github.com"}

      (and (:write-fails held) (#{"PUT" "POST"} method))
      failed

      (= path (str "repos/" repo)) (body (:repo held))
      (str/starts-with? path (str "repos/" repo "/rulesets?")) (body (:rulesets held))
      (and (= path (str "repos/" repo "/rulesets")) (= "POST" method))
      (do (swap! world assoc-in [:writes :posted] (json/parse-string stdin true)) (body {"id" 7}))
      (and (str/starts-with? path (str "repos/" repo "/rulesets/")) (= "PUT" method))
      (do (swap! world assoc-in [:writes :put] (json/parse-string stdin true)) (body {"id" 7}))
      (str/starts-with? path (str "repos/" repo "/rulesets/")) (body (:ruleset held))
      (str/ends-with? path "/permission")
      {:exit 0 :out (str (get-in held [:permissions (second (re-find #"collaborators/([^/]+)/" path))] "") "\n")}
      (= path (str "repos/" repo "/invitations")) (body (:invitations held))
      (= path (str "repos/" repo "/teams")) (body (:teams held))
      (str/starts-with? path (str "repos/" repo "/collaborators/")) {:exit 0 :out ""}
      (str/starts-with? path "orgs/") (if (str/includes? path "/repos/")
                                        {:exit 0 :out ""}
                                        (body {"id" 42}))
      (= path "user") (body {"login" "kit-owner" "id" 1})
      (= path "user/repository_invitations")
      (do (swap! world update :agent-calls conj [(get env "GH_CONFIG_DIR") (get env "GH_TOKEN")])
          (body (:agent-invitations held)))
      (str/starts-with? path "user/repository_invitations/")
      (do (swap! world update :agent-calls conj [(get env "GH_CONFIG_DIR") (get env "GH_TOKEN")])
          (if (:accept-fails held)
            {:exit 1 :err "HTTP 404: Not Found"}
            {:exit 0 :out ""}))
      :else {:exit 0 :out ""})))


(defn- kit-github
  "kit github against `world`: {:out what it printed :refusal what it refused with (the sentence), nil when it went
  through}. Options: :yes, :check, :typed (what the person at the terminal types, which makes it a terminal),
  :in-session, :logged-in (the logins by gh config dir), :locale (what `locale yesexpr` prints), :config."
  [world
   {:keys [yes check typed in-session logged-in locale config-over]
    :or   {logged-in {agent-dir "kit-agent"}}}]
  (let [refusal (atom nil)
        out     (with-redefs-fn {#'base/gh! (fn [& args]
                                              (let [{:keys [exit out err]} (answer world args nil {})]
                                                (if (zero? exit)
                                                  out
                                                  (throw (base/gh-failure args exit err)))))
                                 #'p/sh (fn [& args]
                                          (let [[opts cmd] (if (map? (first args))
                                                             [(first args) (rest args)]
                                                             [{} args])]
                                            (if (= "locale" (first cmd))
                                              {:exit 0 :out (or locale "")}
                                              (answer world (rest cmd) (:in opts) (:env opts)))))
                                 #'gate/gate-config (constantly (merge config config-over))
                                 #'ident/gh-login logged-in
                                 #'ident/interactive? (constantly (some? typed))
                                 #'github/agent-session? (constantly (boolean in-session))}
                  #(with-out-str
                     (binding [*in* (java.io.BufferedReader. (java.io.StringReader. (str typed "\n")))]
                       (try (github/github! (cond-> {:repo repo}
                                              yes   (assoc :yes true)
                                              check (assoc :check check)))
                            (catch clojure.lang.ExceptionInfo e
                              (reset! refusal (problem/refusal-text (ex-data e))))))))]
    {:out out :refusal @refusal :world @world}))


(defn- written
  "The POST and the PUT of a ruleset the run made."
  [{:keys [world]}]
  (:writes world))


(defn- wrote-nothing?
  [{:keys [world]}]
  (and (empty? (:writes world))
       (not-any? #(re-find #"-X (PUT|POST|PATCH)" %) (:calls world))))


(defn- rule-of
  [ruleset rule-type]
  (some #(when (= rule-type (:type %)) %) (:rules ruleset)))


(def ^:private rerun-world
  {:rulesets    [{"id" 7 "name" "main"}]
   :ruleset     (ruleset-as-dev-kit-wants)
   :permissions {"kit-agent" "write"}
   :teams       [{"slug" "owners" "permission" "push"}]})


(deftest the-managed-ruleset-asks-one-approval-from-code-owners-and-allows-squash-only
  (let [ruleset (json/parse-string (slurp (str (fs/path base/kit-dir "managed" "github" "ruleset.json"))) true)
        pull    (:parameters (rule-of ruleset "pull_request"))]
    (is (= [1 true true true ["squash"]]
           ((juxt :required_approving_review_count
                  :dismiss_stale_reviews_on_push     :require_code_owner_review
                  :required_review_thread_resolution :allowed_merge_methods)
            pull)))
    (is (= ["active" ["~DEFAULT_BRANCH"] ["deletion" "non_fast_forward" "pull_request" "required_status_checks"]]
           [(:enforcement ruleset) (get-in ruleset [:conditions :ref_name :include])
            (sort (map :type (:rules ruleset)))]))))


(deftest a-repository-without-the-main-ruleset-gets-it-and-the-access-the-agents-work-needs
  (let [ran   (kit-github (github-holding {:agent-invitations [{"id" 5 "repository" {"full_name" repo}}]}) {:yes true})
        {:keys [posted]} (written ran)
        calls (set (:calls (:world ran)))]
    (is (str/includes? (:out ran) "ruleset main: created"))
    (is (= 1 (:required_approving_review_count (:parameters (rule-of posted "pull_request")))) "one approving review")
    (is (= [["Team" 42 1]]
           (for [r (:required_reviewers (:parameters (rule-of posted "pull_request")))]
             [(get-in r [:reviewer :type]) (get-in r [:reviewer :id]) (:minimum_approvals r)]))
        "the owners team of kit.edn is the required reviewer")
    (is (calls "api orgs/shundeev/teams/owners") "and was looked up")
    (is (= "checks" (get-in (rule-of posted "required_status_checks") [:parameters :required_status_checks 0 :context]))
        "the project's check, checks, is required")
    (is (= [{:actor_type "User" :actor_id 1 :bypass_mode "pull_request"}] (:bypass_actors posted))
        "only the owner who runs it may bypass, in pull-request mode")
    (is (calls (str "api repos/" repo "/collaborators/kit-agent -X PUT -f permission=push"))
        "the agent is invited with Write")
    (is (calls (str "api orgs/shundeev/teams/owners/repos/" repo " -X PUT -f permission=push"))
        "the owners team gets Write")
    (is (calls "api user/repository_invitations/5 -X PATCH") "the invitation is accepted")
    (is (= #{[agent-dir nil]} (set (:agent-calls (:world ran)))) "as the agent, from its gh config")))


(deftest a-rerun-changes-nothing-whatever-fields-github-adds
  (let [ran (kit-github (github-holding rerun-world) {})]
    (is (str/includes? (:out ran) "nothing to change"))
    (is (wrote-nothing? ran))))


(deftest a-different-ruleset-is-shown-and-not-changed-without-yes
  (let [other (-> (ruleset-as-dev-kit-wants)
                  (update :rules
                          (partial mapv
                                   #(cond-> %
                                      (= "pull_request" (:type %))
                                      (assoc-in [:parameters :required_approving_review_count] 0))))
                  (update :rules conj {:type "required_signatures"}))
        held  (assoc rerun-world :ruleset other)
        shown (kit-github (github-holding held) {})]
    (is (str/includes? (:out shown) "required_approving_review_count") "names the difference")
    (is (str/includes? (:out shown) "required_signatures") "and the rule dev-kit lacks")
    (is (str/includes? (:refusal shown) "--yes") "says how to apply it")
    (is (wrote-nothing? shown))
    (let [applied (kit-github (github-holding held) {:yes true})
          put     (:put (written applied))]
      (is (= 1 (:required_approving_review_count (:parameters (rule-of put "pull_request"))))
          "brought back as dev-kit has it")
      (is (nil? (rule-of put "required_signatures")))
      (is (some #{(str "api repos/" repo "/rulesets/7 -X PUT --input -")} (:calls (:world applied))) "in place"))))


(deftest the-required-check-is-named-by-check-or-kept-from-the-ruleset-that-exists
  (is (= "selftest"
         (get-in (rule-of (:posted (written (kit-github (github-holding {}) {:yes true :check "selftest"})))
                          "required_status_checks")
                 [:parameters :required_status_checks 0 :context]))
      "--check names it")
  (let [held (assoc rerun-world :ruleset (ruleset-as-dev-kit-wants {:check "selftest"}))
        ran  (kit-github (github-holding held) {})]
    (is (str/includes? (:out ran) "nothing to change") "without --check, an existing ruleset keeps its check")))


(deftest what-kit-github-refuses-it-refuses-before-it-writes
  (doseq [[what held opts expected]
          [["without admin rights" {:repo {"permissions" {"admin" false}}} {:yes true} ["admin"]]
           ["in an agent session" {} {:yes true :in-session true} ["your own terminal"]]
           ["a ruleset under another name, which it does not double"
            {:rulesets [{"id" 8 "name" "protect main"}]} {:yes true} ["none named main"]]
           ["two rulesets named main" {:rulesets [{"id" 7 "name" "main"} {"id" 9 "name" "main"}]} {:yes true} ["7, 9"]]
           ["an agent with more than Write"
            (assoc rerun-world :permissions {"kit-agent" "admin"}) {:yes true} ["more than Write"]]
           ["a team of another organization" rerun-world {:yes true :config-over {:owners ["other-org/owners"]}}
            ["other-org"]]]]
    (let [ran (kit-github (github-holding held) opts)]
      (is (every? #(str/includes? (:refusal ran) %) expected) (str what ": " (:refusal ran)))
      (is (wrote-nothing? ran) what)))
  (testing "an agent session never calls GitHub"
    (is (empty? (:calls (:world (kit-github (github-holding {}) {:yes true :in-session true})))))))


(deftest a-reviewer-dev-kit-no-longer-sets-is-shown-before-it-goes
  (let [ran (kit-github (github-holding rerun-world) {:config-over {:owners ["kit-owner"]}})]
    (is (str/includes? (:out ran) "required_reviewers"))
    (is (wrote-nothing? ran))))


(deftest a-failed-write-names-the-step-and-says-a-rerun-is-safe
  (let [ran (kit-github (github-holding {:write-fails true}) {:yes true})]
    (is (str/includes? (:refusal ran) "safe to repeat"))
    (is (not (str/includes? (:refusal ran) "ExceptionInfo")) "no stack trace")))


(deftest a-failed-accept-is-no-success-and-names-where-to-accept
  (let [ran (kit-github (github-holding (assoc rerun-world
                                               :agent-invitations [{"id" 5 "repository" {"full_name" repo}}]
                                               :accept-fails      true))
                        {:yes true})]
    (is (str/includes? (:refusal ran) (str "github.com/" repo "/invitations")))))


(deftest a-logged-out-agent-keeps-its-pending-invitation-and-is-told-how-to-accept-it
  (let [ran   (kit-github (github-holding {:invitations       [{"id" 6 "invitee" {"login" "kit-agent"}}]
                                           :agent-invitations [{"id" 6 "repository" {"full_name" repo}}]
                                           :permissions       {"kit-agent" "none"}})
                          {:yes true :logged-in {}})
        calls (str/join "\n" (:calls (:world ran)))]
    (is (not (str/includes? calls "collaborators/kit-agent -X PUT")) "a pending invitation is not sent again")
    (is (str/includes? (:out ran) "invitation to accept: in this terminal"))
    (is (not (str/includes? calls "repository_invitations/6")) "and nothing is accepted as someone else")))


(deftest the-answer-at-the-terminal-is-read-as-the-system-reads-yes
  (let [typed-in (fn [locale typed] (kit-github (github-holding {}) {:typed typed :locale locale}))
        applied? #(some? (:posted (written %)))
        odd      (fn [locale want answers]
                   (vec (for [a answers :when (not= want (applied? (typed-in locale a)))] a)))
        russian  "^[+1yYДд]"]
    (is (= [] (odd nil true ["y" "Y" "yes" "Yeah"])) "y, Y, yes, Yeah apply, in any locale")
    (is (= [] (odd nil false ["у" "д" "да" "n" "x"])) "у, д, да, n, x apply nothing in a C locale")
    (is (= [] (odd russian true ["д" "Да" "+" "1" "y"])) "in a Russian locale д, Да, +, 1 and y apply")
    (is (= [] (odd russian false ["у" "н" "нет"])) "у, н, нет apply nothing there either")
    (let [no (typed-in nil "у")]
      (is (str/includes? (:out no) "«у»") "an answer it did not take is named")
      (is (str/includes? (:refusal no) "nothing was changed")))))
