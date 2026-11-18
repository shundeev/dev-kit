(ns dev-kit.guard.tokenizer
  "Reads a shell command line into statements of words (the guard, #141)."
  (:require
   [clojure.string :as str]))


;; ------------------------------------------------------------------ tokenizer


(def ^:private name-start? #(or (Character/isLetter (char %)) (= % \_)))


(declare tokenize)


(defn- read-balanced
  "From i, just after `$(`, to the matching `)`. Returns [content index-after]. Quotes and escapes inside are
  skipped, so a `)` in them does not close it; neither does the `)` after a pattern of a `case … esac`."
  [^String s i]
  (let [n (count s)]
    (loop [j     i
           depth 1
           q     nil
           cases 0
           w     ""]
      (if (>= j n)
        [(subs s i n) n]
        (let [c (.charAt s j)
              ;; a bare word just ended: count `case` against `esac`
              [cases w] (if (or (Character/isLetter c) (= c \_))
                          [cases (str w c)]
                          [(case w
                             "case" (inc cases)
                             "esac" (max 0 (dec cases))
                             cases) ""])]
          (cond
            (and (= c \\) (not= q \')) (recur (+ j 2) depth q cases "")
            q        (recur (inc j) depth (if (= c q) nil q) cases "")
            (#{\' \"} c) (recur (inc j) depth c cases "")
            (= c \() (recur (inc j) (inc depth) q cases "")
            (= c \)) (cond (pos? cases) (recur (inc j) depth q cases "")
                           (= depth 1)  [(subs s i j) (inc j)]
                           :else        (recur (inc j) (dec depth) q cases ""))
            :else    (recur (inc j) depth q cases w)))))))


(defn- read-backtick
  "From i, just after a backtick, to the closing one. Returns [content index-after]."
  [^String s i]
  (let [n (count s)]
    (loop [j i]
      (cond (>= j n) [(subs s i n) n]
            (= (.charAt s j) \\) (recur (+ j 2))
            (= (.charAt s j) \`) [(subs s i j) (inc j)]
            :else (recur (inc j))))))


(defn- skip-heredocs
  "At the start of a line, after the line that opened them: skips each heredoc's body up to its terminator
  line, a CRLF line end included. Returns the index after the last terminator."
  [^String s i heredocs]
  (reduce (fn [j {:keys [term strip]}]
            (loop [j j]
              (if (>= j (count s))
                j
                (let [e    (let [k (.indexOf s "\n" (int j))]
                             (if (neg? k)
                               (count s)
                               k))
                      line (str/replace (subs s j e) #"\r$" "")
                      line (if strip
                             (str/replace line #"^\t+" "")
                             line)]
                  (if (= line term)
                    (min (count s) (inc e))
                    (recur (inc e)))))))
          i
          heredocs))


(defn tokenize
  "The statements of a shell command, each a vector of words {:t text, :var true when it holds an unquoted
  $name or ${name}, :q true when any of it was quoted or escaped}. Statements end at && || ; | & ( ) newlines
  and the words { }. Redirections and their targets are dropped, heredoc bodies skipped. Commands inside $(…)
  and `…` follow as statements of their own."
  [^String s]
  (let [n (count s)]
    (loop [i        0
           word     nil
           fl       #{}
           drop?    false
           words    []
           out      []
           nested   []
           heredocs []]
      (let [end-word (fn [words]
                       (cond (nil? word) words
                             drop?       words
                             :else       (conj words {:t word :var (contains? fl :var) :q (contains? fl :q)})))
            end-stmt (fn [out words]
                       (let [ws (end-word words)]
                         (if (seq ws)
                           (conj out ws)
                           out)))
            more     (fn [i]
                       (when (< i n)
                         (.charAt s i)))
            quoted   (conj fl :q)]
        (if (>= i n)
          (into (end-stmt out words) nested)
          (let [c (.charAt s i)]
            (cond
              ;; a newline ends the statement; heredoc bodies opened on this line are skipped
              (= c \newline)
              (recur (skip-heredocs s (inc i) heredocs) nil #{} false [] (end-stmt out words) nested [])

              ;; a redirection's target may follow after blanks: it is still dropped
              (#{\space \tab \return} c)
              (recur (inc i) nil #{} (and drop? (nil? word)) (end-word words) out nested heredocs)

              (and (= c \#) (nil? word))
              (let [k (.indexOf s "\n" (int i))] (recur (if (neg? k) n k) nil #{} false words out nested heredocs))

              (= c \\)
              (if (= (more (inc i)) \newline)
                (recur (+ i 2) word fl drop? words out nested heredocs)
                (recur (+ i 2) (str word (or (more (inc i)) "")) quoted drop? words out nested heredocs))

              (= c \')
              (let [k (.indexOf s "'" (int (inc i)))
                    k (if (neg? k) n k)]
                (recur (min n (inc k)) (str word (subs s (inc i) k)) quoted drop? words out nested heredocs))

              ;; $'…', ANSI-C quoting: a backslash escapes, so \' does not end it
              (and (= c \$) (= (more (inc i)) \'))
              (let [[text j] (loop [j    (+ i 2)
                                    text ""]
                               (cond (>= j n) [text n]
                                     (= (.charAt s j) \\) (recur (+ j 2) (str text (or (more (inc j)) "")))
                                     (= (.charAt s j) \') [text (inc j)]
                                     :else (recur (inc j) (str text (.charAt s j)))))]
                (recur j (str word text) quoted drop? words out nested heredocs))

              (= c \")
              (let [[text j inner]
                    (loop [j     (inc i)
                           text  ""
                           inner []]
                      (if (>= j n)
                        [text j inner]
                        (let [d (.charAt s j)]
                          (cond
                            (= d \") [text (inc j) inner]
                            (and (= d \\) (#{\" \\ \$ \` \newline} (more (inc j))))
                            (recur (+ j 2)
                                   (if (= (more (inc j)) \newline)
                                     text
                                     (str text (more (inc j))))
                                   inner)
                            (and (= d \$) (= (more (inc j)) \())
                            (let [[sub k] (read-balanced s (+ j 2))] (recur k (str text "$(" sub ")") (conj inner sub)))
                            (= d \`)
                            (let [[sub k] (read-backtick s (inc j))] (recur k (str text "`" sub "`") (conj inner sub)))
                            :else (recur (inc j) (str text d) inner)))))]
                (recur j (str word text) quoted drop? words out (into nested (mapcat tokenize inner)) heredocs))

              (and (= c \$) (= (more (inc i)) \())
              (let [[sub j] (read-balanced s (+ i 2))]
                (recur j (str word "$(" sub ")") fl drop? words out (into nested (tokenize sub)) heredocs))

              (= c \`)
              (let [[sub j] (read-backtick s (inc i))]
                (recur j (str word "`" sub "`") fl drop? words out (into nested (tokenize sub)) heredocs))

              (and (= c \$) (some-> (more (inc i)) int (#(or (name-start? %) (= % (int \{))))))
              (recur (inc i) (str word c) (conj fl :var) drop? words out nested heredocs)

              ;; redirections: an fd number before them goes, their target goes, a heredoc opens
              (#{\< \>} c)
              (let [words (if (and word (re-matches #"\d+" word))
                            words
                            (end-word words))
                    op    (re-find #"^(<<<|<<-|<<|>>|>&|<&|>\||&>>|&>|<>|>|<)" (subs s i))
                    j     (+ i (count (first op)))]
                (case (first op)
                  ("<<" "<<-")
                  ;; the delimiter is one word, quoted or not: it ends at a blank or an operator
                  (let [[_ ws d] (re-find #"^([ \t]*)((?:'[^']*'|\"[^\"]*\"|\\.|[^\s;|&<>()'\"\\])+)" (subs s j))
                        term     (str/replace (or d "") #"['\"\\]" "")]
                    (recur (+ j (count (or ws "")) (count (or d "")))
                           nil
                           #{}
                           false
                           words
                           out
                           nested
                           (conj heredocs {:term term :strip (= (first op) "<<-")})))
                  (">&" "<&")
                  (if-let [t (re-find #"^[0-9-]+" (subs s j))]
                    (recur (+ j (count t)) nil #{} false words out nested heredocs)
                    (recur j nil #{} true words out nested heredocs))
                  (recur j nil #{} true words out nested heredocs)))

              (and (= c \&) (= (more (inc i)) \>))
              (let [j (if (= (more (+ i 2)) \>)
                        (+ i 3)
                        (+ i 2))]
                (recur j nil #{} true (end-word words) out nested heredocs))

              (#{\; \& \| \( \)} c)
              (let [j (if (and (#{\& \|} c) (= (more (inc i)) c))
                        (+ i 2)
                        (inc i))
                    j (if (and (= c \|) (= (more j) \&))
                        (inc j)
                        j)]
                (recur j nil #{} false [] (end-stmt out words) nested heredocs))

              :else
              (let [word' (str word c)]
                (if (and (#{"{" "}"} word') (#{\space \tab \newline \; nil} (more (inc i))) (empty? (end-word words)))
                  (recur (inc i) nil #{} false [] (end-stmt out words) nested heredocs)
                  (recur (inc i) word' fl drop? words out nested heredocs))))))))))
