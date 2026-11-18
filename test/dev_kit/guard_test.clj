(ns dev-kit.guard-test
  "What the delivery guard reads in a command line before it judges it, told as stories. The cases come
  from the selftest's \"delivery guard\" section, which keeps them end to end until the guard's rules move
  here too (#139, step 6)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev-kit.guard.statements :as statements]
   [dev-kit.guard.tokenizer :as tokenizer]))


(defn- commands-read
  "The commands the guard will judge in a command line: each statement from its command word on,
  nested ones included."
  [line]
  (mapv #(mapv :text (statements/strip-head %)) (statements/expand (tokenizer/tokenize line))))


(deftest quoted-text-is-text-not-a-command
  (is (= [["git" "commit" "-m" "a && gh issue create -t y"]]
         (commands-read "git commit -m \"a && gh issue create -t y\""))
      "a commit message that mentions gh issue create creates no issue")
  (is (= [["gh" "pr" "create" "--head" "12-x" "--title" "a; gh issue create"]]
         (commands-read "gh pr create --head 12-x --title \"a; gh issue create\""))
      "a semicolon inside a title ends nothing"))


(deftest a-heredoc-body-is-text-but-what-follows-it-runs
  (is (= [["cat"]]
         (commands-read "cat <<'EOF' > b.md\ngh issue create -t y\nEOF"))
      "the body written into b.md is not read as commands, and the redirection is no word")
  (is (= [["cat"] ["gh" "issue" "create" "-t" "y"]]
         (commands-read "cat <<EOF\nx\nEOF\ngh issue create -t y"))
      "the line after the heredoc's end is a command of its own"))


(deftest a-command-inside-another-is-judged-on-its-own
  (doseq [[line how] [["bash -c \"gh issue create -t x\"" "inside bash -c"]
                      ["eval 'gh issue create -t x'" "inside eval"]
                      ["echo `gh issue create -t x`" "inside backticks"]
                      ["echo \"made: $(gh issue create -t x)\"" "inside $(…) within quotes"]]]
    (testing how
      (is (some #{["gh" "issue" "create" "-t" "x"]} (commands-read line))
          "the nested gh issue create is seen"))))


(deftest wrappers-and-shell-keywords-do-not-hide-the-command
  (is (= [["gh" "issue" "create" "-t" "x"]]
         (commands-read "sudo -u bob gh issue create -t x"))
      "sudo and its option with a value are stepped over")
  (is (some #{["gh" "issue" "create" "-t" "x"]}
            (commands-read "if true; then gh issue create -t x; fi"))
      "a command after then is still a command"))


(deftest statements-split-where-the-shell-splits-them
  (is (= [["git" "status"] ["sleep" "3"]]
         (commands-read "git status && sleep 3"))
      "the sleep after && is judged apart from git status"))
