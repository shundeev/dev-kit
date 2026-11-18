(ns dev-kit.guard-rules-test
  "What the delivery guard decides on a command line, told as stories over tables of commands. The guard reads the
  command and a few facts (the shell, the environment, which branch a folder is on, what review a pull request
  holds); the tests give it those facts as data and fakes, so no repository, no gh and no process is needed
  (#139, step 6). The selftest keeps what needs a real run: gh in the push's folder, the hook's wiring, fail-closed."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [dev-kit.guard.rules :as rules]
   [dev-kit.guard.statements :as statements]
   [dev-kit.guard.tokenizer :as tokenizer]))


(def issue-folder "/w/force")


(def main-folder "/w/force-main")


(def world
  "The facts of a session in the project's folder, which is on main; two repositories, one on an issue branch and
  one on main, and a home folder with a repository on an issue branch."
  {:shell    "bash"
   :env      {"HOME" "/h" "S_ENV" issue-folder}
   :cwd      "."
   :base     "main"
   :repo     "<owner/repo>"
   :branches {"."          "main"
              issue-folder "42-some-task"
              main-folder  "main"
              "/h/repo"    "42-some-task"}
   :review   nil})


(defn- judged
  "[decision, reads]: the guard's decision on the line, with the facts of world and changes; reads lists what it
  asked of git and GitHub: [:branch-of dir] and [:review-of dir branch]."
  [line changes]
  (let [{:keys [branches review] :as facts} (merge world changes)
        reads    (atom [])
        decision (rules/judge
                  (rules/loop-marked (statements/expand (tokenizer/tokenize line)))
                  (assoc facts
                         :branch-of (fn [dir] (swap! reads conj [:branch-of dir]) (get branches dir))
                         :review-of (fn [dir branch]
                                      (swap! reads conj [:review-of dir branch])
                                      (if (fn? review)
                                        (review dir branch)
                                        review))))]
    [decision @reads]))


(defn- decision [line changes] (first (judged line changes)))


(defn- check-rows
  "Each row is [line expected] or [line changes expected]. expected is nil (the command runs) or
  [decision rule & words the reason holds]."
  [rows]
  (doseq [[line & more] rows
          :let [[changes expected] (if (= 2 (count more))
                                     more
                                     [{} (first more)])
                d (decision line changes)]]
    (testing line
      (if (nil? expected)
        (is (nil? d) "the command runs")
        (let [[kind rule & words] expected]
          (is (= [kind rule] [(:decision d) (:rule d)]))
          (doseq [w words]
            (is (str/includes? (str (:reason d)) w) (str "the reason says " w))))))))


(def zsh {:shell "zsh"})


(def home-set {:env {"HOME" "/h" "S_ENV" issue-folder}})


(deftest the-flow-is-not-bypassed-by-raw-gh
  (check-rows
   [["gh issue create --title x" [:deny :gh-issue-create ".shared/bin/kit start"]]
    ["/usr/bin/gh issue create --title x" [:deny :gh-issue-create]]
    ["sudo -u bob gh issue create -t x" [:deny :gh-issue-create]]
    ["gh issue create --help" nil]
    ["gh issue list --limit 1" nil]
    ["gh pr create --fill" [:deny :pr-create-no-issue "HEAD is 'main'"]]
    ["gh pr create --head 12-landing --fill" nil]
    ["git status" nil]
    ["DELIVERY_GUARD=off gh issue create -t x" [:ask :guard-off]]
    ["echo \"DELIVERY_GUARD=off\"" [:ask :guard-off]]
    ["FOO=1 git status" nil]
    ["DELIVERY_GUARD=off sleep 5" [:ask :guard-off]]
    ["git push --force-with-lease origin main --help" nil]]))


(deftest a-forced-push-rewrites-only-an-issue-branch
  (check-rows
   [["git push --force-with-lease origin 42-some-task" nil]
    [(str "cd " issue-folder " && git push --force-with-lease") nil]
    [(str "git -C " issue-folder " push --force-with-lease origin") nil]
    ["git push origin main" nil]
    ["git push --force-with-lease origin main" [:deny :forced-push-branch "'main'" "<number>-<slug>"]]
    ["git push --force-with-lease origin HEAD:main" [:deny :forced-push-branch "'main'"]]
    ["git push --force-with-lease origin feature-x" [:deny :forced-push-branch "'feature-x'"]]
    ["git push --force-if-includes --force-with-lease origin feature-x" [:deny :forced-push-branch]]
    [(str "cd " main-folder " && git push --force-with-lease") [:deny :forced-push-branch "'main'"]]
    ["git push -f" [:deny :forced-push-branch "'main'"]]
    ["git push origin +main" [:deny :forced-push-branch "'main'"]]
    ["git push --force-with-lease origin refs/heads/main" [:deny :forced-push-branch "'main'"]]
    ["git push --force-with-lease origin refs/heads/*:refs/heads/*" [:deny :forced-push-branch "'*'"]]
    ["git push -f origin :" [:deny :forced-push-branch "the branches matching on both sides"]]
    ["git -c core.x=y push --force-with-lease origin main" [:deny :forced-push-branch]]
    ["git --no-pager push --force-with-lease origin main" [:deny :forced-push-branch]]
    ["bash -c \"git push -f origin main\"" [:deny :forced-push-branch]]
    ["git push -f origin main --help" nil]
    ["git push --force-with-lease origin 42-some-task && git push -f origin main" [:deny :forced-push-branch]]]))


(deftest a-forced-push-to-an-approved-pull-request-is-refused
  (let [approved {:review "APPROVED"}]
    (check-rows
     (for [c ["git push --force origin 42-some-task" "git push -f origin 42-some-task"
              "git push -fu origin 42-some-task" "git push --force-with-lease origin 42-some-task"
              "git push origin +42-some-task" "git push origin +HEAD:42-some-task"
              (str "cd " issue-folder " && git push --force-with-lease")]]
       [c approved [:deny :forced-push-approved "gh pr update-branch" "'42-some-task'"]]))))


(deftest review-states-other-than-approved-let-the-push-through
  (check-rows
   (for [review ["REVIEW_REQUIRED" "CHANGES_REQUESTED" "" nil]]
     ["git push --force-with-lease origin 42-some-task" {:review review} nil])))


(deftest a-push-reads-github-only-when-it-is-forced-to-an-issue-branch
  (testing "an unforced command reads nothing"
    (doseq [c ["git push origin 42-some-task" "git push -u origin 42-some-task" "git status"
               "git push origin main" "gh pr create --head 12-x --fill"]]
      (is (= [] (second (judged c {:review "APPROVED"}))) c)))
  (testing "a forced push to main is refused without asking GitHub"
    (is (= [] (second (judged "git push -f origin main" {:review "APPROVED"})))))
  (testing "a forced push asks about the branch it names, in the folder it runs in (#99)"
    (is (= [[:review-of "." "42-some-task"]] (second (judged "git push -f origin 42-some-task" {}))))
    (is (= [[:review-of issue-folder "42-some-task"]]
           (second (judged (str "cd " issue-folder " && git push -f origin 42-some-task") {}))))
    (is (= [[:review-of issue-folder "42-some-task"]]
           (second (judged (str "git -C " issue-folder " push -f origin 42-some-task") {}))))
    (is (= [[:branch-of issue-folder] [:review-of issue-folder "42-some-task"]]
           (second (judged (str "cd " issue-folder " && git push -f") {})))
        "a push to HEAD reads the folder's branch first")
    (is (= [[:review-of issue-folder "42-some-task"]]
           (second (judged (str "S=" issue-folder "; git -C $S push -f origin 42-some-task") {})))
        "a folder given by a variable is the resolved one"))
  (testing "GitHub answers for the folder of the push, not for the session's"
    (let [only-issue-folder (fn [dir _] (when (= dir issue-folder) "APPROVED"))]
      (is (= :forced-push-approved
             (:rule (decision (str "cd " issue-folder " && git push -f origin 42-some-task")
                              {:review only-issue-folder}))))
      (is (nil? (decision "git push -f origin 42-some-task" {:review only-issue-folder}))))))


(deftest a-folder-given-by-a-variable-is-read-from-the-command-and-the-environment
  (check-rows
   [[(str "S=" issue-folder "; cd $S && git push --force-with-lease") nil]
    [(str "S=" issue-folder " && cd \"$S\" && git push --force-with-lease") nil]
    [(str "S=" issue-folder "; cd ${S} && git push --force-with-lease") nil]
    [(str "export S=" issue-folder "; cd $S && git push --force-with-lease") nil]
    [(str "S=" issue-folder "; git -C $S push --force-with-lease") nil]
    ["cd ~/repo && git push --force-with-lease" nil]
    ["git -C ~/repo push --force-with-lease" nil]
    ["R=repo; cd ~/$R && git push --force-with-lease" nil]
    [(str "D=" issue-folder "; S=$D; cd $S && git push --force-with-lease") nil]
    ["cd $S_ENV && git push --force-with-lease" nil]
    [(str "S=" main-folder "; cd $S && git push --force-with-lease") [:deny :forced-push-branch "'main'"]]
    ["cd sub && git push -f origin 42-some-task" nil]
    ["cd -P /x && git push -f origin 42-some-task" nil]]))


(deftest a-folder-that-cannot-be-read-is-never-replaced-by-the-sessions
  (doseq [c ["cd $UNSET && git push -f" "cd \"$(pwd)\" && git push -f" "cd `pwd` && git push -f origin 42-some-task"
             "git -C $UNSET push --force-with-lease" "S=$(pwd); cd $S && git push -f"]]
    (testing c
      (let [d (decision c {})]
        (is (= [:deny :forced-push-dir] [(:decision d) (:rule d)]))
        (is (str/includes? (:reason d) "git -C <absolute path>")))))
  (check-rows
   [["cd $UNSET && git push -f" [:deny :forced-push-dir "$UNSET"]]
    ["cd $UNSET && git push -f origin main" [:deny :forced-push-branch "'main'"]]
    ["cd $UNSET && git push origin 42-some-task" nil]
    ["cd $UNSET && git push -f origin 42-some-task" [:deny :forced-push-dir]]
    ["cd $UNSET && gh pr create --head 12-x" nil]
    ["cd $UNSET && gh pr create --fill"
     [:deny :pr-create-unknown-branch "gh pr create --head <number>-<slug>" "$UNSET"]]
    ["cd $S_ENV && cd $UNSET && git push -f origin 42-some-task" [:deny :forced-push-dir]]
    [(str "S=" issue-folder "; S=$(pwd); cd $S && git push -f origin 42-some-task") [:deny :forced-push-dir]]]))


(deftest gh-pr-create-judges-the-branch-of-the-folder-it-runs-in
  (check-rows
   [[(str "cd " issue-folder " && gh pr create --fill") nil]
    [(str "S=" issue-folder "; cd $S && gh pr create --fill") nil]
    [(str "cd " issue-folder " && gh pr create -R o/r --fill") nil]
    ["gh pr create --head 12-x --fill" nil]
    ["gh pr create --head=12-x --fill" nil]
    ["gh pr create -H me:12-x --fill" nil]
    ["gh pr create -H=12-x --fill" nil]
    ["gh pr create -H12-x --fill" nil]
    ["gh pr create --head=me:12-x --fill" nil]
    ["gh pr create --head main --head 12-x --fill" nil]
    ["gh pr create --head=main --fill" [:deny :pr-create-no-issue "--head names 'main'"]]
    ["gh pr create -H=main --fill" [:deny :pr-create-no-issue "--head names 'main'"]]
    ["gh pr create -Hmain --fill" [:deny :pr-create-no-issue "--head names 'main'"]]
    ["gh pr create --head= --fill" [:deny :pr-create-no-issue "HEAD is 'main'"]]
    ["gh pr create --head --fill" [:deny :pr-create-no-issue "HEAD is 'main'"]]
    ["gh pr create -R o/r --head me:12-x --fill" nil]
    [(str "cd " main-folder " && gh pr create --fill") [:deny :pr-create-no-issue "'main'"]]
    [(str "cd " main-folder " && gh pr create -R o/r --fill") [:deny :pr-create-no-issue "'main'"]]
    ["cd /w/unknown && gh pr create --fill" [:deny :pr-create-no-issue "HEAD is 'unknown'"]]
    ["gh pr create --head main --fill" [:deny :pr-create-no-issue "--head names 'main'"]]
    ["gh pr create --head you:feature --fill" [:deny :pr-create-no-issue "--head names 'feature'"]]
    ["gh pr create --fill" {:base "develop" :repo "o/r"}
     [:deny :pr-create-no-issue "gh issue develop <n> -R o/r --checkout --base develop"]]])
  (is (= [[:branch-of issue-folder]] (second (judged (str "cd " issue-folder " && gh pr create --fill") {})))
      "the folder's branch is read in the folder")
  (is (= [] (second (judged "gh pr create --head 12-x" {}))) "a named branch reads nothing"))


(deftest the-working-folder-follows-cd-and-the---help-skip
  (check-rows
   [["gh pr create --head 12-x --help" nil]
    [(str "cd " main-folder " && gh pr create --help && gh pr create --fill") [:deny :pr-create-no-issue "'main'"]]
    [(str "cd " main-folder " --help && gh pr create --fill") [:deny :pr-create-no-issue "HEAD is 'main'"]]
    ["cd /w/force --help; gh pr create --fill" [:deny :pr-create-no-issue "'main'"]]]))


(deftest in-zsh-a-variable-that-must-be-split-is-refused
  (check-rows
   (concat
    (for [c ["set -- $t" "env $E bash x.sh" "for n in $list; do echo $n; done" "cmd=\"git status\"; $cmd --flag"
             "for n in ${list}; do :; done" "cmd=\"git status\"; ${cmd} --flag" "cmd=\"git status\" && $cmd"
             "sudo env $E bash x.sh"]]
      [c zsh [:deny :zsh-split "one word"]])
    (for [c ["set -- ${=t}" "for n in ${=list}; do :; done" "echo \"$v\"" "for x in $(seq 3); do :; done"
             "env A=1 bash x.sh" "echo $v" "echo $v and $w" "\"$cmd\" --flag"]]
      [c zsh nil]))))


(deftest in-zsh-a-command-word-from-a-variable-runs-unless-its-value-has-words
  (check-rows
   (concat
    (for [c ["$B cmd" "${B} cmd" "$cmd --flag" "B=/bin/echo; $B cmd" "B=ls; $B -l" "sudo $B x" "FOO=1 $B x"
             "if $B; then :; fi" "time $B x" "B=$(which ls); $B -l"]]
      [c zsh nil])
    (for [c ["B=\"echo hi\"; $B cmd" "B='echo hi'; ${B} cmd" "B=\"echo hi\" && $B cmd" "cmd=\"git status\"; $cmd --flag"
             "B=ls; B=\"a b\"; $B x"]]
      [c zsh [:deny :zsh-split]]))))


(deftest the-refusal-names-the-fix-for-a-split
  (is (str/includes? (:reason (decision "set -- $t" zsh)) "${="))
  (is (str/includes? (:reason (decision "cmd=\"git status\"; $cmd --flag" zsh)) "'$cmd --flag'")))


(deftest in-bash-words-split-by-themselves
  (check-rows
   (for [c ["set -- $t" "cmd=\"git status\"; $cmd --flag" "env $E bash x.sh" "echo \"$a:host\"" "echo $v:h"]]
     [c nil])))


(deftest in-zsh-dollar-name-colon-letter-is-a-modifier-and-is-refused
  (check-rows
   (concat
    (for [c ["echo \"$a:host\"" "echo $v:h" "echo \"x $v:t\"" "ls $d:h/x" "echo \"$a:gs/x/y/\"" "B=$p:r" "echo $v:e"
             "echo http://$h:aaa" "echo \"$(date) $v:u\"" "cat \"$f:q\""]]
      [c zsh [:deny :zsh-modifier "${v}:h" "${v:h}"]])
    (for [c ["echo '$v:h'" "echo \"${v}:h\"" "echo \"$h:$p\"" "echo \"$h:80\"" "echo ${v:h}" "echo \"$h:port\""
             "echo \\$v:h" "echo $v: h" "echo \"$a:x\"" "echo \"$a:g\"" "echo $v:" "echo ${v}:t" "echo \"${v:h}\""]]
      [c zsh nil])))
  (testing "the reason names the word"
    (is (str/includes? (:reason (decision "echo \"$a:host\"" zsh)) "'$a:host'"))))


(deftest the-split-rule-comes-before-the-modifier-rule
  (is (= :zsh-split (:rule (decision "set -- $t; echo $v:h" zsh)))))


(deftest a-gh-api-call-asks-only-when-it-can-change-github
  (check-rows
   (concat
    (for [c ["gh api repos/u473t8/x" "gh api repos/u473t8/x --paginate --jq '.[] | .name'"
             "gh api graphql -f query='{viewer{login}}'" "gh api -X GET repos/u473t8/x -f per_page=100"
             "gh api -X get repos/u473t8/x" "/usr/bin/gh api repos/u473t8/x" "gh api --method GET repos/u473t8/x"
             "gh api --method=get repos/u473t8/x" "gh api -XGET repos/u473t8/x" "gh api -X=GET repos/u473t8/x"
             "gh api -X DELETE -X GET repos/u473t8/x" "gh api repos/u473t8/x -X"]]
      [c nil])
    (for [c ["gh api -X POST repos/u473t8/x/issues -f title=t" "gh api repos/u473t8/x/issues -f title=t"
             "gh api --method=DELETE repos/u473t8/x/git/refs/heads/y" "gh api -XPATCH repos/u473t8/x"
             "gh api --method POST repos/u473t8/x" "gh api -X=POST repos/u473t8/x"
             "gh api -X GET -X DELETE repos/u473t8/x" "gh api --method GET -XDELETE repos/u473t8/x"
             "gh api graphql -f query='mutation { addStar(input: {}) { clientMutationId } }'"
             "gh api graphql -F query=@q.graphql"
             "echo \"gone: $(gh api -X DELETE repos/u473t8/x/git/refs/heads/y)\"" "/usr/bin/gh api -X POST x"]]
      [c [:ask :gh-api-write "can change GitHub"]]))))


(deftest quoted-text-and-heredoc-bodies-are-not-commands
  (check-rows
   (for [c ["gh issue create --title \"x\" --help" "git commit -m \"a && gh issue create -t y\""
            "gh pr create --head 12-x --title \"a; gh issue create\"" "cat <<'EOF' > b.md\ngh issue create -t y\nEOF"]]
     [c nil])))


(deftest a-command-nested-or-hidden-in-shell-syntax-is-still-judged
  (check-rows
   (for [c ["bash -c \"gh issue create -t x\"" "eval 'gh issue create -t x'" "echo `gh issue create -t x`"
            "cat <<<\"gh x\"\ngh issue create -t y" "cat <<EOF\nx\nEOF\ngh issue create -t y"
            "sh -c \"cd /tmp && gh issue create -t x\""
            "cat <<EOF|gh issue create -t y\nbody\nEOF" "cat <<EOF;gh issue create -t y\nbody\nEOF"
            "cat <<A; cat <<B\nx\nA\ny\nB\ngh issue create -t y" "cat <<EOF\r\nbody\r\nEOF\r\ngh issue create -t y"
            "echo $'it\\'s' && gh issue create -t y" "gh issue create --title '--help'"
            "if true; then gh issue create -t x; fi" "for x in a b; do gh issue create -t x; done"
            "sudo -u bob gh issue create -t x" "sudo -E gh issue create -t x" "bash -lc 'gh issue create -t x'"
            "echo \"$(case x in x) gh issue create -t y;; esac)\""]]
     [c [:deny :gh-issue-create]])))


(def waiting-in-the-foreground
  ["sleep 5" "gh pr checks 12 --watch --fail-fast" "gh run watch 99" ".shared/bin/kit merge"
   "bb .shared/bin/kit merge" ".shared/bin/kit review --wait" "bb .shared/bin/kit review --pr 5 --wait"
   ".shared/bin/kit checks" "bb .shared/bin/kit checks --pr 5" "bb bin/kit merge"
   "bash -c \"sleep 2\"" "echo $(sleep 1)" "git status && sleep 3" "sleep 2; echo done" "gh pr checks 1 --watch"
   "while true; do gh pr checks 1 --watch; done" "kit merge" "while true; do kit checks; sleep 5; done"
   "for i in 1 2; do echo $i; done; sleep 3"])


(def poll-loops
  ["while true; do gh api x || true; sleep 30; done" "until grep -q X f; do sleep 1; done"
   "for i in 1 2 3; do echo $i; sleep 1; done"])


(deftest a-command-that-waits-runs-in-the-background
  (check-rows
   (concat
    (for [c waiting-in-the-foreground]
      [c {:background? false} [:deny :foreground-wait "run_in_background"]])
    (for [c waiting-in-the-foreground]
      [c {:background? true} nil]))))


(deftest a-poll-loop-is-not-a-wait-for-one-event
  (check-rows
   (for [c poll-loops
         background? [false true]]
     [c {:background? background?} nil])))


(deftest words-like-merge-or-sleep-in-text-or-another-command-are-not-waits
  (check-rows
   (for [c ["echo \"kit merge later\"" "echo kit checks" "git merge main" "gh pr checks 12" "echo sleep"]]
     [c {:background? false} nil])))


(deftest a-wait-is-still-judged-by-the-other-rules
  (check-rows
   [["gh issue create --title x" {:background? true} [:deny :gh-issue-create]]
    ["sleep 5 && gh issue create --title x" {:background? true} [:deny :gh-issue-create]]]))


(deftest rules-are-tried-in-their-order
  (testing "DELIVERY_GUARD=off asks before anything else decides"
    (is (= :guard-off (:rule (decision "DELIVERY_GUARD=off sleep 5" {})))))
  (testing "a forced push is judged before a wait, a wait before the zsh rules"
    (is (= :forced-push-branch (:rule (decision "git push -f origin main; sleep 5" {}))))
    (is (= :foreground-wait (:rule (decision "sleep 5; set -- $t" zsh)))))
  (testing "the first statement that is refused decides"
    (is (= :gh-api-write (:rule (decision "gh api -X POST x; gh issue create -t x" {}))))))


(deftest the-hook-prints-the-json-claude-code-and-the-kit-read
  (is (= (str "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
              "\"permissionDecisionReason\":\"no \\\"x\\\"\"}}")
         (rules/decision-json {:decision :deny :rule :foreground-wait :reason "no \"x\""}))
      "the text managed/pretooluse.sh greps for stays contiguous")
  (is (= {"hookSpecificOutput" {"hookEventName"      "PreToolUse"
                                "permissionDecision" "ask"
                                "permissionDecisionReason" "r"}}
         (json/parse-string (rules/decision-json {:decision :ask :rule :guard-off :reason "r"})))))


(deftest a-read-that-fails-stops-the-judgement-so-the-hook-refuses
  (is (thrown? Exception
               (rules/judge (statements/expand (tokenizer/tokenize "gh pr create --fill"))
                            (assoc world :branch-of (fn [_] (throw (ex-info "no git" {}))))))
      "the entry turns an exception into exit 2 (ADR-0001)")
  (is (thrown? Exception
               (rules/judge (statements/expand (tokenizer/tokenize "git push -f origin 42-x"))
                            (assoc world :review-of (fn [_ _] (throw (ex-info "no gh" {})))))))
  (is (nil? (rules/judge (statements/expand (tokenizer/tokenize "git status"))
                         (assoc world
                                :branch-of (fn [_] (throw (ex-info "never read" {})))
                                :review-of (fn [_ _] (throw (ex-info "never read" {}))))))
      "a command with no use for them never calls them"))
