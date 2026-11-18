(ns dev-kit.flow.github-test
  "The in-memory GitHub against the real one (#139): the same operations in the same maps, and a story's clock that
  plays the script of what happens while a command waits."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev-kit.flow.fake-github :as fake]
   [dev-kit.flow.github :as real-github]))


(deftest the-in-memory-github-answers-every-operation-the-real-one-has
  (is
   (= (update-vals real-github/command-operations (comp set keys)) fake/command-operation-names)
   "a story never meets an operation the fake lacks, nor the fake one the real GitHub lacks, and each sits in its
      command's map"))


(deftest no-operation-is-named-twice
  (let [names (mapcat keys (vals real-github/command-operations))]
    (is (= (count names) (count (set names)))
        "two commands' maps never define the same operation: the one merged last would hide the other")))


(deftest a-pause-runs-the-next-event-of-the-script
  (let [world (fake/new-world {:prs    [{:branch "7-fix" :head "abc"}]
                               :script [fake/nothing-happens (fake/pr-becomes 1 {:state "MERGED"})]})
        sleep (fake/clock world)
        look  #(:state (first (fake/prs world)))]
    (testing "before any pause, GitHub is as the story built it"
      (is (= "OPEN" (look))))
    (sleep 10)
    (is (= "OPEN" (look)) "nothing happens in the first pause")
    (sleep 10)
    (is (= "MERGED" (look)) "the pull request merged in the second")
    (is (= [10 10] (fake/slept world)) "the pauses are kept, in seconds")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"script ran out: the command paused 3 times, the script plans 2"
                          (sleep 10))
        "a pause the story did not plan is an error, as the fake gh's sequences are")))


(deftest a-pull-request-reads-as-the-real-one-does
  (let [world  (fake/new-world {:issues [{:title "Fix"}]
                                :prs    [{:branch "1-fix" :head "abc" :labels ["hold"] :owners ["u473t8"]}
                                         {:branch "9-old" :unreadable "HTTP 502"}]})
        github (fake/github world)
        fail   #(throw (ex-info % {}))]
    (is (= "https://github.com/o/r/pull/2" (github :open-pr-url "o/r" "1-fix" "open"))
        "pull requests are numbered after the issues")
    (is (nil? (github :open-pr-url "o/r" "1-fix" "merged")))
    (is (= {:state       "OPEN"
            :decision    nil
            :merge-state nil
            :head        "abc"
            :base        "main"
            :draft       false
            :hold        true
            :owners      ["u473t8"]
            :auto-merge  false}
           (github :read-pr "2" "o/r" fail)))
    (is (= (github :read-pr "2" "o/r" fail) (github :read-pr "https://github.com/o/r/pull/2" "o/r" fail))
        "by number or by URL")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"^gh pr view failed: HTTP 502\. Fix: run it by hand"
                          (github :read-pr "3" "o/r" fail))
        "a failed read goes to the step's fail with gh's message, as the real one")
    (is (= [] (github :required-checks "2" "o/r" fail)))))


(deftest a-command-keeps-its-own-things-in-the-world
  (is (= ["abc"] (:commits @(fake/new-world {:commits ["abc"]})))
      "an option new-world does not know goes into the world as it is, for the command's operations to read"))
