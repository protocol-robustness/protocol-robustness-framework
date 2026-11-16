(ns resolver-sim.protocols.sew.validator-registry
  "Sew protocol validator descriptor catalog and rooted registry.

   Exposes first-class prf/game-theoretic-validator.v1 descriptors for the
   Sew mechanism-property and equilibrium-concept validators defined in
   resolver-sim.protocols.sew.equilibrium.  The executable association
   (validator-id → fn) lives in the equilibrium namespace's validator maps;
   this namespace supplies the semantic descriptors that are the committed
   identity, and composes them into a rooted registry via
   resolver-sim.validation.strategic-registry.

   The descriptors are exposed to the framework through the optional
   ValidatorDescriptorCatalog protocol (implemented on SewProtocol), so
   consumers can compose the Sew validators into application registries and
   route by declared execution horizon without editing framework source.

   This namespace is pure — no I/O, no DB, no side effects."

  (:require [resolver-sim.protocols.sew.equilibrium :as sew-eq]
            [resolver-sim.validation.validator-descriptor :as vd]
            [resolver-sim.validation.strategic-registry :as sr]))

;; ---------------------------------------------------------------------------
;; Descriptor scaffolding
;; ---------------------------------------------------------------------------

(def ^:private default-epistemic-contract
  "Common epistemic contract for single-trace Sew validators: falsification
   over the evaluated trace(s), never a universal claim."
  {:claim-strength :single-trace-proxy
   :universal-claim? false
   :falsification? true
   :limitations [:single-trace
                 :no-universal-claim
                 :bounded-counterfactual-search]})

(defn- descriptor-for
  "Build a prf/game-theoretic-validator.v1 descriptor for a Sew validator."
  [{:keys [id kind validation-class execution-model
           epistemic-contract dependencies]}]
  (merge
   {:validator/schema vd/validator-schema
    :validator/id id
    :validator/version 1
    :validator/kind kind
    :validator/validation-class validation-class
    :validator/epistemic-contract (or epistemic-contract default-epistemic-contract)
    :validator/origin :protocol}
   (when execution-model {:validator/execution-model execution-model})
   (when dependencies {:validator/dependencies dependencies})))

;; ---------------------------------------------------------------------------
;; Mechanism-property descriptor catalog
;; ---------------------------------------------------------------------------

(def mechanism-property-validator-descriptors
  "Semantic descriptors for Sew mechanism-property validators."
  (mapv descriptor-for
        [{:id :individual-rationality
          :kind :mechanism-property
          :validation-class :validation.class/payoff-property}
         {:id :collusion-resistance
          :kind :mechanism-property
          :validation-class :validation.class/deviation-resistance
          :execution-model {:horizon :multi-trace
                            :state-model :deterministic
                            :history-required? true}
          :epistemic-contract {:claim-strength :multi-trace-required
                               :universal-claim? false
                               :falsification? true
                               :limitations [:multi-trace
                                             :no-universal-claim]}}
         {:id :stake-flow-conservation
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity}
         {:id :budget-balance
          :kind :mechanism-property
          :validation-class :validation.class/payoff-property}
         {:id :budget-balance-detailed
          :kind :mechanism-property
          :validation-class :validation.class/payoff-property}
         {:id :force-refund-path-integrity
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity}
         {:id :force-reversal-path-integrity
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity}
         {:id :pending-lifecycle-integrity
          :kind :mechanism-property
          :validation-class :validation.class/algebraic-integrity}
         {:id :resolver-response-deadline
          :kind :mechanism-property
          :validation-class :validation.class/equilibrium}]))

;; ---------------------------------------------------------------------------
;; Equilibrium-concept descriptor catalog
;; ---------------------------------------------------------------------------

(def equilibrium-concept-validator-descriptors
  "Semantic descriptors for Sew equilibrium-concept validators."
  (mapv descriptor-for
        [{:id :subgame-perfect-equilibrium
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :trace-conditioned-epsilon-spe
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :bounded-public-state-epsilon-spe
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :bounded-backward-induction-spe
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :resolver-reputation-spe
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :resolver-reputation-profile-matrix
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}
         {:id :cancellation-dominance
          :kind :equilibrium-concept
          :validation-class :validation.class/deviation-resistance}
         {:id :appeal-decision-rationality
          :kind :equilibrium-concept
          :validation-class :validation.class/equilibrium}]))

;; ---------------------------------------------------------------------------
;; Rooted registry
;; ---------------------------------------------------------------------------

(def all-validator-descriptors
  "All Sew validator descriptors (mechanism properties + equilibrium concepts)."
  (vec (concat mechanism-property-validator-descriptors
               equilibrium-concept-validator-descriptors)))

(def validator-descriptors
  "Exposes all Sew validator descriptors (protocol method body)."
  all-validator-descriptors)

(def sew-validator-registry
  "Rooted validator registry for the Sew protocol.  The executables are the
   validator maps from resolver-sim.protocols.sew.equilibrium — the registry
   root commits only the descriptors (the committed identity), never the fns."
  (sr/build-validator-registry
   :sources [{:origin :protocol :entries all-validator-descriptors}]
   :executables (merge sew-eq/mechanism-property-validators
                       sew-eq/equilibrium-concept-validators)))

(defn resolve-sew-validator
  "Resolve a Sew validator descriptor by id."
  [validator-id]
  (sr/resolve-validator sew-validator-registry validator-id))

(defn resolve-sew-validator-executable
  "Resolve a Sew validator executable fn by id."
  [validator-id]
  (sr/resolve-validator-executable sew-validator-registry validator-id))

(defn build-validator-registry-standalone
  "Build a rooted validator registry from explicit descriptor vectors (used by
   tests and by consumers that want a custom Sew subset).  Each vector entry
   must already be a well-formed descriptor; executables resolve from the
   full Sew registry when present."
  [& {:keys [mech eq]
      :or {mech [] eq []}}]
  (let [descs (vec (concat mech eq))]
    (sr/build-validator-registry
     :sources [{:origin :protocol :entries descs}]
     :executables (merge sew-eq/mechanism-property-validators
                         sew-eq/equilibrium-concept-validators))))