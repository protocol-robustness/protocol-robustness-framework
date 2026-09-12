(ns resolver-sim.pro-rata.evm-conformance
  "Executable, expectation-independent verifier for the V2 conformance package."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as binding]
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
  {:allocation/root (get-in context [:artifacts :allocation :allocation/hash])
   :target-map/root (get-in context [:artifacts :target-map :target-map/root])
   :effect-compilation-v3/root (get-in context [:artifacts :compilation :effect-compilation/root])
   :normalized-effects (get-in context [:derived :normalized-effects])
   :normalized-effects/root (get-in context [:artifacts :compilation :effects/root])
   :state-before/root (get-in context [:artifacts :canonical-transition :state-before/root])
   :state-after (get-in context [:derived :state-after])
   :state-after/root (get-in context [:artifacts :canonical-transition :state-after/root])
   :canonical-transition/root (get-in context [:artifacts :canonical-transition :canonical-effect-transition/root])
   :pro-rata-evm-v2/root (get-in context [:artifacts :statement :pro-rata-evm-v2/root])})

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
        artifacts (:artifacts reconstructed)]
    {:profile :all-active
     :authority semantic-authority
     :source {:inputs inputs}
     :artifacts (assoc artifacts :allocation allocation :target-map target-map
                       :semantics compiler-semantics)
     :derived (:derived reconstructed)
     :stage-a nil}))

(defn derive-all-active
  "Return the positive-vector projection only; expectations are never inputs."
  [vector]
  (project-derived (build-all-active-context vector)))

(defn derive-aggregate-held-credit
  "Derive the aggregate profile from complete Stage A semantic bodies."
  [{:keys [inputs semantic-authority] :as vector}]
  (let [allocation (allocation/allocate (:allocation/request inputs))
        aggregate-quantity (quantity/build-identity (:aggregate-quantity/input inputs))
        quantity-root (:quantity/root aggregate-quantity)
        target-input (update-in (:target-map/input inputs) [:targets]
                                (fn [targets]
                                  (mapv #(assoc % :quantity/root quantity-root) targets)))
        target-map (target-map/build-aggregate-target-map target-input)
        descriptor (execution-context/build-descriptor (:adapter/input inputs))
        location-input (update-in (:native-location/input inputs) [:locations]
                                  (fn [locations]
                                    (mapv #(assoc % :quantity/root quantity-root) locations)))
        locations (target-map/build-location-map
                   (assoc location-input :adapter-descriptor-root
                          (:adapter/descriptor-root descriptor)))
        validation (target-map/validate-aggregate-target-map
                    {:allocation allocation
                     :target-map target-map
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
                   :adapter-descriptor descriptor})
        bodies {(:allocation/hash allocation) allocation
                (:target-map/root target-map) target-map
                (:effect-compilation-semantics/root compiler-semantics) compiler-semantics
                (:target-map-validation/root validation) validation
                (:native-location-map/root locations) locations
                (:quantity/root aggregate-quantity) aggregate-quantity
                (:adapter/descriptor-root descriptor) descriptor}
        resolve-body #(get bodies %)
        state-before {quantity-root 100N}
        canonical-transition (effects/transition state-before
                                                 (binding/recompute-effects compiled resolve-body))
        effect-compilation-binding (binding/build compiled canonical-transition)
        statement (evm/build-statement-v2
                   {:state-before state-before
                    :effect-compilation compiled
                    :effect-compilation-binding effect-compilation-binding
                    :resolve-body resolve-body
                    :configuration-root (:configuration/root semantic-authority)})]
    {:allocation/root (:allocation/hash allocation)
     :target-map/root (:target-map/root target-map)
     :target-map-validation/root (:target-map-validation/root validation)
     :effect-compilation-v3/root (:effect-compilation/root compiled)
     :normalized-effects (:effects compiled)
     :normalized-effects/root (:effects/root compiled)
     :state-before/root (:state-before/root canonical-transition)
     :state-after (:state-after canonical-transition)
     :state-after/root (:state-after/root canonical-transition)
     :canonical-transition/root (:canonical-effect-transition/root canonical-transition)
     :pro-rata-evm-v2/root (:pro-rata-evm-v2/root statement)}))

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
