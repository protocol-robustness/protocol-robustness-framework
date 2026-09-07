(ns resolver-sim.pro-rata.invocation-publication-binding
  "Rooted correspondence for one pro-rata capability invocation."
  (:require [resolver-sim.benchmark.distributed.executable-distribution :as distribution]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as compilation-binding]
            [resolver-sim.pro-rata.evm :as evm]
            [resolver-sim.pro-rata.protocol-transaction-realization :as realization]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.use-cases.application :as application]))

(def output-schema "pro-rata-capability-output.v1")
(def output-v2-schema "pro-rata-capability-output.v2")
(def binding-schema "pro-rata-invocation-publication-binding.v1")

(def ^:private output-fields
  #{:pro-rata-output/schema :allocation/root :application-policy/root
    :pro-rata-transition/root :canonical-transition/root})

(def ^:private output-v2-fields
  (conj output-fields :effect-compilation-binding/root))

(def ^:private binding-fields
  #{:binding/schema :application/root :capability-binding/root :executable-distribution/root
    :publication-ordering/root})

(def ^:private pro-rata-capability
  {:capability/kind :economics/allocation
   :capability/id :prf/pro-rata-allocation
   :capability/version 1})

(declare output-valid?)

(defn- commitment-root? [value]
  (or (ref/valid-sha256-ref? value)
      (and (string? value) (re-matches #"[0-9a-f]{64}" value))))

(defn output-root [output]
  (let [schema (:pro-rata-output/schema output)
        fields (case schema
                 "pro-rata-capability-output.v1" output-fields
                 "pro-rata-capability-output.v2" output-v2-fields
                 nil)
        domain (case schema
                 "pro-rata-capability-output.v1" :pro-rata-capability-output-v1
                 "pro-rata-capability-output.v2" :pro-rata-capability-output-v2
                 nil)]
    (when-not fields
      (throw (ex-info "unknown pro-rata capability output schema" {:schema schema})))
    (ref/sha256-ref (hc/domain-hash domain (select-keys output fields)))))

(defn binding-root [binding]
  (ref/sha256-ref
   (hc/domain-hash :pro-rata-invocation-publication-binding-v1
                   (select-keys binding binding-fields))))

(defn build-output
  "Build the V1 pro-rata output artifact expected at an invocation's output root."
  [{:keys [allocation pro-rata-application pro-rata-transition canonical-transition]}]
  (let [base {:pro-rata-output/schema output-schema
              :allocation/root (:allocation/hash allocation)
              :application-policy/root (:application-policy/root pro-rata-transition)
              :pro-rata-transition/root (:transition/root pro-rata-transition)
              :canonical-transition/root (:canonical-effect-transition/root canonical-transition)}
        output (assoc base :pro-rata-output/root (output-root base))]
    (when-not (output-valid? output allocation pro-rata-application pro-rata-transition canonical-transition)
      (throw (ex-info "Invalid pro-rata capability output" {:reason :invalid-pro-rata-capability-output})))
    output))

(defn output-valid?
  "Verify the exact output adapter against resolved pro-rata bodies."
  [output allocation pro-rata-application pro-rata-transition canonical-transition]
  (and (= (conj output-fields :pro-rata-output/root) (set (keys output)))
       (= output-schema (:pro-rata-output/schema output))
       (every? commitment-root?
               (vals (select-keys output (disj output-fields :pro-rata-output/schema))))
       (evm/application-valid? pro-rata-application)
       (evm/transition-valid? pro-rata-transition pro-rata-application)
       (= (:allocation/root output) (:allocation/hash allocation))
       (= (:allocation/root output) (:allocation/root pro-rata-transition))
       (= (:application-policy/root output) (:application-policy/root pro-rata-transition))
       (= (:pro-rata-transition/root output) (:transition/root pro-rata-transition))
       (= (:canonical-transition/root output)
          (:canonical-effect-transition/root canonical-transition))
       (= (:state-before/root pro-rata-transition) (:state-before/root canonical-transition))
       (= (:state-after/root pro-rata-transition) (:state-after/root canonical-transition))
       (= (:canonical-effect-transition/root canonical-transition)
          (effects/transition-root canonical-transition))
       (= (:pro-rata-output/root output) (output-root output))))

(defn build-output-v2
  "Build a V2 output that commits a retained-body-verified V2 compilation binding."
  [{:keys [effect-compilation-binding compilation canonical-transition resolve-body] :as resolved}]
  (let [v1 (build-output resolved)
        base (assoc (dissoc v1 :pro-rata-output/root)
                    :pro-rata-output/schema output-v2-schema
                    :effect-compilation-binding/root
                    (:effect-compilation-binding/root effect-compilation-binding))]
    (when-not (and (= "pro-rata-effect-compilation.v3" (:schema-version compilation))
                   (compilation-binding/valid? effect-compilation-binding compilation canonical-transition resolve-body))
      (throw (ex-info "invalid effect compilation binding" {})))
    (assoc base :pro-rata-output/root (output-root base))))

(defn output-v2-valid?
  [output allocation pro-rata-application pro-rata-transition canonical-transition
   compilation effect-compilation-binding resolve-body]
  (and (= "pro-rata-effect-compilation.v3" (:schema-version compilation))
       (= (conj output-v2-fields :pro-rata-output/root) (set (keys output)))
       (= output-v2-schema (:pro-rata-output/schema output))
       (output-valid? (-> output
                          (dissoc :effect-compilation-binding/root :pro-rata-output/root)
                          (assoc :pro-rata-output/schema output-schema
                                 :pro-rata-output/root
                                 (output-root (assoc (dissoc output :effect-compilation-binding/root
                                                             :pro-rata-output/root)
                                                     :pro-rata-output/schema output-schema))))
                      allocation pro-rata-application pro-rata-transition canonical-transition)
       (= (:pro-rata-output/root output) (output-root output))
       (= (:effect-compilation-binding/root output)
          (:effect-compilation-binding/root effect-compilation-binding))
       (compilation-binding/valid? effect-compilation-binding compilation canonical-transition resolve-body)))

(defn- binding-shape-valid? [binding capability-binding descriptor]
  (and (= (conj binding-fields :pro-rata-invocation-publication-binding/root)
          (set (keys binding)))
       (= binding-schema (:binding/schema binding))
       (every? ref/valid-sha256-ref?
               (vals (select-keys binding (disj binding-fields :binding/schema))))
       (= (:pro-rata-invocation-publication-binding/root binding) (binding-root binding))
       (= pro-rata-capability (select-keys descriptor (keys pro-rata-capability)))
       (:valid? (manifest/validate-capability descriptor))
       (= capability-binding
          (application/capability-invocation-binding
           {:capability descriptor
            :input-root (:invocation/input-root capability-binding)
            :output-root (:invocation/output-root capability-binding)
            :evidence-roots (:invocation/evidence-roots capability-binding)}))))

(defn binding-eligible?
  "Verify a pro-rata invocation/publication correspondence from resolved bodies.
  V2 outputs require a retained-body resolver; V1 verification is unchanged."
  [binding {:keys [capability-binding capability-descriptor executable-distribution
                   use-case-application output allocation pro-rata-application pro-rata-transition
                   canonical-transition compilation effect-compilation-binding receipt resolve-body
                   protocol-transaction-realization transition-binding protocol-effect-realization
                   publication-ordering]}]
  (and (binding-shape-valid? binding capability-binding capability-descriptor)
       (= (:application/root binding) (:application/root use-case-application))
       (= (:application/root use-case-application) (application/application-root use-case-application))
       (some #(= (:capability-binding-root %) (:binding/root capability-binding))
             (:application/capability-bindings use-case-application))
       (= (:capability-binding/root binding) (:binding/root capability-binding))
       (= (:executable-distribution/root binding) (:executable-distribution/root executable-distribution))
       (distribution/verify-distribution executable-distribution)
       (case (:pro-rata-output/schema output)
         "pro-rata-capability-output.v1"
         (output-valid? output allocation pro-rata-application pro-rata-transition canonical-transition)
         "pro-rata-capability-output.v2"
         (output-v2-valid? output allocation pro-rata-application pro-rata-transition canonical-transition
                           compilation effect-compilation-binding resolve-body)
         false)
       (= (:invocation/output-root capability-binding) (:pro-rata-output/root output))
       (= (:publication-ordering/root binding) (:transaction-ordering/hash publication-ordering))
       (= ordering/ordering-v3-schema (:transaction-ordering/schema publication-ordering))
       (:valid? (ordering/verify-ordering publication-ordering))
       (= (:transaction/realization-root publication-ordering)
          (ref/sha256-ref (:protocol-transaction-realization/root protocol-transaction-realization)))
       (= (:transaction/application-receipt-root publication-ordering)
          (ref/sha256-ref (:applied-effect-receipt/root receipt)))
       (realization/valid? protocol-transaction-realization transition-binding protocol-effect-realization)
       (= (:canonical-transition/root protocol-transaction-realization)
          (:canonical-transition/root output))
       (publication/conservation-holds? receipt protocol-transaction-realization canonical-transition
                                        compilation effect-compilation-binding output resolve-body)))

(defn binding-valid?
  "An eligible correspondence is authoritative only when its ordering is the
   exact ordering currently committed by the supplied publication head."
  [binding {:keys [publication-store publication-ordering] :as resolved}]
  (and (binding-eligible? binding resolved)
       (publication/published? publication-store publication-ordering)))

(defn build-binding
  "Build a closed correspondence only after resolving and validating every body
   needed to establish invocation -> result -> authoritative publication."
  [{:keys [use-case-application capability-binding executable-distribution publication-ordering]
    :as resolved}]
  (let [base {:binding/schema binding-schema
              :application/root (:application/root use-case-application)
              :capability-binding/root (:binding/root capability-binding)
              :executable-distribution/root (:executable-distribution/root executable-distribution)
              :publication-ordering/root (:transaction-ordering/hash publication-ordering)}
        binding (assoc base :pro-rata-invocation-publication-binding/root
                       (binding-root base))]
    (when-not (binding-eligible? binding resolved)
      (throw (ex-info "Invalid pro-rata invocation/publication correspondence"
                      {:reason :invalid-pro-rata-invocation-publication-binding})))
    binding))
