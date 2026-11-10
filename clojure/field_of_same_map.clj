#!/usr/bin/env bb
;; Lists calls that hand a function a map and a field of that same map,
;; `(f m (:k m))`: pass `m`, read `:k` inside `f`. clj-kondo hooks attach to
;; named vars only, so a check over every call is a script, not a linter.
;; clojure.core and special forms are skipped: `(assoc m :a (:b m))` is no
;; smell. Run from the repository root:
;;
;;   bb .shared/clojure/field_of_same_map.clj [path ...]     (default: src)
(require '[babashka.fs :as fs]
         '[rewrite-clj.zip :as z])


(let [roots (or (seq *command-line-args*) ["src"])
      sexpr #(try (z/sexpr %) (catch Exception _ nil))
      lines (for [file  (->> roots
                             (mapcat #(if (fs/directory? %)
                                        (fs/glob % "**.{clj,cljs,cljc}")
                                        [(fs/path %)]))
                             (map str)
                             sort)
                  node  (->> (iterate z/next (z/of-file file {:track-position? true}))
                             (take-while (complement z/end?)))
                  :let  [head (and (= :list (z/tag node)) (z/down node))
                         f    (some-> head sexpr)]
                  :when (and (symbol? f)
                             (not (special-symbol? f))
                             (not (and (nil? (namespace f)) (ns-resolve 'clojure.core f))))
                  :let  [args (take-while some? (iterate z/right (z/right head)))
                         syms (->> args (map sexpr) (filter symbol?) set)]
                  arg   args
                  :when (= :list (z/tag arg))
                  :let  [[k m] (take 2 (iterate z/right (z/down arg)))]
                  :when (and (keyword? (some-> k sexpr)) (syms (some-> m sexpr)))
                  :let  [[row col] (z/position node)]]
              (format "%s:%d:%d: %s gets %s and (%s %s) — pass %s, read %s inside %s"
                      file
                      row
                      col
                      f
                      (z/string m)
                      (z/string k)
                      (z/string m)
                      (z/string m)
                      (z/string k)
                      f))]
  (run! println lines)
  (println (count lines) "hits"))
