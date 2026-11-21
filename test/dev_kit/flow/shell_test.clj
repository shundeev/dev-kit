(ns dev-kit.flow.shell-test
  "What a failed git or gh call does to a flow command (step 4 of #139): the step stops with the command's own exit code
  and says what to do; nothing here ends the process."
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.flow.shell :as shell]))


(defn- refusal
  "What a call that is expected to stop throws: its problem."
  [call]
  (try (call) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest a-failed-git-push-stops-the-step-with-gits-own-code
  (with-redefs [shell/run-on-terminal (constantly 128)]
    (let [problem (refusal #(shell/run-on-terminal! "git" "push" "-u" "origin" "1-add-page"))]
      (is (= 128 (:exit problem)) "git's code goes to the person's shell")
      (is (= "git push failed with exit code 128" (:message problem)))
      (is (string? (:fix problem)) "and the refusal says what to do")))
  (with-redefs [shell/run-on-terminal (constantly 0)]
    (is (nil? (shell/run-on-terminal! "git" "push")) "a call that went through stops nothing")))


(deftest a-failed-gh-call-keeps-gh-s-code-and-its-own-words
  (let [gh-answers (fn [answer] (fn [& _] (delay answer)))]
    (testing "gh said something: it is the text, as it is"
      (with-redefs [p/process (gh-answers {:exit 4 :out "" :err "HTTP 502: Bad Gateway\n"})]
        (let [problem (refusal #(shell/gh! "pr" "list"))]
          (is (= {:exit 4 :message "HTTP 502: Bad Gateway"} (select-keys problem [:exit :message])))
          (is (string? (:fix problem))))))
    (testing "gh said nothing: the call and the code are named"
      (with-redefs [p/process (gh-answers {:exit 3 :out "" :err ""})]
        (let [problem (refusal #(shell/gh! "pr" "view" "5"))]
          (is (= 3 (:exit problem)))
          (is (= "gh pr view failed with exit code 3 and printed no message" (:message problem))))))
    (testing "a call that went through gives gh's trimmed output"
      (with-redefs [p/process (gh-answers {:exit 0 :out "https://x/1\n" :err ""})]
        (is (= "https://x/1" (shell/gh! "pr" "list")))))))


(deftest an-unknown-flag-is-refused-with-what-to-do
  (let [problem (refusal #(shell/parse-opts {"--pr" [:pr :value]} ["--bogus"]))]
    (is (= :flow/unknown-argument (:problem problem)))
    (is (= "Unknown argument: --bogus" (:message problem)))
    (is (string? (:fix problem)))))


;; what passes (#139, step 8)


(deftest only-a-github-error-that-passes-is-asked-again
  (doseq [[message passes?] [["gh: No server is currently available (HTTP 503)" true]
                             ["HTTP 502 Bad Gateway" true]
                             ["Post https://api.github.com: connection reset by peer" true]
                             ["error connecting to api.github.com\ncheck your internet connection" true]
                             ["HTTP 404: Not Found" false]
                             ["HTTP 401: Bad credentials" false]
                             ["" false]]]
    (is (= passes? (shell/passing-github-error? message)) message)))
