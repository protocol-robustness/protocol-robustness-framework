(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[resolver-sim.concepts.registry :as concepts-registry])

(def concept-root (io/file "data/concepts"))
(def registry-path (io/file concept-root "registry.edn"))
(def known-protocols #{:protocol/sew-v1 :protocol/prf})
(def supported-maps-to-types #{:protocol.actor :protocol.role :protocol.entity :protocol.action :protocol.outcome :protocol.guard :protocol.parameter})

(def required-keys
  #{:concept/id :concept/name :concept/summary :concept/stakeholder-question
    :concept/protocols :concept/roles :concept/entities :concept/actions
    :concept/outcomes :concept/failure-modes :concept/assumptions
    :concept/out-of-scope :concept/type :concept/layer})

(def type-dir-map
  {:use-case "use-case"
   :decision-quality "decision-quality"
   :assurance "assurance"
   :allocation "allocation"
   :yield "yield"
   :framework "framework"
   :security "security"
   :mechanism "mechanism"})

(def standard-concept-types
  #{:use-case :decision-quality :assurance :allocation :yield :framework :security})

(def known-gap-patterns
  #"(?i)(currently unsupported|not yet checked|temporarily excluded|not yet implemented|not yet supported|future work|to be done)")

(defn edn-files [dir]
  (filter #(and (.endsWith (.getName %) ".edn") (not (.isDirectory %)))
          (file-seq (io/file dir))))

(defn parse-edn [f]
  (try [(edn/read-string (slurp f)) nil]
       (catch Exception e [nil (str (.getMessage e))])))

(defn missing-keys [data]
  (seq (set/difference required-keys (set (keys data)))))

(defn check-maps-to [path context errors]
  (let [maps-val (:maps-to context)]
    (when maps-val
      (when (not (vector? maps-val))
        (swap! errors conj (str path " :maps-to must be a vector, got " (type maps-val))))
      (doseq [v (flatten (if (vector? maps-val) maps-val [maps-val]))]
        (cond
          (string? v)
          (swap! errors conj (str path " :maps-to value \"" v "\" is a string; should be a :protocol.* keyword"))
          (and (keyword? v) (not (some #(= (namespace v) %) (map name supported-maps-to-types))))
          (swap! errors conj (str path " :maps-to value " v " has unsupported namespace; expected one of " supported-maps-to-types)))))))

(defn check-value-maps-to [path obj errors]
  (cond
    (map? obj)
    (do (when (:maps-to obj) (check-maps-to path obj errors))
        (doseq [[k v] obj]
          (check-value-maps-to (str path "/" k) v errors)))
    (sequential? obj)
    (doseq [v obj] (check-value-maps-to path v errors))))

(def evidence-keys #{:scenarios :benchmarks :claims})
(def use-case-required-keys
  #{:concept/maturity :concept/support-status :concept/known-gaps :concept/evidence})
(def conceptual-maturities #{:illustrative :mapping-reviewed :scenario-backed :benchmark-backed})
(def support-statuses #{:not-asserted})
(def mapping-statuses #{:native :derived :approximate :not-modelled})

(defn check-use-case-contract [path concept errors]
  (when (= :use-case (:concept/type concept))
    (let [missing (set/difference use-case-required-keys (set (keys concept)))]
      (when (seq missing)
        (swap! errors conj (str path " :use-case missing required keys " (pr-str missing))))
      (when-not (contains? conceptual-maturities (:concept/maturity concept))
        (swap! errors conj (str path " has invalid :concept/maturity " (:concept/maturity concept))))
      (when-not (contains? support-statuses (:concept/support-status concept))
        (swap! errors conj (str path " has invalid :concept/support-status " (:concept/support-status concept))))
      (when-not (vector? (:concept/known-gaps concept))
        (swap! errors conj (str path " :concept/known-gaps must be a vector"))))))

(defn check-mapping-statuses [path obj errors]
  (cond
    (map? obj)
    (do
      (when (and (contains? obj :mapping/status)
                 (not (contains? mapping-statuses (:mapping/status obj))))
        (swap! errors conj (str path " has invalid :mapping/status " (:mapping/status obj))))
      (doseq [[k v] obj]
        (check-mapping-statuses (str path "/" k) v errors)))
    (sequential? obj)
    (doseq [v obj] (check-mapping-statuses path v errors))))

(defn- normalise-for-overlap
  "Normalise a string for near-duplicate comparison: lowercase, strip
   non-alphanumeric, collapse whitespace."
  [s]
  (-> s
      (clojure.string/lower-case)
      (clojure.string/replace #"[^a-z0-9]+" " ")
      (clojure.string/trim)))

(defn check-out-of-scope [path concept errors]
  (let [oos (:concept/out-of-scope concept)]
    (when (nil? oos)
      (swap! errors conj (str path " :concept/out-of-scope is required")))
    (when (and (some? oos) (not (vector? oos)))
      (swap! errors conj (str path " :concept/out-of-scope must be a vector, got " (type oos))))
    (when (and (vector? oos) (empty? oos))
      (swap! errors conj (str path " :concept/out-of-scope must be non-empty for production concepts")))
    (when (vector? oos)
      (doseq [s oos]
        (when-not (string? s)
          (swap! errors conj (str path " :concept/out-of-scope entry " (pr-str s) " is not a string"))))
      (let [duplicates (set (for [[id freq] (frequencies oos) :when (> freq 1)] id))]
        (doseq [d duplicates]
          (swap! errors conj (str path " :concept/out-of-scope has duplicate entry: " (pr-str d)))))
      ;; Semantic-overlap lint: flag distinct entries that normalise to the same
      ;; canonical form (trivially reworded duplicates).
      (let [normalised (mapv (fn [s] [(normalise-for-overlap s) s]) (filter string? oos))
            by-norm (group-by first normalised)
            overlaps (keep (fn [[k vs]]
                             (when (> (count vs) 1)
                               [k (mapv second vs)]))
                           by-norm)]
        (doseq [[_ vs] overlaps]
          (swap! errors conj (str path " :concept/out-of-scope has semantically overlapping entries: " (pr-str vs)))))
      (doseq [s oos]
        (when (and (string? s) (re-find known-gap-patterns s))
          (swap! errors conj (str path " :concept/out-of-scope entry appears to describe a temporary known gap rather than a permanent boundary: " (pr-str s))))))))

(def known-gaps-root (io/file "data/known-gaps"))
(def known-gap-required-keys
  #{:issue/id :current-behaviour :intended-contract
    :reason-non-gating :removal-or-conversion-condition})

(defn load-known-gap-registry
  "Load all known-gap issue maps under data/known-gaps/ into a flat vector.
   Returns {:issues [map...] :errors [string...]}."
  []
  (let [files (edn-files known-gaps-root)
        issues (atom [])
        errors (atom [])]
    (doseq [f (sort files)]
      (let [rel (str (.relativize (.toPath (.getCanonicalFile known-gaps-root))
                                  (.toPath (.getCanonicalFile f))))
            [data parse-err] (parse-edn f)]
        (if parse-err
          (swap! errors conj (str "data/known-gaps/" rel ": " parse-err))
          (doseq [issue data]
            (swap! issues conj (assoc issue :known-gap/file rel))))))
    {:issues @issues :errors @errors}))

(defn- canonical-gap-ref
  "Canonical key for a known-gap reference. Returns the keyword name (string)
   when the ref is an id-like keyword or string, else nil (free-text prose)."
  [ref]
  (when (or (keyword? ref) (string? ref))
    (let [s (if (keyword? ref) (name ref) ref)]
      (when (re-matches #"[a-z][a-z0-9-]*" s)
        s))))

(defn check-known-gaps [path concept known-gaps-by-id known-gap-ids errors]
  (let [refs (:concept/known-gaps concept)
        refs (if (keyword? refs) [refs] refs)]
    (when refs
      (when-not (vector? refs)
        (swap! errors conj (str path " :concept/known-gaps must be a vector")))
      (let [refs (vec (if (vector? refs) refs [refs]))
            id-refs (keep canonical-gap-ref refs)
            ;; known-gap-ids is a set of keyword-name strings
            missing (set/difference (set id-refs) known-gap-ids)]
        (doseq [m (sort missing)]
          (swap! errors conj (str path " references unknown known-gap :issue/id " (pr-str m)
                                  " — no such gap exists under data/known-gaps/")))
        (doseq [r id-refs]
          (let [issue (get known-gaps-by-id r)]
            (when issue
              (let [missing-keys (set/difference known-gap-required-keys (set (keys issue)))]
                (when (seq missing-keys)
                  (swap! errors conj (str path " known-gap " (pr-str r)
                                          " is missing canonical keys " (pr-str missing-keys))))))))))))

(defn check-evidence [path concept errors]
  (when (= :use-case (:concept/type concept))
    (let [evidence (:concept/evidence concept)]
      (cond
        (nil? evidence)
        (swap! errors conj (str path " :use-case requires :concept/evidence"))

        (not (map? evidence))
        (swap! errors conj (str path " :concept/evidence must be a map"))

        :else
        (do
          (when-not (= evidence-keys (set (keys evidence)))
            (swap! errors conj (str path " :concept/evidence must contain exactly " evidence-keys)))
          (doseq [key evidence-keys]
            (when-not (set? (get evidence key))
              (swap! errors conj (str path " :concept/evidence " key " must be a set")))))))))

(defn run-validation []
  (println "▶ concepts:validate\n")
  (println "  Parsing registry...")
  (let [[registry reg-err] (parse-edn registry-path)]
    (when reg-err
      (println "    FAIL" (.getPath registry-path) "-" reg-err "\n\nVALIDATION FAILED")
      (System/exit 1))
    (let [reg-entries (:concepts registry)
          reg-by-id (into {} (map (fn [e] [(:concept/id e) e]) reg-entries))
          errors (atom [])
          files-ok (atom 0)
          loaded-concepts (atom [])
          {kg-issues :issues kg-errors :errors} (load-known-gap-registry)
          known-gaps-by-id (into {} (map (fn [i] [(name (:issue/id i)) i]) kg-issues))
          known-gap-ids (set (keys known-gaps-by-id))
          kg-dup-ids (set (for [[id freq] (frequencies (map #(name (:issue/id %)) kg-issues))
                                :when (> freq 1)] id))]
      (println "    OK" (count reg-entries) "entries")
      (println "  Checking registry...")
      (doseq [[cid entry] reg-by-id]
        (let [f (io/file (:concept/file entry))]
          (when-not (.exists f)
            (swap! errors conj (str "registry entry " cid " references " (:concept/file entry) " but file not found"))
            (println "    FAIL registry entry" cid "file not found:" (:concept/file entry)))))
      (let [ids (map :concept/id reg-entries)
            dups (set (for [[id freq] (frequencies ids) :when (> freq 1)] id))]
        (doseq [id dups]
          (swap! errors conj (str "duplicate concept ID " id " in registry"))
          (println "    FAIL duplicate concept ID" id)))
      (doseq [entry reg-entries]
        (let [ps (:concept/protocols entry)]
          (when ps
            (doseq [p ps]
              (when-not (known-protocols p)
                (swap! errors conj (str "entry " (:concept/id entry) " unknown protocol " p))
                (println "    FAIL entry" (:concept/id entry) "unknown protocol" p))))))
      (println "  Parsing known-gaps registry...")
      (doseq [e kg-errors]
        (swap! errors conj e)
        (println "    FAIL" e))
      (println "    OK" (count kg-issues) "issues across" (count (distinct (map :known-gap/file kg-issues))) "files")
      (doseq [id (sort kg-dup-ids)]
        (swap! errors conj (str "duplicate known-gap :issue/id " (pr-str id) " across data/known-gaps/"))
        (println "    FAIL duplicate known-gap :issue/id" id))
      (println "  Parsing concept files...")
      (doseq [f (sort (edn-files concept-root))]
        (when-not (= (.getName f) "registry.edn")
          (let [rel (str (.relativize (.toPath (.getCanonicalFile concept-root)) (.toPath (.getCanonicalFile f))))
                [data parse-err] (parse-edn f)]
            (if parse-err
              (do (swap! errors conj (str rel ": " parse-err))
                  (println "    FAIL" rel "-" parse-err))
              (let [ctype (:concept/type data)
                         is-standard (contains? standard-concept-types ctype)
                         missing (when is-standard (missing-keys data))]
                 (if missing
                   (do (swap! errors conj (str rel ": missing keys " (pr-str missing)))
                       (println "    FAIL" rel "missing keys:" (pr-str missing)))
                  (let [cid (:concept/id data)
                        ctype (:concept/type data)
                        reg-entry (get reg-by-id cid)]
                    (swap! files-ok inc)
                    (swap! loaded-concepts conj data)
                    (when (nil? reg-entry)
                      (swap! errors conj (str rel ": not registered in registry.edn"))
                      (println "    WARN" rel "not registered"))
                    (when reg-entry
                      (let [expected-file (:concept/file reg-entry)
                            expected-rel (clojure.string/replace-first expected-file "data/concepts/" "")]
                        (when (not= expected-rel rel)
                          (swap! errors conj (str rel ": registry file mismatch, expected " expected-file))
                          (println "    FAIL" rel "registry file mismatch, expected" expected-file)))
                      (let [ft ctype rt (:concept/type reg-entry)]
                        (when (not= ft rt)
                          (swap! errors conj (str rel ": type mismatch, file has " ft ", registry has " rt))
                          (println "    FAIL" rel "type mismatch")))
                      (let [fl (:concept/layer data) rl (:concept/layer reg-entry)]
                        (when (not= fl rl)
                          (swap! errors conj (str rel ": layer mismatch, file has " fl ", registry has " rl))
                          (println "    FAIL" rel "layer mismatch"))))
                    (let [expected-dir (get type-dir-map ctype)]
                      (when (and expected-dir (not (.startsWith rel (str expected-dir "/")))
                                 (not= rel (str expected-dir ".edn")))
                        (swap! errors conj (str rel ": expected in " expected-dir "/ directory for type " ctype))
                        (println "    FAIL" rel "wrong directory for type" ctype "expected" (str expected-dir "/"))))
                    (let [ps (:concept/protocols data)]
                      (doseq [p ps]
                        (when-not (known-protocols p)
                          (swap! errors conj (str rel ": unknown protocol " p))
                          (println "    FAIL" rel "unknown protocol" p))))
                    (check-value-maps-to rel data errors)
                     (check-use-case-contract rel data errors)
                     (check-evidence rel data errors)
                     (check-mapping-statuses rel data errors)
                     (check-out-of-scope rel data errors)
                     (check-known-gaps rel data known-gaps-by-id known-gap-ids errors))))))))
      (println "  Checking related concept references...")
      (doseq [{:keys [from to]} (concepts-registry/missing-related-concepts @loaded-concepts)]
        (swap! errors conj (str "concept " from " references missing related concept " to))
        (println "    FAIL concept" from "references missing related concept" to))
      (println)
      (if (empty? @errors)
        (println "  OK" @files-ok "files, all valid\n\nVALIDATION PASSED")
        (do (println "  ERRORS:" (count @errors))
            (doseq [e @errors] (println "    -" e))
            (println "\nVALIDATION FAILED")
            (System/exit 1))))))

(run-validation)
