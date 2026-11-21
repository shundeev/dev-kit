(ns dev-kit.config-test
  "What kit tells the owner about a kit.edn that is wrong (step 4 of #139): each problem says what is wrong and, as its
  own field, what to do."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.config :as config]
   [dev-kit.fake-project :as fake]))


(def ^:private good
  {:repo     "shundeev/site"
   :showcase "Site"
   :modules  #{}
   :base     "main"
   :owners   ["shundeev/owners"]
   :board    {:owner "u473t8" :number 3}})


(deftest a-good-kit-edn-has-no-problems
  (is (= [] (config/config-problems good))))


(deftest every-wrong-key-is-named-with-what-to-do
  (doseq [[what cfg expected]
          [["a repo that is no owner/name" (assoc good :repo "site") ":repo is \"site\", not \"owner/name\""]
           ["no showcase" (dissoc good :showcase) ":showcase is missing"]
           ["modules that are no set" (assoc good :modules :clojure) ":modules must be a set"]
           ["an unknown module" (assoc good :modules #{:ruby}) ":modules has :ruby"]
           ["no base" (dissoc good :base) ":base must name the base branch"]
           ["no owners" (assoc good :owners []) ":owners must list who reviews every change"]
           ["a board without a number" (assoc good :board {:owner "x"}) ":board must be {:owner \"login\" :number N}"]
           ["a partial agent" (assoc good :agent {:login "a"}) ":agent must be nil, or a map"]
           ["a user test without a command" (assoc good :user-test {:artifacts "out"}) ":user-test must be nil"]
           ["artifacts outside the project" (assoc good :user-test {:run "t" :artifacts "../x"})
            ":user-test :artifacts is \"../x\""]]]
    (let [[problem :as problems] (config/config-problems cfg)]
      (is (= 1 (count problems)) what)
      (is (str/includes? (:message problem) expected) what)
      (is (not (str/blank? (:fix problem))) (str what ": it says what to do")))))


(deftest a-kit-edn-that-cannot-be-read-says-where-and-how-to-fix-it
  (let [problem (fake/thrown #(config/parse-kit-edn "kit.edn" "{:repo \"a/b\"\n :x ]\n}\n"))]
    (is (= :config/unreadable (:problem problem)))
    (is (str/starts-with? (:message problem) "kit.edn line 2 cannot be read"))
    (is (= "fix it by hand" (:fix problem)))))


(deftest a-kit-edn-that-is-no-map-says-what-a-map-looks-like
  (let [problem (fake/thrown #(config/parse-kit-edn "kit.edn" "[1 2]"))]
    (is (= :config/not-a-map (:problem problem)))
    (is (str/includes? (:fix problem) ":repo \"owner/name\""))))


;; The user-level test in CI (the selftest's cases of #27, #139 step 8)


(defn- user-test-problems
  [user-test]
  (config/config-problems (assoc good :user-test user-test)))


(deftest an-artifacts-folder-that-upload-artifact-would-read-as-a-pattern-is-refused
  (doseq [artifacts ["../elsewhere" "/tmp/out" "." "*" "!x" "a b" "~/x" "./x"]]
    (let [[problem :as problems] (user-test-problems {:run "true" :artifacts artifacts})]
      (is (= 1 (count problems)) artifacts)
      (is (str/includes? (:message problem) ":user-test :artifacts") artifacts)
      (is (str/includes? (:fix problem) "inside the project") (str artifacts ": says where they belong"))))
  (is (= [] (user-test-problems {:run "true" :artifacts "out"})) "a plain folder passes"))


(deftest a-user-test-that-is-not-a-run-command-is-refused-naming-the-key
  (doseq [user-test ["npm test" {:run 5} {:run "  "} [1] {:artifacts "out"}]]
    (let [[problem :as problems] (user-test-problems user-test)]
      (is (= 1 (count problems)) (pr-str user-test))
      (is (str/includes? (:message problem) ":user-test must be nil, or {:run") (pr-str user-test))))
  (is (= [] (user-test-problems nil)) "none is fine")
  (is (= [] (user-test-problems {:run "mkdir -p out && echo visitor-ok" :artifacts "out"})) "a declared one passes"))
