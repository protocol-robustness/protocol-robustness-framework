(ns resolver-sim.validation.validator-descriptor
  "First-class validator descriptor contract: prf/game-theoretic-validator.v1.

   A descriptor is the semantic identity of a game-theoretic validator,
   independent of the executable function that realises it.  Every extension
   validator (mechanism property, equilibrium concept, deviation generator,
   closed-form check) should carry one so that:

     - it can be rooted (descriptor-root);
     - it can be included in a rooted registry and in valid-application;
     - it can be exposed to auditors and referenced by benchmark evidence;
     - it can be checked for compatibility and epistemic-contract validity;
     - it can be reconstructed independently of the runtime function.

   The executable association (validator-id -> validator-fn) is kept in a
   registry map, never inside the descriptor.  A descriptor is pure data.

   Descriptor shape:

     {:validator/schema        :prf/game-theoretic-validator.v1
      :validator/id            :my-protocol/no-profitable-withholding
      :validator/version       1
      :validator/kind          :deviation-resistance|:mechanism-property
                               |:equilibrium-concept|:deviation-generator
                               |:closed-form
      :validator/validation-class
                               :validation.class/deviation-resistance
      :validator/input-schema-root  sha256-hex | nil
      :validator/output-schema-root sha256-hex | nil
      :validator/epistemic-contract
                               {:claim-strength     :bounded-falsification
                                :universal-claim?   false
                                :falsification?     true
                                :parameter-domain   ... (optional)
                                :strategy-domain    ... (optional)
                                :utility-model      ... (optional)
                                :information-model  ... (optional)
                                :limitations        [...]}
      :validator/dependencies  [{:kind :utility-model :id :sew/...} ...]
      :validator/execution-model
                               {:horizon :single-trace|:multi-epoch
                                :state-model ...
                                :history-required? bool}
      :validator/origin        :framework|:protocol|:researcher|:benchmark|:auditor
      :validator/description   \"optional human description\"

   Descriptor rooting uses the authoritative resolver-sim.hash.canonical
   domain-hash implementation with a string domain tag (the same approach
   intent contracts use), so the root is stable and reproducible.

   This namespace is pure — no I/O, no DB, no side effects."

  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.validation.classes :as classes]))

;; ---------------------------------------------------------------------------
;; Schema constants
;; ---------------------------------------------------------------------------

(def validator-schema
  "Canonical schema identifier for a game-theoretic validator descriptor."
  :prf/game-theoretic-validator.v1)

(def validator-schema-tag
  "String domain tag for descriptor rooting (kept stable; never changed)."
  "PRF_GAME_THEORETIC_VALIDATOR_V1")

(def validator-registry-schema-tag
  "String domain tag for validator-registry rooting."
  "PRF_GAME_THEORETIC_VALIDATOR_REGISTRY_V1")

(def validator-kinds
  "Allowed :validator/kind values."
  #{:mechanism-property
    :equilibrium-concept
    :deviation-resistance
    :deviation-generator
    :closed-form})

(def validator-origins
  "Allowed :validator/origin values.  Origin does NOT determine trust;
   trust comes from descriptor + code identity + assumptions + execution
   + evidence + authority/assurance policy."
  #{:framework :protocol :researcher :benchmark :auditor})

(def validator-horizons
  "Allowed :validator/execution-model :horizon values.
   Execution horizon is first-class: dispatch can follow declared
   requirements instead of whichever dispatcher the evaluator lives behind."
  #{:single-transition :single-trace :multi-trace :multi-epoch})

(def validator-dependency-kinds
  "Allowed :kind values in :validator/dependencies entries."
  #{:utility-model :deviation-contract :state-projection :time-model
    :payoff-model :information-model :monitoring-model :validator
    :schema})

(def validator-descriptor-keys
  "Closed set of keys a validator descriptor may carry.  Unknown keys are
   rejected so that 'knowing the schema means knowing what was committed'."
  #{:validator/schema
    :validator/id
    :validator/version
    :validator/kind
    :validator/validation-class
    :validator/input-schema-root
    :validator/output-schema-root
    :validator/epistemic-contract
    :validator/dependencies
    :validator/execution-model
    :validator/origin
    :validator/description
    :validator/root})

(def required-descriptor-keys
  "Keys that MUST be present on every validator descriptor."
  #{:validator/schema
    :validator/id
    :validator/version
    :validator/kind
    :validator/validation-class
    :validator/epistemic-contract})

(def epistemic-contract-required-keys
  "Keys that MUST be present in :validator/epistemic-contract."
  #{:claim-strength :universal-claim? :falsification? :limitations})

;; ---------------------------------------------------------------------------
;; Descriptor validation
;; ---------------------------------------------------------------------------

(defn validate-epistemic-contract
  "Validate an epistemic-contract map.  Returns a vector of error strings
   (empty means valid).

   An epistemic contract is compulsory for every validator so that a bounded
   empirical check can never be silently surfaced alongside a theorem-backed
   result (claim-strength inflation prevention)."
  [ec]
  (if-not (map? ec)
    [":validator/epistemic-contract must be a map"]
    (into []
          (concat
           (keep (fn [k]
                   (when-not (contains? ec k)
                     (str "epistemic-contract missing required key " k)))
                 epistemic-contract-required-keys)
           (when (contains? ec :universal-claim?)
             (when-not (boolean? (:universal-claim? ec))
               ["epistemic-contract :universal-claim? must be boolean"]))
           (when (contains? ec :falsification?)
             (when-not (boolean? (:falsification? ec))
               ["epistemic-contract :falsification? must be boolean"]))
           (when (contains? ec :limitations)
             (when-not (sequential? (:limitations ec))
               ["epistemic-contract :limitations must be sequential"]))))))

(defn- validate-dependencies
  "Validate :validator/dependencies (optional). Returns error vector."
  [deps]
  (if-not (sequential? deps)
    [":validator/dependencies must be sequential"]
    (into []
          (keep (fn [d]
                  (cond
                    (not (map? d))
                    "dependency entry must be a map"

                    (not (contains? d :kind))
                    "dependency entry missing :kind"

                    (not (contains? validator-dependency-kinds (:kind d)))
                    (str "dependency kind " (:kind d)
                         " not in " (sort validator-dependency-kinds))

                    (not (contains? d :id))
                    "dependency entry missing :id"

                    :else nil))
                deps))))

(defn- validate-execution-model
  "Validate :validator/execution-model (optional). Returns error vector."
  [em]
  (if-not (map? em)
    [":validator/execution-model must be a map"]
    (into []
          (concat
           (when-not (contains? em :horizon)
             ["execution-model missing required key :horizon"])
           (when (and (contains? em :horizon)
                      (not (contains? validator-horizons (:horizon em))))
             [(str "execution-model :horizon " (:horizon em)
                   " not in " (sort validator-horizons))])
           (when (and (contains? em :history-required?)
                      (not (boolean? (:history-required? em))))
             ["execution-model :history-required? must be boolean"])))))

(defn validate-descriptor
  "Validate a validator descriptor map.  Returns a vector of error strings
   (empty means the descriptor is well-formed and self-consistent).

   Checks:
     - required keys present;
     - schema matches :prf/game-theoretic-validator.v1;
     - :validator/kind is recognised;
     - :validator/validation-class is a framework-recognised class
       (the class ladder is framework-governed — extensions refine, they do
       not extend class-order);
     - :validator/version is a positive integer;
     - :validator/epistemic-contract is present and well-formed;
     - :validator/dependencies and :validator/execution-model are well-formed;
     - no unknown keys (closed shape)."
  [d]
  (if-not (map? d)
    ["validator descriptor must be a map"]
    (let [unknown (set/difference (set (keys d)) validator-descriptor-keys)
          missing (vec (remove #(contains? d %) required-descriptor-keys))]
      (into []
            (concat
             (map (fn [k] (str "missing required key " k)) missing)
             (when (seq unknown)
               [(str "unknown descriptor keys: " (sort unknown))])
             (when (and (contains? d :validator/schema)
                        (not= validator-schema (:validator/schema d)))
               [(str "unsupported :validator/schema "
                     (:validator/schema d)
                     "; expected " validator-schema)])
             (when (and (contains? d :validator/kind)
                        (not (contains? validator-kinds (:validator/kind d))))
               [(str ":validator/kind " (:validator/kind d)
                     " not in " (sort validator-kinds))])
             (when (and (contains? d :validator/validation-class)
                        (not (contains? (set classes/class-order)
                                        (:validator/validation-class d))))
               [(str ":validator/validation-class "
                     (:validator/validation-class d)
                     " is not a framework-governed class; classes are "
                     (sort classes/class-order))])
             (when (and (contains? d :validator/version)
                        (not (and (integer? (:validator/version d))
                                  (pos? (:validator/version d)))))
               [":validator/version must be a positive integer"])
             (when (contains? d :validator/origin)
               (when-not (contains? validator-origins (:validator/origin d))
                 [(str ":validator/origin " (:validator/origin d)
                       " not in " (sort validator-origins))]))
             (validate-epistemic-contract (:validator/epistemic-contract d))
             (if (contains? d :validator/dependencies)
               (validate-dependencies (:validator/dependencies d))
               [])
             (if (contains? d :validator/execution-model)
               (validate-execution-model (:validator/execution-model d))
               []))))))

(defn valid-descriptor?
  "True when the descriptor has no validation errors."
  [d]
  (empty? (validate-descriptor d)))

(defn validate-descriptor!
  "Validate a descriptor and throw on the first problem."
  [d]
  (let [errors (validate-descriptor d)]
    (when (seq errors)
      (throw (ex-info "Invalid game-theoretic validator descriptor"
                      {:errors (vec errors)
                       :validator/id (:validator/id d)})))
    d))

;; ---------------------------------------------------------------------------
;; Rooting
;; ---------------------------------------------------------------------------

(defn committed-descriptor
  "Return the descriptor's committed identity: every descriptor field except
   the self-referential :validator/root (which is derived, never supplied)."
  [d]
  (dissoc d :validator/root))

(defn descriptor-root
  "Deterministic content root of a validator descriptor (sha256 hex).
   Root is domain-separated and stable — two descriptors with identical
   committed fields produce identical roots regardless of map ordering."
  [d]
  (hc/domain-hash validator-schema-tag (committed-descriptor d)))

(defn with-root
  "Return the descriptor with its derived :validator/root attached."
  [d]
  (assoc d :validator/root (descriptor-root d)))

;; ---------------------------------------------------------------------------
;; Horizon / execution-model classification
;; ---------------------------------------------------------------------------

(def single-trace-horizons
  "Horizons satisfiable by a single-trace terminal projection."
  #{:single-transition :single-trace})

(def multi-epoch-horizons
  "Horizons requiring multiple epochs (multi-epoch or stochastic state)."
  #{:multi-epoch})

(def multi-trace-horizons
  "Horizons requiring multiple independent traces (not a single trace)."
  #{:multi-trace})

(defn descriptor-horizon
  "Return the declared execution horizon of a descriptor, or nil when the
   descriptor carries no :validator/execution-model."
  [d]
  (get-in d [:validator/execution-model :horizon]))

(defn single-trace-satisfiable?
  "True when the descriptor's declared horizon can be satisfied by a
   single-trace terminal projection.  A descriptor with no execution model
   is treated as single-trace (backward compatible)."
  [d]
  (let [h (descriptor-horizon d)]
    (or (nil? h)
        (contains? single-trace-horizons h))))

(defn multi-epoch-required?
  "True when the descriptor declares a horizon requiring multi-epoch evidence."
  [d]
  (contains? multi-epoch-horizons (descriptor-horizon d)))

(defn multi-trace-required?
  "True when the descriptor declares a horizon requiring multiple traces."
  [d]
  (contains? multi-trace-horizons (descriptor-horizon d)))