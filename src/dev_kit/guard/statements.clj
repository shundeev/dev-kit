(ns dev-kit.guard.statements
  "What a statement is, from its command word on: wrappers, assignments, nested shells (the guard, #141)."
  (:require
   [clojure.string :as str]
   [dev-kit.guard.tokenizer :as tokenizer]))


;; ------------------------------------------------------------------ statements


(def wrapper-args
  "The wrappers a statement may start with, each with its options that take a value."
  {"sudo"    #{"-u" "-g" "-h" "-p" "-C" "-D" "-R" "-T" "-U" "-r" "-t" "--user" "--group" "--host" "--prompt"
               "--chdir" "--chroot" "--close-from" "--role" "--type" "--other-user" "--command-timeout"}
   "env"     #{"-u" "--unset" "-C" "--chdir" "-S" "--split-string"}
   "time"    #{"-f" "--format" "-o" "--output"}
   "exec"    #{"-a"}
   "command" #{}
   "nohup"   #{}})


(def keywords "Shell words that may stand before a command." #{"then" "do" "else" "elif" "if" "while" "until" "!"})


(def assignment? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %))


(defn strip-head
  "The words of a statement from its command on: without leading assignments, shell keywords (then, do, if,
  while, !, function f) and wrappers with their options (sudo -u bob, env -u X A=1, time -p, …)."
  [words]
  (loop [words words]
    (let [text (:text (first words))]
      (cond
        (nil? text)         words
        (assignment? text)  (recur (rest words))
        (keywords text)     (recur (rest words))
        (= text "function") (recur (drop 2 words))
        (wrapper-args text) (recur (loop [words (rest words)]
                                     (let [option (:text (first words) "")]
                                       (cond ((wrapper-args text) option) (recur (drop 2 words))
                                             (and (str/starts-with? option "-") (not= option "-")) (recur (rest words))
                                             (and (= text "env") (assignment? option)) (recur (rest words))
                                             :else words))))
        :else               words))))


(def shells #{"bash" "sh" "zsh" "dash"})


(defn expand
  "Statements, with the strings of `bash -c …` (-lc, -ec and the like; sh, zsh, dash) and `eval …` read as
  statements of their own."
  [statements]
  (mapcat (fn [statement]
            (let [words (strip-head statement)
                  head  (some-> (:text (first words)) (str/replace #".*/" ""))
                  texts (map :text words)
                  inner (cond
                          (shells head)   (some->> (drop-while #(not (re-matches #"-[A-Za-z]*c[A-Za-z]*" %))
                                                               (rest texts))
                                                   second
                                                   vector)
                          (= head "eval") [(str/join " " (rest texts))]
                          :else           nil)]
              (cons statement (mapcat (comp expand tokenizer/tokenize) inner))))
   statements))
