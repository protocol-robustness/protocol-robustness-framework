(ns resolver-sim.pro-rata.evm-conformance
  "Executable, expectation-independent verifier for the V2 conformance package."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]

            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation]
            [resolver-sim.pro-rata.evm :as evm]
            [resolver-sim.pro-rata.execution-context :as execution-context]
            [resolver-sim.pro-rata.quantity :as quantity]
            [resolver-sim.pro-rata.target-map :as target-map]))

(def package-path "pro-rata-evm-v2-conformance")
(def positive-paths ["vectors/all-active-60-40.edn"
                     "vectors/aggregate-held-credit-60-40.edn"])
(def rejection-paths ["rejection-vectors/forged-post-state.edn"
                      "rejection-vectors/effect-substitution.edn"
                      "rejection-vectors/retained-body-substitution.edn"])

(defn read-vector [path]
  (edn/read-string (slurp (io/file package-path path))))

(defn- file-digest [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (java.nio.file.Files/readAllBytes
               (.toPath (io/file package-path path)))]
    (format "%064x" (java.math.BigInteger. 1 (.digest digest bytes)))))

(defn- manifest-integrity-valid? [manifest]
  (let [declared (set (map :path (:files manifest)))
        expected-members (set (concat ["SPEC.md"] positive-paths rejection-paths))
        digests-valid? (every? (fn [{:keys [path sha256 normative?]}]
                                 (and normative?
                                      (= sha256 (file-digest path))))
                               (:files manifest))
        package-root (hc/domain-hash :pro-rata-evm-conformance-v1
                                     (dissoc manifest :package/root))]
    (and (= declared expected-members)
         (= (count declared) (count (:files manifest)))
         digests-valid?
         (= package-root (:package/root manifest)))))

(defn- body-map [allocation target-map compiler-semantics]
  {(:allocation/hash allocation) allocation
   (:target-map/root target-map) target-map
   (:effect-compilation-semantics/root compiler-semantics) compiler-semantics})

(defn project-derived [context]
  "Project only reconstructed context data; source and expected values are not capabilities."
  (let [{:keys [artifacts derived]} (select-keys context [:artifacts :derived])]
    (cond-> {:allocation/root (get-in artifacts [:allocation :allocation/hash])
             :target-map/root (get-in artifacts [:target-map :target-map/root])
             :effect-compilation-v3/root (get-in artifacts [:compilation :effect-compilation/root])
             :normalized-effects (:normalized-effects derived)
             :normalized-effects/root (get-in artifacts [:compilation :effects/root])
             :state-before/root (get-in artifacts [:canonical-transition :state-before/root])
             :state-after (:state-after derived)
             :state-after/root (get-in artifacts [:canonical-transition :state-after/root])
             :canonical-transition/root (get-in artifacts [:canonical-transition :canonical-effect-transition/root])
             :pro-rata-evm-v2/root (get-in artifacts [:statement :pro-rata-evm-v2/root])}
      (get-in context [:stage-a :validation :target-map-validation/root])
      (assoc :target-map-validation/root
             (get-in context [:stage-a :validation :target-map-validation/root])))))

(defn build-all-active-context
  "Reconstruct the complete all-active artifact graph from inputs and authority."
  [{:keys [inputs semantic-authority] :as vector}]
  (when-not (= :all-active (:profile vector))
    (throw (ex-info "unsupported conformance profile" {:profile (:profile vector)})))
  (let [allocation (allocation/allocate (:allocation/request inputs))
        target-map-input (:target-map/input inputs)
        target-map (target-map/build-target-map
                    {:allocation-subjects-root (:allocation-subjects/root target-map-input)
                     :scope-root (:scope/root target-map-input)
                     :mapping-profile-root (:mapping-profile/root target-map-input)
                     :targets (:targets target-map-input)})
        compiler-semantics (semantics/build (:compilation/profile inputs))
        compiled (compilation/compile
                  {:allocation allocation
                   :target-map target-map
                   :semantics compiler-semantics
                   :allocation-policy-root (:allocation-policy/root inputs)})
        bodies (body-map allocation target-map compiler-semantics)
        resolve-body #(get bodies %)
        state-before (:state-before inputs)
        reconstructed (evm/reconstruct-v2
                       {:state-before state-before
                        :effect-compilation compiled
                        :resolve-body resolve-body
                        :configuration-root (:configuration/root semantic-authority)})
        artifacts (:artifacts reconstructed)
        retained-inputs (body-map allocation target-map compiler-semantics)
        retained-bodies (merge retained-inputs artifacts
                               {(:effects/root compiled)
                                (:normalized-effects (:derived reconstructed))})]
    {:profile :all-active
     :authority semantic-authority
     :source {:inputs inputs}
     :artifacts (assoc artifacts :allocation allocation :target-map target-map
                       :semantics compiler-semantics)
     :derived (:derived reconstructed)
     :retained-input-bodies retained-inputs
     :retained-bodies retained-bodies
     :reconstruction {:state-before state-before
                      :effect-compilation compiled
                      :authority semantic-authority}
     :stage-a nil}))

(defn derive-all-active
  "Return the positive-vector projection only; expectations are never inputs."
  [vector]
  (project-derived (build-all-active-context vector)))

(defn build-aggregate-stage-a
  "Build only aggregate-specific inputs through V3 compilation."
  [{:keys [inputs]}]
  (let [allocation (allocation/allocate (:allocation/request inputs))
        aggregate-quantity (quantity/build-identity (:aggregate-quantity/input inputs))
        quantity-root (:quantity/root aggregate-quantity)
        target-map (target-map/build-aggregate-target-map
                    (update-in (:target-map/input inputs) [:targets]
                               #(mapv (fn [target] (assoc target :quantity/root quantity-root)) %)))
        descriptor (execution-context/build-descriptor (:adapter/input inputs))
        locations (target-map/build-location-map
                   (assoc (update-in (:native-location/input inputs) [:locations]
                                     #(mapv (fn [location]
                                              (assoc location :quantity/root quantity-root)) %))
                          :adapter-descriptor-root (:adapter/descriptor-root descriptor)))
        validation (target-map/validate-aggregate-target-map
                    {:allocation allocation :target-map target-map
                     :allocation-scope-root (:allocation-scope/root target-map)
                     :aggregate-custody-scope-root (:aggregate-custody-scope/root target-map)
                     :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                     :native-state-before-root (:native-before/root inputs)
                     :native-location-map locations
                     :aggregate-quantity aggregate-quantity
                     :expected-identity
                     (assoc (select-keys aggregate-quantity
                                         [:protocol-instance/root :state-domain/root
                                          :subject/root :quantity-kind :asset/root :scope/root])
                            :mapping/profile target-map/many-to-one-profile)})
        compiler-semantics (semantics/build (:compilation/profile inputs))
        compiled (compilation/compile
                  {:allocation allocation :target-map target-map
                   :semantics compiler-semantics
                   :allocation-policy-root (:allocation-policy/root inputs)
                   :target-map-validation validation
                   :native-location-map locations
                   :aggregate-quantity aggregate-quantity
                   :adapter-descriptor descriptor})]
    {:allocation allocation :target-map target-map :semantics compiler-semantics
     :validation validation :locations locations :aggregate-quantity aggregate-quantity
     :descriptor descriptor :compiled compiled
     :state-before {quantity-root 100N}}))

(defn- retained-input-bodies [{:keys [allocation target-map semantics validation locations
                                      aggregate-quantity descriptor]}]
  {(:allocation/hash allocation) allocation
   (:target-map/root target-map) target-map
   (:effect-compilation-semantics/root semantics) semantics
   (:target-map-validation/root validation) validation
   (:native-location-map/root locations) locations
   (:quantity/root aggregate-quantity) aggregate-quantity
   (:adapter/descriptor-root descriptor) descriptor})

(defn- reconstruct-context [stage-a authority]
  (let [inputs (retained-input-bodies stage-a)
        reconstructed (evm/reconstruct-v2
                       {:state-before (:state-before stage-a)
                        :effect-compilation (:compiled stage-a)
                        :resolve-body #(get inputs %)
                        :configuration-root (:configuration/root authority)})
        artifacts (:artifacts reconstructed)
        retained-bodies (merge inputs artifacts
                               {(:effects/root (:compiled stage-a))
                                (:normalized-effects (:derived reconstructed))})]
    {:artifacts (assoc artifacts :allocation (:allocation stage-a)
                       :target-map (:target-map stage-a)
                       :semantics (:semantics stage-a))
     :derived (:derived reconstructed)
     :retained-input-bodies inputs
     :retained-bodies retained-bodies
     :reconstruction {:state-before (:state-before stage-a)
                      :effect-compilation (:compiled stage-a)
                      :authority authority}}))

(defn build-aggregate-context
  [{:keys [semantic-authority] :as vector}]
  (when-not (= :aggregate-held-credit (:profile vector))
    (throw (ex-info "unsupported conformance profile" {:profile (:profile vector)})))
  (let [stage-a (build-aggregate-stage-a vector)
        reconstructed (reconstruct-context stage-a semantic-authority)]
    (assoc reconstructed :profile :aggregate-held-credit
           :authority semantic-authority
           :source {:inputs (:inputs vector)}
           :stage-a (select-keys stage-a [:validation :locations :aggregate-quantity :descriptor]))))

(defn replay-from-context [context]
  (let [{:keys [state-before effect-compilation authority]} (:reconstruction context)
        inputs (:retained-input-bodies context)
        reconstructed (evm/reconstruct-v2
                       {:state-before state-before
                        :effect-compilation effect-compilation
                        :resolve-body #(get inputs %)
                        :configuration-root (:configuration/root authority)})]
    (assoc reconstructed
           :artifacts (merge (:artifacts context) (:artifacts reconstructed))
           :stage-a (:stage-a context)
           :retained-input-bodies inputs)))

(defn derive-aggregate-held-credit [vector]
  (project-derived (build-aggregate-context vector)))

(defn- root-delta [baseline mutated]
  (into {}
        (map (fn [root]
               [root (if (= (get baseline root) (get mutated root)) :same :changed)]))
        (sort (into #{} (concat (keys baseline) (keys mutated))))))

(defn- mutation-context [context {:mutation/keys [target apply]}]
  (when-not (fn? apply)
    (throw (ex-info "context mutation requires an apply function" {:target target})))
  (case (first target)
    :state-before
    (update-in context (into [:reconstruction :state-before] (rest target)) apply)

    :retained-input-bodies
    (let [[_ body-root & path] target]
      (when-not (contains? (:retained-input-bodies context) body-root)
        (throw (ex-info "mutation target is not a retained reconstruction input"
                        {:target target})))
      (update-in context (into [:retained-input-bodies body-root] path) apply))

    (throw (ex-info "unsupported context mutation target" {:target target}))))

(defn run-context-mutation
  "Run one mutation against the closed reconstruction-input capability.
   Reconstructed descendants in `context` are intentionally never supplied to
   `evm/reconstruct-v2`; they remain baseline observations only."
  [context {:mutation/keys [id class target] :as mutation}]
  (let [baseline (project-derived context)
        mutated-context (mutation-context context mutation)
        {:keys [state-before effect-compilation authority]}
        (:reconstruction mutated-context)
        inputs (:retained-input-bodies mutated-context)]
    (try
      (let [reconstructed (evm/reconstruct-v2
                           {:state-before state-before
                            :effect-compilation effect-compilation
                            :resolve-body #(get inputs %)
                            :configuration-root (:configuration/root authority)})
            observed (project-derived
                      {:artifacts (merge (:artifacts context) (:artifacts reconstructed))
                       :derived (:derived reconstructed)
                       :stage-a (:stage-a context)})]
        {:mutation/id id
         :mutation/class class
         :mutation/target target
         :baseline {:reconstructed-roots baseline}
         :mutated {:reconstructed-roots observed}
         :result :accepted
         :root-delta (root-delta baseline observed)})
      (catch clojure.lang.ExceptionInfo error
        {:mutation/id id
         :mutation/class class
         :mutation/target target
         :baseline {:reconstructed-roots baseline}
         :result :rejected
         :rejection {:classification :semantic-verification-rejected
                     :data (select-keys (ex-data error) [:missing :target])}}))))

(defn compare-derived
  "Compare a derived result to an expected section. This is intentionally
   separate from derivation, so expectations cannot influence it."
  [derived expected]
  (= derived (select-keys expected (keys derived))))

(defn verify-aggregate-vector [vector]
  (let [derived (derive-aggregate-held-credit vector)]
    {:valid? (compare-derived derived (:expected vector))
     :derived derived
     :expected (:expected vector)}))

(defn verify-all-active-vector [vector]
  (let [derived (derive-all-active vector)]
    {:valid? (compare-derived derived (:expected vector))
     :derived derived
     :expected (:expected vector)}))

(defn- valid-rejection? [rejection]
  (and (= "pro-rata-evm-v2-rejection-vector.v1" (:schema rejection))
       (keyword? (:case/id rejection))
       (keyword? (:basis/vector rejection))
       (map? (:mutation rejection))
       (seq (:rehash rejection))
       (= false (get-in rejection [:expected :valid?]))
       (keyword? (get-in rejection [:expected :failure-class]))))

(defn verify-package
  "Verify package membership, positive vectors, and rejection-vector shape.
   This is intentionally fail-closed on the exact declared member set."
  []
  (let [manifest (read-vector "manifest.edn")
        declared (set (map :path (:files manifest)))
        expected-members (set (concat ["SPEC.md"] positive-paths rejection-paths))
        positives (mapv read-vector positive-paths)
        rejections (mapv read-vector rejection-paths)
        positive-results [(verify-all-active-vector (first positives))
                          (verify-aggregate-vector (second positives))]
        ids (map :case/id (concat positives rejections))]
    {:valid? (and (manifest-integrity-valid? manifest)
                  (= declared expected-members)
                  (= (count ids) (count (distinct ids)))
                  (every? :valid? positive-results)
                  (every? valid-rejection? rejections))
     :manifest-integrity? (manifest-integrity-valid? manifest)
     :members? (= declared expected-members)
     :unique-case-ids? (= (count ids) (count (distinct ids)))
     :rejections-valid? (every? valid-rejection? rejections)
     :positive-results positive-results
     :rejection-count (count rejections)}))
