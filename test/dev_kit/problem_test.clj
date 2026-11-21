(ns dev-kit.problem-test
  "What a refusal is (step 4 of #139): one shape that every edge reads, and a scan of the sources that holds the
  rules no story can reach: every exit code is in the one list, every stop names its problem, and in the namespaces
  already converted every stop says what to do."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.problem :as problem]
   [edamame.core :as edamame]))


(def converted
  "Namespaces where every problem/fail! carries a :fix. A namespace is added here when its stops get their advice."
  #{'dev-kit.flow.board 'dev-kit.flow.checks 'dev-kit.flow.finish 'dev-kit.flow.github 'dev-kit.flow.merge
    'dev-kit.flow.release 'dev-kit.flow.review 'dev-kit.flow.shell 'dev-kit.flow.start 'dev-kit.flow.steps
    'dev-kit.flow.wait 'dev-kit.flow.watched-pr})


(defn- source-forms
  "The forms of one source file, read without evaluating anything."
  [file]
  (edamame/parse-string-all (slurp (str file))
                            {:all true :auto-resolve (fn [_] 'any) :read-cond :allow :features #{:bb :clj}}))


(defn- sources
  "Every Clojure file under src, as {:ns the namespace :forms its forms}."
  []
  (for [file (sort (map str (fs/glob "src" "**.clj")))
        :let [forms (source-forms file)]]
    {:file  file
     :ns    (-> (first forms) second)
     :forms forms}))


(defn- all-forms [forms] (mapcat #(tree-seq coll? seq %) forms))


(defn- fail-calls
  [forms]
  (filter #(and (seq? %) (= 'problem/fail! (first %))) (all-forms forms)))


(defn- fail-options
  "The options map of a problem/fail! call, when it is written out in the call."
  [call]
  (let [options (nth call 3 nil)]
    (when (map? options) options)))


(deftest a-stop-is-one-shape-every-edge-reads
  (let [thrown (fn [& args]
                 (try (apply problem/fail! args)
                      (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "the problem, the text and every option come with it"
      (is (= {:problem :pr/none :message "No PR" :fix "open one" :exit 2 :plain true}
             (thrown :pr/none "No PR" {:fix "open one" :exit 2 :plain true}))))
    (testing "no options is a plain refusal"
      (is (= {:problem :pr/none :message "No PR"} (thrown :pr/none "No PR"))))
    (testing "the text is the exception's message"
      (is (= "No PR" (try (problem/fail! :pr/none "No PR") (catch clojure.lang.ExceptionInfo e (ex-message e))))))))


(deftest every-exit-code-in-the-sources-is-in-the-list
  (let [literals (for [{:keys [file forms]} (sources)
                       form (all-forms forms)
                       :let [codes (cond
                                     (map? form) (when (number? (:exit form))
                                                   [(:exit form)])
                                     (and (seq? form) (= 'System/exit (first form)) (number? (second form)))
                                     [(second form)])]
                       code codes]
                   [file code])
        unlisted (remove (comp problem/exit-codes second) literals)]
    (is (seq literals) "the scan reads the sources")
    (is (empty? unlisted) (str "exit codes missing from dev-kit.problem/exit-codes: " (pr-str unlisted)))))


(deftest every-stop-names-its-problem-in-the-call
  (let [calls   (for [{:keys [file forms]} (sources)
                      call (fail-calls forms)]
                  [file call])
        unnamed (remove (comp keyword? second second) calls)]
    (is (seq calls) "the scan finds the stops")
    (is (empty? unnamed)
        (str "problem/fail! needs a problem keyword written out: "
             (pr-str (map (fn [[file call]] [file (second call)]) unnamed))))))


(deftest every-stop-of-a-converted-namespace-says-what-to-do
  (let [lacking (for [{:keys [file forms] namespace-name :ns} (sources)
                      :when (converted namespace-name)
                      call  (fail-calls forms)
                      :when (not (contains? (fail-options call) :fix))]
                  [file (second call)])]
    (is (empty? lacking) (str "problem/fail! without :fix: " (pr-str lacking)))))
