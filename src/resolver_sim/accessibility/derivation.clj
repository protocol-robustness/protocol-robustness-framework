(ns resolver-sim.accessibility.derivation
  "Exact-address access and reproducible-derivation navigation.

   This namespace deliberately stops before semantic validity, authority, and
   currentness. Domain namespaces own canonical identity and derivation logic."
  (:require [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]
            [resolver-sim.pro-rata.canonical-effects :as effects]))

(defn- canonical-root?
  [value]
  (and (string? value) (boolean (re-matches #"(?:sha256:)?[0-9a-f]{64}" value))))

(defn root-ref
  "Construct an exact immutable-artifact address."
  [value]
  (let [schema (:subject/schema value)
        root (:subject/root value)]
    (when-not (and (some? schema) (canonical-root? root))
      (throw (ex-info "Invalid root address" {:ref value})))
    {:ref/kind :root :subject/schema schema :subject/root root}))

(defn state-ref
  "Construct an exact concrete-state-snapshot address."
  [value]
  (let [type (:state/type value)
        root (:state/root value)]
    (when-not (and (some? type) (canonical-root? root))
      (throw (ex-info "Invalid state address" {:ref value})))
    {:ref/kind :state :state/type type :state/root root}))

(defn address? [value]
  (case (:ref/kind value)
    :root (try (root-ref value) true (catch Exception _ false))
    :state (try (state-ref value) true (catch Exception _ false))
    false))

(defmulti address-of
  "Return the domain-canonical address for `candidate` at an expected address.
   Methods are intentionally selected by the expected address, not by generic
   hashing. Domains must opt in with their canonical identity constructor."
  (fn [expected _candidate]
    [(:ref/kind expected)
     (or (:subject/schema expected) (:state/type expected))]))

(defmethod address-of [:root fixed/schema]
  [_ candidate]
  (root-ref {:subject/schema fixed/schema
             :subject/root (fixed/chunk-set-root candidate)}))

(defmethod address-of [:root "canonical-effect-transition.v1"]
  [_ candidate]
  (root-ref {:subject/schema "canonical-effect-transition.v1"
             :subject/root (effects/transition-root candidate)}))

(defmethod address-of [:root effects/effect-schema]
  [_ candidate]
  (root-ref {:subject/schema effects/effect-schema
             :subject/root (effects/effect-root candidate)}))

(defmethod address-of [:state effects/state-schema]
  [_ candidate]
  (state-ref {:state/type effects/state-schema
              :state/root (effects/state-root candidate)}))

(defmethod address-of :default
  [expected _candidate]
  (throw (ex-info "No canonical identity constructor is registered for address"
                  {:reason :unsupported-address-domain :ref expected})))

(defn retrieve
  "Retrieve a candidate for an exact address. `resolver` is a function from an
   exact address to a candidate. Retrieval establishes neither identity nor
   semantic validity."
  [resolver address]
  (when-not (address? address)
    (throw (ex-info "retrieve requires an exact address" {:ref address})))
  (when-not (fn? resolver)
    (throw (ex-info "retrieve requires a resolver function" {:resolver resolver})))
  (resolver address))

(defn verify-ref
  "Verify only that `candidate` reconstructs `address` using its registered
   domain identity constructor. It makes no semantic, authority, or currentness
   claim."
  [address candidate]
  (cond
    (not (address? address))
    {:status :invalid :reason :invalid-address :subject/ref address}

    (nil? candidate)
    {:status :invalid :reason :missing-candidate :subject/ref address}

    :else
    (try
      (let [reconstructed (address-of address candidate)]
        {:status (if (= address reconstructed) :valid :invalid)
         :reason (when-not (= address reconstructed) :address-mismatch)
         :subject/ref address
         :reconstructed/ref reconstructed})
      (catch clojure.lang.ExceptionInfo error
        {:status :unsupported
         :reason (:reason (ex-data error))
         :subject/ref address
         :findings [(ex-data error)]}))))

(defmulti basis-of
  "Read a normalized reproduction basis from a derived value. It neither
   retrieves nor verifies inputs, and must not infer uncommitted provenance."
  (fn [artifact]
    (cond
      (= fixed/schema (:chunk-set/schema artifact)) :fixed-chunk-set
      (= effects/compilation-schema (:schema-version artifact)) :pro-rata-effects
      (= "canonical-effect-transition.v1" (:schema-version artifact)) :state-transition
      :else :unknown)))

(defmethod basis-of :fixed-chunk-set
  [artifact]
  {:derivation/kind :fixed-chunk-set
   :derivation/reproducible? true
   :derivation/output (root-ref {:subject/schema fixed/schema
                                 :subject/root (:chunk-set/root artifact)})
   :derivation/inputs
   [{:input/kind :ref :input/role :execution-plan
     :ref (root-ref {:subject/schema :benchmark/execution-plan
                     :subject/root (:execution-plan/root artifact)})}
    {:input/kind :parameter :input/role :chunk-size
     :value (:chunk-size artifact)}
    {:input/kind :ref :input/role :sensitivity
     :ref (root-ref {:subject/schema :benchmark/sensitivity
                     :subject/root (:sensitivity/root artifact)})}
    {:input/kind :ref :input/role :executable-distribution
     :ref (root-ref {:subject/schema :benchmark/executable-distribution
                     :subject/root (:executable-distribution/root artifact)})}]})

(defmethod basis-of :pro-rata-effects
  [artifact]
  {:derivation/kind :pro-rata-effects
   :derivation/reproducible? false
   :derivation/contract-gap :uncommitted-target-mapping
   :derivation/output (root-ref {:subject/schema effects/effect-schema
                                 :subject/root (:effects/root artifact)})
   :derivation/inputs
   [{:input/kind :ref :input/role :realized-allocation
     :ref (root-ref {:subject/schema :pro-rata/realized-allocation
                     :subject/root (:realized-allocation/root artifact)})}
    {:input/kind :parameter :input/role :effect-compilation-semantics
     :value (:effect-compilation-semantics/root artifact)}]})

(defmethod basis-of :state-transition
  [artifact]
  {:derivation/kind :canonical-effects-state-transition
   :derivation/reproducible? true
   :derivation/output (state-ref {:state/type effects/state-schema
                                  :state/root (:state-after/root artifact)})
   :derivation/inputs
   [{:input/kind :ref :input/role :state-before
     :ref (state-ref {:state/type effects/state-schema
                      :state/root (:state-before/root artifact)})}
    {:input/kind :ref :input/role :effects
     :ref (root-ref {:subject/schema effects/effect-schema
                     :subject/root (:effects/root artifact)})}]})

(defmethod basis-of :unknown [artifact]
  {:derivation/kind :unknown
   :derivation/reproducible? false
   :derivation/contract-gap :unrecognized-derived-value
   :artifact artifact})

(defn derivation-kind [artifact] (:derivation/kind (basis-of artifact)))
(defn derived-address [artifact] (:derivation/output (basis-of artifact)))

(defn- ref-inputs [basis]
  (filter #(= :ref (:input/kind %)) (:derivation/inputs basis)))

(defn- input-by-role [basis role]
  (some #(when (= role (:input/role %)) %) (:derivation/inputs basis)))

(defn- verified-inputs [basis resolver]
  (mapv (fn [{:keys [ref] :as input}]
          (assoc input :verification (verify-ref ref (retrieve resolver ref))))
        (ref-inputs basis)))

(defn verify-derived
  "Verify an addressable derived value, then its exact reproduction inputs, and
   finally replay its registered domain derivation. Local report shapes are
   intentional; this is not a universal verification schema."
  [artifact resolver]
  (let [basis (basis-of artifact)
        output (:derivation/output basis)
        subject-candidate (if (= :state (:ref/kind output))
                            (:state-after artifact)
                            artifact)
        subject (verify-ref output subject-candidate)]
    (cond
      (not= :valid (:status subject))
      {:status :invalid :reason :subject-address-invalid :subject/ref output
       :subject-verification subject :basis basis}

      (not (:derivation/reproducible? basis))
      {:status :unsupported :reason (:derivation/contract-gap basis)
       :subject/ref output :subject-verification subject :basis basis}

      :else
      (let [inputs (verified-inputs basis resolver)
            invalid-inputs (filter #(not= :valid (get-in % [:verification :status])) inputs)]
        (if (seq invalid-inputs)
          {:status :invalid :reason :basis-address-invalid :subject/ref output
           :subject-verification subject :basis basis :inputs inputs}
          (try
            (let [recomputed
                  (case (:derivation/kind basis)
                    :fixed-chunk-set
                    (let [plan (retrieve resolver (:ref (input-by-role basis :execution-plan)))
                          chunk-size (:value (input-by-role basis :chunk-size))
                          sensitivity-root (get-in (input-by-role basis :sensitivity) [:ref :subject/root])
                          distribution-root (get-in (input-by-role basis :executable-distribution) [:ref :subject/root])]
                      (fixed/derive-fixed-chunk-set
                       plan {:chunk-size chunk-size
                             :sensitivity-root sensitivity-root
                             :executable-distribution-root distribution-root}))

                    :canonical-effects-state-transition
                    (effects/apply-effects
                     (retrieve resolver (:ref (input-by-role basis :state-before)))
                     (retrieve resolver (:ref (input-by-role basis :effects))))
                    (throw (ex-info "No derivation replay is registered"
                                    {:reason :unsupported-derivation-kind
                                     :derivation/kind (:derivation/kind basis)})))
                  recomputed-ref (address-of output recomputed)]
              {:status (if (= output recomputed-ref) :valid :invalid)
               :reason (when-not (= output recomputed-ref) :derived-address-mismatch)
               :derivation/kind (:derivation/kind basis)
               :subject/ref output :subject-verification subject :basis basis
               :inputs inputs :recomputed/ref recomputed-ref})
            (catch clojure.lang.ExceptionInfo error
              {:status :invalid :reason (:reason (ex-data error)) :subject/ref output
               :subject-verification subject :basis basis :inputs inputs
               :findings [(ex-data error)]})))))))

(defn trace-back
  "Recursively traverse only committed reproduction-basis references. Cycles are
   reported structurally rather than followed indefinitely."
  [artifact resolver]
  (letfn [(walk [value path]
            (let [basis (basis-of value)
                  subject (:derivation/output basis)]
              {:subject subject
               :derivation (:derivation/kind basis)
               :inputs
               (mapv (fn [input]
                       (let [kind (:input/kind input)
                             role (:input/role input)
                             ref (:ref input)
                             parameter-value (:value input)]
                         (if (= :ref kind)
                           (if (contains? path ref)
                             {:role role :subject ref :status :cycle}
                             (let [candidate (retrieve resolver ref)]
                               (if candidate
                                 {:role role :subject ref :trace (walk candidate (conj path ref))}
                                 {:role role :subject ref :status :missing})))
                           {:role role :value parameter-value})))
                     (:derivation/inputs basis))}))]
    (walk artifact #{(derived-address artifact)})))
