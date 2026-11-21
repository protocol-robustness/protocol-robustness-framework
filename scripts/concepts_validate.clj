(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[resolver-sim.concepts.registry :as concepts-registry]
         '[resolver-sim.concepts.bundles :as concept-bundles]
         '[resolver-sim.hash.reference :as hash-ref]
         '[resolver-sim.io.resource-path :as resource-path])

(def registry-path (str hash-ref/resource-prefix hash-ref/concept-registry-path))
(def concept-bundle-registry-path "resource:concepts/registry.edn")
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

;; Explicit allowlist of top-level :concept/* keys. Any other :concept/*
;; key (e.g. a misspelling like :concept/out-of-scoped) is rejected so
;; unrecognised fields cannot silently pass validation. Captures the full
;; vocabulary currently in use across concept files.
(def allowed-concept-keys
  #{:actions :aggregation :architecture-notes :assumptions :benchmark-concepts :benchmark-domains
    :compatibility :description :display-name :entities :evidence :examples
    :failure-modes :frameworks :guarantees :id :include-values :known-gaps
    :layer :maturity :metrics :name :note :notes :out-of-scope :outcomes
    :primitives :properties :protocols :related :relations :roles
    :security-significance :stakeholder-question :subconcepts :summary
    :status :support-status :type :variants :version :vocabulary})

(def known-id-namespaces
  "Allowlist of :concept/id namespace segments currently in use across the
   catalog, as strings (a keyword's namespace is read back as a string).
   Concept ids use semantic domain namespaces (independent of the type
   directory), so a previously unseen namespace indicates a typo or a stray id
   rather than a normal catalog addition. Add to this set when a new concept
   namespace is genuinely introduced."
  #{"allocation" "assurance" "attestation" "concept" "consensus"
    "decision-quality" "dispute" "ecommerce" "event" "forensic" "framework"
    "funding" "governance" "mechanism" "resolver" "security"
    "spending-account" "verifiable-assurance" "yield"})

(defn check-id-namespace-known
  "Hygiene check: the :concept/id namespace must be a recognised semantic
   domain (see known-id-namespaces), not merely a typo or novel phrase. This
   complements the type->directory and registry-file bindings, which already
   pin ids to their storage location and registry entry."
  [path data errors]
  (let [cid (:concept/id data)
        id-ns (namespace cid)]
    (when (and cid (not (contains? known-id-namespaces id-ns)))
      (swap! errors conj (str path ": :concept/id " cid " has unrecognised namespace "
                              (pr-str id-ns) " (expected one of " known-id-namespaces ")")))))

(defn check-unknown-concept-keys [path concept errors]
  (let [unknown (->> (keys concept)
                     (filter #(= "concept" (namespace %)))
                     (filter #(not (contains? allowed-concept-keys (keyword (name %)))))
                     (sort-by name))]
    (doseq [k unknown]
      (swap! errors conj (str path " has unrecognised :concept/* key " k)))))

(defn edn-files [dir]
  (filter #(and (.endsWith (.getName %) ".edn") (not (.isDirectory %)))
          (file-seq (io/file dir))))

(defn parse-edn [f]
  (try [(if (instance? java.io.File f)
          (edn/read-string (slurp f))
          (let [path (str f)
                resource (if (str/starts-with? path "resource:")
                           path
                           (str hash-ref/resource-prefix path))]
            (resource-path/edn-read resource))) nil]
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
(def mapping-categories #{:concept/roles :concept/entities :concept/actions :concept/outcomes})
(def mapping-value-keys #{:maps-to :mapping/status :mapping/confidence :mapping/note})

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

(defn check-protocol-mapping-files [registry concepts errors]
  (let [entries (:concepts registry)
        concepts-by-id (into {} (map (juxt :concept/id identity) concepts))
        mapping-files (:concept-mapping-files registry)
        duplicate-protocols (->> mapping-files (map :mapping/protocol) frequencies
                                 (keep (fn [[protocol n]] (when (> n 1) protocol))) set)
        registered-protocols (set (map :mapping/protocol mapping-files))
        protocol-mapping-files (set/intersection known-protocols registered-protocols)]
    (doseq [protocol (set/difference known-protocols protocol-mapping-files)]
      (swap! errors conj (str "missing protocol mapping file for " protocol)))
    (doseq [protocol duplicate-protocols]
      (swap! errors conj (str "duplicate protocol mapping file for " protocol)))
    (doseq [mapping-entry mapping-files]
      (let [protocol (:mapping/protocol mapping-entry)
            mapping-file (:mapping/file mapping-entry)
            [mapping-set parse-error] (parse-edn mapping-file)]
        (when-not (or (= :shared protocol) (contains? known-protocols protocol))
          (swap! errors conj (str mapping-file " declares unknown protocol " protocol)))
        (if parse-error
          (swap! errors conj (str mapping-file ": " parse-error))
          (do
            (when-not (= protocol (:mapping-set/protocol mapping-set))
              (swap! errors conj (str mapping-file " declares protocol "
                                      (:mapping-set/protocol mapping-set) " but registry declares " protocol)))
            (when-not (= 1 (:mapping-set/version mapping-set))
              (swap! errors conj (str mapping-file " has unsupported mapping-set version "
                                      (:mapping-set/version mapping-set))))
            (when-not (vector? (:mappings mapping-set))
              (swap! errors conj (str mapping-file " :mappings must be a vector")))
            (let [rows (:mappings mapping-set)
                  duplicate-keys (->> rows
                                      (map (juxt :concept/id :mapping/category :mapping/id))
                                      frequencies
                                      (keep (fn [[k n]] (when (> n 1) k))))]
              (doseq [duplicate duplicate-keys]
                (swap! errors conj (str mapping-file " has duplicate mapping " (pr-str duplicate))))
              (doseq [row rows]
                (let [concept-id (:concept/id row)
                      category (:mapping/category row)
                      mapping-id (:mapping/id row)
                      values (:mapping/values row)
                      concept (get concepts-by-id concept-id)
                      entry (first (filter #(= concept-id (:concept/id %)) entries))
                      target (get-in concept [category mapping-id])]
                  (when-not concept
                    (swap! errors conj (str mapping-file " references unknown concept " concept-id)))
                  (when (and entry (not (contains? (:concept/protocols entry) protocol)))
                    (when-not (= :shared protocol)
                      (swap! errors conj (str mapping-file " maps " concept-id " for unsupported protocol " protocol))))
                  (when (and entry (= :shared protocol) (not= :shared (:concept/owner entry)))
                    (swap! errors conj (str mapping-file " contains protocol-neutral mapping for non-shared concept " concept-id)))
                  (when-not (contains? mapping-categories category)
                    (swap! errors conj (str mapping-file " has invalid mapping category " category)))
                  (when-not target
                    (swap! errors conj (str mapping-file " references unknown mapping " category "/" mapping-id " in " concept-id)))
                  (when-not (map? values)
                    (swap! errors conj (str mapping-file " mapping values must be a map for " concept-id "/" mapping-id)))
                  (when (and (map? values) (seq (set/difference (set (keys values)) mapping-value-keys)))
                    (swap! errors conj
                           (str mapping-file " has unsupported mapping value keys "
                                (pr-str (set/difference (set (keys values)) mapping-value-keys)))))
                  (when (map? values)
                    (check-value-maps-to (str mapping-file "/" concept-id "/" category "/" mapping-id)
                                         values errors)))))))))))

(defn check-concept-bundle-inventory
  "Check the incubating bundle manifests are a complete, exact inventory of
   the current monolithic catalogue. This prepares extraction without making
   the bundle manifests runtime authority yet."
  [registry errors]
  (let [[bundle-registry parse-error] (parse-edn concept-bundle-registry-path)]
    (if parse-error
      (swap! errors conj (str concept-bundle-registry-path ": " parse-error))
      (let [bundle-entries (:concept-bundle-registry/bundles bundle-registry)
            bundle-ids (map :bundle/id bundle-entries)
            bundle-files (map :bundle/manifest bundle-entries)
            allowed-owners (conj known-protocols :shared)
            expected-concepts (set (map (juxt :concept/owner :concept/id :concept/file)
                                        (:concepts registry)))
            expected-mappings (set (map (fn [entry]
                                          [(if (= :shared (:mapping/protocol entry))
                                             :shared (:mapping/protocol entry))
                                           (:mapping/protocol entry) (:mapping/file entry)])
                                        (:concept-mapping-files registry)))
            actual-concepts (atom [])
            actual-mappings (atom [])
            known-bundle-ids (set bundle-ids)]
        (when-not (= 1 (:concept-bundle-registry/version bundle-registry))
          (swap! errors conj "concept bundle registry has unsupported version"))
        (when-not (vector? bundle-entries)
          (swap! errors conj "concept bundle registry :bundles must be a vector"))
        (when-not (= (count bundle-ids) (count (distinct bundle-ids)))
          (swap! errors conj "concept bundle registry contains duplicate bundle IDs"))
        (when-not (= bundle-ids (vec (sort-by str bundle-ids)))
          (swap! errors conj "concept bundle registry must be ordered by bundle ID"))
        (when-not (= (count bundle-files) (count (distinct bundle-files)))
          (swap! errors conj "concept bundle registry contains duplicate manifest paths"))
        (doseq [[entry path] (map vector bundle-entries bundle-files)]
          (let [[manifest manifest-error] (parse-edn path)
                bundle-id (:bundle/id entry)]
            (if manifest-error
              (swap! errors conj (str path ": " manifest-error))
              (do
                (when-not (= "prf.concept-bundle.v1" (:bundle/schema manifest))
                  (swap! errors conj (str path " has unsupported bundle schema")))
                (when-not (= bundle-id (:bundle/id manifest))
                  (swap! errors conj (str path " manifest ID does not match registry entry")))
                (when-not (and (string? (:bundle/version manifest))
                               (= 1 (:bundle/catalogue-schema-version manifest))
                               (keyword? (:bundle/status manifest))
                               (vector? (:bundle/dependencies manifest))
                               (vector? (:bundle/concepts manifest))
                               (vector? (:bundle/mapping-files manifest)))
                  (swap! errors conj (str path " has malformed bundle metadata")))
                (when-not (contains? allowed-owners (:bundle/owner manifest))
                  (swap! errors conj (str path " has an unsupported bundle owner")))
                (when-not (= (:bundle/owner entry) (:bundle/owner manifest))
                  (swap! errors conj (str path " owner does not match the bundle registry entry")))
                (doseq [{dependency-id :bundle/id dependency-version :bundle/version}
                        (:bundle/dependencies manifest)]
                  (when-not (contains? known-bundle-ids dependency-id)
                    (swap! errors conj (str path " depends on unknown bundle " dependency-id)))
                  (when (= dependency-id bundle-id)
                    (swap! errors conj (str path " depends on itself")))
                  (when-not (string? dependency-version)
                    (swap! errors conj (str path " has an unpinned bundle dependency"))))
                (when-not (= (mapv :bundle/id (:bundle/dependencies manifest))
                             (vec (sort-by str (map :bundle/id (:bundle/dependencies manifest)))))
                  (swap! errors conj (str path " dependencies must be ordered by bundle ID")))
                (when-not (= (mapv :concept/id (:bundle/concepts manifest))
                             (vec (sort-by str (map :concept/id (:bundle/concepts manifest)))))
                  (swap! errors conj (str path " concepts must be ordered by concept ID")))
                (when-not (= (mapv :mapping/protocol (:bundle/mapping-files manifest))
                             (vec (sort-by str (map :mapping/protocol (:bundle/mapping-files manifest)))))
                  (swap! errors conj (str path " mapping files must be ordered by protocol")))
                (doseq [{:keys [concept/id concept/file]} (:bundle/concepts manifest)]
                  (swap! actual-concepts conj
                         [(:bundle/owner manifest) id (str/replace-first file #"^resource:" "")]))
                (doseq [{:keys [mapping/protocol mapping/file]} (:bundle/mapping-files manifest)]
                  (swap! actual-mappings conj
                         [(if (= :shared protocol) :shared protocol) protocol
                          (str/replace-first file #"^resource:" "")]))))))
        (when-not (= expected-concepts (set @actual-concepts))
          (swap! errors conj "concept bundle inventories do not exactly cover registry concepts"))
        (when-not (= expected-mappings (set @actual-mappings))
          (swap! errors conj "concept bundle inventories do not exactly cover registry mapping files"))
        (when-not (= (count @actual-concepts) (count (distinct @actual-concepts)))
          (swap! errors conj "concept bundle inventories contain duplicate concepts"))
        (when-not (= (count @actual-mappings) (count (distinct @actual-mappings)))
          (swap! errors conj "concept bundle inventories contain duplicate mapping files"))))))

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
  (try
    (let [{:keys [registry]} (concepts-registry/load-registry)
          reg-entries (:concepts registry)
          errors (atom [])
          files-ok (atom 0)
          loaded-concepts (atom [])
          {kg-issues :issues kg-errors :errors} (load-known-gap-registry)
          known-gaps-by-id (into {} (map (fn [issue] [(name (:issue/id issue)) issue]) kg-issues))
          known-gap-ids (set (keys known-gaps-by-id))
          kg-dup-ids (set (for [[id freq] (frequencies (map #(name (:issue/id %)) kg-issues))
                                :when (> freq 1)] id))]
      (println "  Checking pinned bundle inventory...")
      (check-concept-bundle-inventory registry errors)
      (let [catalogue (concept-bundles/load-bundles)
            loaded-ids (set (map :concept/id
                                 (:concept-bundle-catalogue/concepts catalogue)))
            registered-ids (set (map :concept/id reg-entries))]
        (when-not (= loaded-ids registered-ids)
          (swap! errors conj "bundle loader output differs from the pinned concept registry"))
        (when-not (= (:concept-bundle-catalogue/root catalogue)
                     (:concept-bundle-catalogue/root
                      (concepts-registry/load-registry)))
          (swap! errors conj "runtime registry root differs from the pinned bundle catalogue"))
        (println "    OK" (count (:concept-bundle-catalogue/bundles catalogue))
                 "bundles; root" (:concept-bundle-catalogue/root catalogue)))
      (println "    OK" (count reg-entries) "registered concepts")
      (let [ids (map :concept/id reg-entries)
            dups (set (for [[id freq] (frequencies ids) :when (> freq 1)] id))]
        (doseq [id dups]
          (swap! errors conj (str "duplicate concept ID " id " in bundle registry"))))
      (doseq [entry reg-entries]
        (let [file (:concept/file entry)
              path (str hash-ref/resource-prefix file)
              owner (:concept/owner entry)
              protocols (:concept/protocols entry)]
          (when-not (resource-path/path-exists? path)
            (swap! errors conj (str "bundle registry references missing concept resource " path)))
          (when-not (or (= :shared owner) (contains? known-protocols owner))
            (swap! errors conj (str "entry " (:concept/id entry) " has invalid owner " owner)))
          (when (and (contains? known-protocols owner) (not (contains? protocols owner)))
            (swap! errors conj (str "entry " (:concept/id entry) " owner is not an applicable protocol")))
          (when (and (= :shared owner) (< (count protocols) 2))
            (swap! errors conj (str "entry " (:concept/id entry) " is shared but applies to fewer than two protocols")))
          (doseq [protocol protocols]
            (when-not (known-protocols protocol)
              (swap! errors conj (str "entry " (:concept/id entry) " has unknown protocol " protocol))))))
      (println "  Parsing known-gaps registry...")
      (doseq [error kg-errors]
        (swap! errors conj error))
      (println "    OK" (count kg-issues) "issues across"
               (count (distinct (map :known-gap/file kg-issues))) "files")
      (doseq [id (sort kg-dup-ids)]
        (swap! errors conj (str "duplicate known-gap :issue/id " (pr-str id) " across data/known-gaps/")))
      (println "  Validating pinned concept resources...")
      (doseq [entry reg-entries]
        (let [file (:concept/file entry)
              path (str hash-ref/resource-prefix file)
              rel (str/replace-first file #"^concepts/definitions/" "")
              [data parse-error] (parse-edn path)]
          (if parse-error
            (swap! errors conj (str path ": " parse-error))
            (let [ctype (:concept/type data)
                  is-standard (contains? standard-concept-types ctype)
                  missing (when is-standard (missing-keys data))]
              (if missing
                (swap! errors conj (str rel ": missing keys " (pr-str missing)))
                (do
                  (swap! files-ok inc)
                  (swap! loaded-concepts conj data)
                  (when-not (= (:concept/id data) (:concept/id entry))
                    (swap! errors conj (str rel ": concept ID differs from bundle registry")))
                  (when-not (= (:concept/type data) (:concept/type entry))
                    (swap! errors conj (str rel ": concept type differs from bundle registry")))
                  (when-not (= (:concept/layer data) (:concept/layer entry))
                    (swap! errors conj (str rel ": concept layer differs from bundle registry")))
                  (when-not (= (:concept/protocols data) (:concept/protocols entry))
                    (swap! errors conj (str rel ": protocol applicability differs from bundle registry")))
                  (let [expected-dir (get type-dir-map ctype)]
                    (when (and expected-dir
                               (not (.startsWith rel (str expected-dir "/")))
                               (not= rel (str expected-dir ".edn")))
                      (swap! errors conj (str rel ": expected in " expected-dir "/ for type " ctype))))
                  (doseq [protocol (:concept/protocols data)]
                    (when-not (known-protocols protocol)
                      (swap! errors conj (str rel ": unknown protocol " protocol))))
                  (check-value-maps-to rel data errors)
                  (check-unknown-concept-keys rel data errors)
                  (check-id-namespace-known rel data errors)
                  (check-use-case-contract rel data errors)
                  (check-evidence rel data errors)
                  (check-mapping-statuses rel data errors)
                  (check-out-of-scope rel data errors)
                  (check-known-gaps rel data known-gaps-by-id known-gap-ids errors)))))))
      (println "  Checking protocol mapping files...")
      (check-protocol-mapping-files registry @loaded-concepts errors)
      (println "  Checking related concept references...")
      (doseq [{:keys [from to]} (concepts-registry/missing-related-concepts @loaded-concepts)]
        (swap! errors conj (str "concept " from " references missing related concept " to)))
      (println)
      (if (empty? @errors)
        (println "  OK" @files-ok "concept resources, all valid\n\nVALIDATION PASSED")
        (do (println "  ERRORS:" (count @errors))
            (doseq [error @errors] (println "    -" error))
            (println "\nVALIDATION FAILED")
            (System/exit 1))))
    (catch Exception e
      (println "  FAIL" (.getMessage e) "\n\nVALIDATION FAILED")
      (System/exit 1))))

(run-validation)
