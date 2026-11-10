#!/usr/bin/env bb
;; Lists functions and defs with exactly one call site: candidates to inline
;; into their caller. Reads clj-kondo's var definitions and usages over src,
;; test and the given paths, so a call from a test counts; a call of a var from
;; itself does not.
;; Protocols, records, defonce and macros are left out: they are not inlined.
;; Run from the repository root:
;;
;;   bb .shared/clojure/single_caller.clj [path ...]     (default: src; where to report)
(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])


(let [roots         (or (seq *command-line-args*) ["src"])
      {:keys [out]} (apply p/shell
                           {:out :string :continue true}
                           "clj-kondo"
                           "--config"
                           "{:output {:format :edn} :analysis true}"
                           "--lint"
                           (distinct (concat ["src" "test"] roots)))
      {:keys [var-definitions var-usages]} (:analysis (edn/read-string out))
      ;; A .cljc file is analysed once per language: count each site once.
      calls         (->> var-usages
                         (remove #(and (= (:from %) (:to %))
                                       (= (:from-var %) (:name %))))
                         (map (juxt :to :name :filename :row :col))
                         distinct
                         (group-by (juxt first second)))
      lines         (->> var-definitions
                         (filter #('#{clojure.core/def clojure.core/defn clojure.core/defn-
                                      cljs.core/def cljs.core/defn cljs.core/defn-}
                                    (:defined-by %)))
                         (filter #(some (fn [root] (str/starts-with? (:filename %) root)) roots))
                         (keep (fn [{:keys [ns name filename row]}]
                                 (let [sites (calls [ns name])]
                                   (when (= 1 (count sites))
                                     (let [[_ _ file line] (first sites)]
                                       (format "%s:%d: %s/%s — one call, %s:%d"
                                               filename
                                               row
                                               ns
                                               name
                                               file
                                               line))))))
                         distinct
                         sort)]
  (run! println lines)
  (println (count lines) "single-caller vars"))
