(ns dev-kit.flow.steps-test
  "How a recipe ends (#139): the steps done, or the refusal with its text and exit code."
  (:require
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.steps :as steps]
   [dev-kit.problem :as problem]))


(defn- refused
  [& fail-args]
  (steps/refusal (steps/run {}
                   :check (fn [_] (apply problem/fail! fail-args)))))


(deftest a-refusal-prints-error-and-exits-1
  (is (= {:text "Error: no branch" :exit 1} (refused :x/no-branch "no branch"))))


(deftest a-step-may-name-its-own-exit-code-and-a-plain-text
  (is (= {:text "no result yet" :exit 2} (refused :checks/no-result "no result yet" {:exit 2 :plain true})))
  (is (= {:text "Error: gh failed" :exit 4} (refused :x/gh "gh failed" {:exit 4}))))


(deftest the-run-stops-at-the-first-refusal-and-keeps-what-it-gathered
  (let [result (steps/run {:args []}
                 :read  (fn [_] {:branch "7-fix"})
                 :check (fn [facts] (problem/fail! :x/bad (str "bad " (:branch facts))))
                 :never (fn [_] {:never true}))]
    (is (= :fail (::steps/status result)))
    (is (= [:read] (::steps/done result)))
    (is (= :check (::steps/stopped-at result)))
    (is (= "7-fix" (:branch result)))
    (is (not (contains? result :never)))))
