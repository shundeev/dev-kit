(ns dev-kit.guard.tokenizer
  "Reads a shell command line into statements of words (the guard, #141)."
  (:require
   [clojure.string :as str]))


;; ------------------------------------------------------------------ tokenizer


(def ^:private name-start? #(or (Character/isLetter (char %)) (= % \_)))


(def ^:private zsh-modifier
  "`$name:` followed by what zsh reads as a history-style modifier (:h, :t, :r, :e, :l, :u, :a, :A, :c, :P, :q, :Q,
  :s, :&, and :g or :G before s or &), which it applies without braces, also inside double quotes."
  #"^\$[A-Za-z_][A-Za-z0-9_]*:(?:[aAcehlPqQrstu&]|[gG][sS&])")


(declare tokenize)


(defn- read-balanced
  "From i, just after `$(`, to the matching `)`. Returns [content index-after]. Quotes and escapes inside are
  skipped, so a `)` in them does not close it; neither does the `)` after a pattern of a `case … esac`."
  [^String command i]
  (let [length (count command)]
    (loop [j          i
           depth      1
           open-quote nil
           cases      0
           bare       ""]
      (if (>= j length)
        [(subs command i length) length]
        (let [current      (.charAt command j)
              ;; a bare word just ended: count `case` against `esac`
              [cases bare] (if (or (Character/isLetter current) (= current \_))
                             [cases (str bare current)]
                             [(case bare
                                "case" (inc cases)
                                "esac" (max 0 (dec cases))
                                cases) ""])]
          (cond
            (and (= current \\) (not= open-quote \')) (recur (+ j 2) depth open-quote cases "")
            open-quote         (recur (inc j) depth (if (= current open-quote) nil open-quote) cases "")
            (#{\' \"} current) (recur (inc j) depth current cases "")
            (= current \()     (recur (inc j) (inc depth) open-quote cases "")
            (= current \))     (cond (pos? cases) (recur (inc j) depth open-quote cases "")
                                     (= depth 1)  [(subs command i j) (inc j)]
                                     :else        (recur (inc j) (dec depth) open-quote cases ""))
            :else              (recur (inc j) depth open-quote cases bare)))))))


(defn- read-backtick
  "From i, just after a backtick, to the closing one. Returns [content index-after]."
  [^String command i]
  (let [length (count command)]
    (loop [j i]
      (cond (>= j length) [(subs command i length) length]
            (= (.charAt command j) \\) (recur (+ j 2))
            (= (.charAt command j) \`) [(subs command i j) (inc j)]
            :else (recur (inc j))))))


(defn- skip-heredocs
  "At the start of a line, after the line that opened them: skips each heredoc's body up to its terminator
  line, a CRLF line end included. Returns the index after the last terminator."
  [^String command i heredocs]
  (reduce (fn [j {:keys [term strip]}]
            (loop [j j]
              (if (>= j (count command))
                j
                (let [line-end (let [k (.indexOf command "\n" (int j))]
                                 (if (neg? k)
                                   (count command)
                                   k))
                      line     (str/replace (subs command j line-end) #"\r$" "")
                      line     (if strip
                                 (str/replace line #"^\t+" "")
                                 line)]
                  (if (= line term)
                    (min (count command) (inc line-end))
                    (recur (inc line-end)))))))
          i
          heredocs))


(defn tokenize
  "The statements of a shell command, each a vector of words {:text text, :var true when it holds an unquoted
  $name or ${name}, :mod true when it holds `$name:` and a zsh modifier letter, unquoted or in double quotes,
  :quoted true when any of it was quoted or escaped}. Statements end at && || ; | & ( ) newlines
  and the words { }. Redirections and their targets are dropped, heredoc bodies skipped. Commands inside $(…)
  and `…` follow as statements of their own."
  [^String command]
  (let [length (count command)]
    (loop [i        0
           word     nil
           flags    #{}
           drop?    false
           words    []
           out      []
           nested   []
           heredocs []]
      (let [end-word (fn [words]
                       (cond
                         (nil? word) words
                         drop?       words
                         :else       (conj words
                                           {:text   word
                                            :var    (contains? flags :var)
                                            :mod    (contains? flags :mod)
                                            :quoted (contains? flags :quoted)})))
            end-stmt (fn [out words]
                       (let [closed (end-word words)]
                         (if (seq closed)
                           (conj out closed)
                           out)))
            more     (fn [i]
                       (when (< i length)
                         (.charAt command i)))
            quoted   (conj flags :quoted)]
        (if (>= i length)
          (into (end-stmt out words) nested)
          (let [current (.charAt command i)]
            (cond
              ;; a newline ends the statement; heredoc bodies opened on this line are skipped
              (= current \newline)
              (recur (skip-heredocs command (inc i) heredocs) nil #{} false [] (end-stmt out words) nested [])

              ;; a redirection's target may follow after blanks: it is still dropped
              (#{\space \tab \return} current)
              (recur (inc i) nil #{} (and drop? (nil? word)) (end-word words) out nested heredocs)

              (and (= current \#) (nil? word))
              (let [k (.indexOf command "\n" (int i))]
                (recur (if (neg? k) length k) nil #{} false words out nested heredocs))

              (= current \\)
              (if (= (more (inc i)) \newline)
                (recur (+ i 2) word flags drop? words out nested heredocs)
                (recur (+ i 2) (str word (or (more (inc i)) "")) quoted drop? words out nested heredocs))

              (= current \')
              (let [k (.indexOf command "'" (int (inc i)))
                    k (if (neg? k) length k)]
                (recur (min length (inc k)) (str word (subs command (inc i) k)) quoted drop? words out nested heredocs))

              ;; $'…', ANSI-C quoting: a backslash escapes, so \' does not end it
              (and (= current \$) (= (more (inc i)) \'))
              (let [[text j] (loop [j    (+ i 2)
                                    text ""]
                               (cond (>= j length) [text length]
                                     (= (.charAt command j) \\) (recur (+ j 2) (str text (or (more (inc j)) "")))
                                     (= (.charAt command j) \') [text (inc j)]
                                     :else (recur (inc j) (str text (.charAt command j)))))]
                (recur j (str word text) quoted drop? words out nested heredocs))

              (= current \")
              (let [[text j inner modifier]
                    (loop [j        (inc i)
                           text     ""
                           inner    []
                           modifier false]
                      (if (>= j length)
                        [text j inner modifier]
                        (let [next-char (.charAt command j)]
                          (cond
                            (= next-char \") [text (inc j) inner modifier]
                            (and (= next-char \\) (#{\" \\ \$ \` \newline} (more (inc j))))
                            (recur (+ j 2)
                                   (if (= (more (inc j)) \newline)
                                     text
                                     (str text (more (inc j))))
                                   inner
                                   modifier)
                            (and (= next-char \$) (= (more (inc j)) \())
                            (let [[sub k] (read-balanced command (+ j 2))]
                              (recur k (str text "$(" sub ")") (conj inner sub) modifier))
                            (= next-char \`)
                            (let [[sub k] (read-backtick command (inc j))]
                              (recur k (str text "`" sub "`") (conj inner sub) modifier))
                            :else (recur (inc j)
                                         (str text next-char)
                                         inner
                                         (or modifier
                                             (and (= next-char \$)
                                                  (boolean (re-find zsh-modifier (subs command j))))))))))]
                (recur j
                       (str word text)
                       (cond-> quoted modifier (conj :mod))
                       drop?
                       words
                       out
                       (into nested (mapcat tokenize inner))
                       heredocs))

              (and (= current \$) (= (more (inc i)) \())
              (let [[sub j] (read-balanced command (+ i 2))]
                (recur j (str word "$(" sub ")") flags drop? words out (into nested (tokenize sub)) heredocs))

              (= current \`)
              (let [[sub j] (read-backtick command (inc i))]
                (recur j (str word "`" sub "`") flags drop? words out (into nested (tokenize sub)) heredocs))

              (and (= current \$) (some-> (more (inc i)) int (#(or (name-start? %) (= % (int \{))))))
              (recur (inc i)
                     (str word current)
                     (cond-> (conj flags :var) (re-find zsh-modifier (subs command i)) (conj :mod))
                     drop?
                     words
                     out
                     nested
                     heredocs)

              ;; redirections: an fd number before them goes, their target goes, a heredoc opens
              (#{\< \>} current)
              (let [words    (if (and word (re-matches #"\d+" word))
                               words
                               (end-word words))
                    operator (re-find #"^(<<<|<<-|<<|>>|>&|<&|>\||&>>|&>|<>|>|<)" (subs command i))
                    j        (+ i (count (first operator)))]
                (case (first operator)
                  ("<<" "<<-")
                  ;; the delimiter is one word, quoted or not: it ends at a blank or an operator
                  (let [[_ blanks delimiter] (re-find #"^([ \t]*)((?:'[^']*'|\"[^\"]*\"|\\.|[^\s;|&<>()'\"\\])+)"
                                                      (subs command j))
                        term (str/replace (or delimiter "") #"['\"\\]" "")]
                    (recur (+ j (count (or blanks "")) (count (or delimiter "")))
                           nil
                           #{}
                           false
                           words
                           out
                           nested
                           (conj heredocs {:term term :strip (= (first operator) "<<-")})))
                  (">&" "<&")
                  (if-let [digits (re-find #"^[0-9-]+" (subs command j))]
                    (recur (+ j (count digits)) nil #{} false words out nested heredocs)
                    (recur j nil #{} true words out nested heredocs))
                  (recur j nil #{} true words out nested heredocs)))

              (and (= current \&) (= (more (inc i)) \>))
              (let [j (if (= (more (+ i 2)) \>)
                        (+ i 3)
                        (+ i 2))]
                (recur j nil #{} true (end-word words) out nested heredocs))

              (#{\; \& \| \( \)} current)
              (let [j (if (and (#{\& \|} current) (= (more (inc i)) current))
                        (+ i 2)
                        (inc i))
                    j (if (and (= current \|) (= (more j) \&))
                        (inc j)
                        j)]
                (recur j nil #{} false [] (end-stmt out words) nested heredocs))

              :else
              (let [word' (str word current)]
                (if (and (#{"{" "}"} word') (#{\space \tab \newline \; nil} (more (inc i))) (empty? (end-word words)))
                  (recur (inc i) nil #{} false [] (end-stmt out words) nested heredocs)
                  (recur (inc i) word' flags drop? words out nested heredocs))))))))))
