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
  (loop [ws words]
    (let [t (:t (first ws))]
      (cond
        (nil? t)         ws
        (assignment? t)  (recur (rest ws))
        (keywords t)     (recur (rest ws))
        (= t "function") (recur (drop 2 ws))
        (wrapper-args t) (recur (loop [ws (rest ws)]
                                  (let [o (:t (first ws) "")]
                                    (cond ((wrapper-args t) o) (recur (drop 2 ws))
                                          (and (str/starts-with? o "-") (not= o "-")) (recur (rest ws))
                                          (and (= t "env") (assignment? o)) (recur (rest ws))
                                          :else ws))))
        :else            ws))))


(def shells #{"bash" "sh" "zsh" "dash"})


(defn expand
  "Statements, with the strings of `bash -c …` (-lc, -ec and the like; sh, zsh, dash) and `eval …` read as
  statements of their own."
  [statements]
  (mapcat (fn [st]
            (let [ws    (strip-head st)
                  head  (some-> (:t (first ws)) (str/replace #".*/" ""))
                  ts    (map :t ws)
                  inner (cond
                          (shells head)   (some->> (drop-while #(not (re-matches #"-[A-Za-z]*c[A-Za-z]*" %)) (rest ts))
                                                   second
                                                   vector)
                          (= head "eval") [(str/join " " (rest ts))]
                          :else           nil)]
              (cons st (mapcat (comp expand tokenizer/tokenize) inner))))
   statements))
