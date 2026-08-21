(ns resolver-sim.resubmission.genesis
  "Canonical resubmission-chain-genesis.v1: the declared source of semantic
   truth for a resubmission chain, replacing the implicit genesis that
   `new-chain` previously constructed only in constructor arguments.

   Stage 1 (this namespace): declares the family, initial
   authority/configuration, and initial state as a canonical artifact with
   validation, projection, and hashing. Does not yet introduce governance
   authorization (Stage 3).

   Key distinction: validated ≠ authoritative.

   validate-resubmission-chain-genesis establishes:
     - well-formedness (closed-shape, schema)
     - internal consistency (chain-id derivation, initial-state/root)
     - canonical rooting (sha256 references, domain hashes)

   It does NOT establish governance authorization. A locally constructed or
   attacker-chosen genesis that is internally consistent will pass validation.
   Authority binding enters through a separate, governance-authorized genesis
   authorization artifact (Stage 3, design §15).

   Architectural separation (design §6, §15):

     genesis artifact     — declared, canonical, content-addressed
         |                       (what defines the chain's semantic origin)
         | realize
         v
     TransactionStore    — local runtime construction
         |
         | transact!
         v
     state transitions

   `new-chain` (convenience/local realization) and
   `new-chain-from-genesis` (canonical validated realization) both construct
   a TransactionStore from a genesis. Neither exercises governance authority
   to create a chain. The provenance distinction is:

     genesis-of(store)
       = nil                 → undeclared provenance (legacy/transitional)
       = locally-derived G   → convenience path, not validated
       = validated G         → canonical validated realization

   In all cases, genesis-of conveys declaration/provenance only, NOT
   authorization. Authority must be evidenced by a separate verifiable
   artifact."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.transition :as transition]))

;; ── Schema constants ──────────────────────────────────────────────────

(def ^:const resubmission-chain-genesis-schema
  "Schema identifier for resubmission-chain-genesis.v1."
  "resubmission-chain-genesis.v1")

(def ^:const resubmission-chain-configuration-schema
  "Schema identifier for resubmission-chain-configuration.v1."
  "resubmission-chain-configuration.v1")

;; ── Configuration validation ──────────────────────────────────────────

(defn validate-resubmission-chain-configuration
  "Strict, closed-shape validator for resubmission-chain-configuration.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong configuration/schema;
     - unknown top-level keys (closed shape);
     - missing required keys;
     - authority keys that are not strings or nil.

   nil keys are accepted as explicit absence — the field is present (the
   configuration commits to 'no authority configured'), not omitted."
   [config]
   (let [errors (atom [])
         report! (fn [msg] (swap! errors conj msg))
         expect (set hc/resubmission-chain-configuration-fields)]
     (when-not (map? config)
       (report! "resubmission-chain-configuration must be a map"))
     (when (map? config)
       (when-not (= resubmission-chain-configuration-schema
                    (get config :configuration/schema))
         (report! (str "configuration/schema must be "
                       resubmission-chain-configuration-schema
                       ", got " (pr-str (:configuration/schema config)))))
       (let [have (set (keys config))
             extra (set/difference have expect)
             missing (set/difference expect have)]
         (when (seq extra)
           (report! (str "unknown configuration keys: " (sort extra))))
         (when (seq missing)
           (report! (str "missing configuration keys: " (sort missing))))
         (doseq [f [:disposition-authority/public-key
                    :receipt-authority/public-key]
                 :let [v (get config f)]
                 :when (contains? (set (keys config)) f)]
           (when-not (or (nil? v) (string? v))
             (report! (str f " must be a string or nil, got "
                           (some-> v class .getName)))))))
     {:valid? (empty? @errors) :errors (vec @errors)}))

(defn resubmission-chain-configuration-valid?
  "Quick boolean structural validity check for resubmission-chain-configuration.v1."
  [config]
  (:valid? (validate-resubmission-chain-configuration config)))

(defn resubmission-chain-configuration-root
  "Compute the canonical SHA-256 root of a
   resubmission-chain-configuration.v1.

   Validates strict closed-shape first (fail-closed); a caller-supplied
   expected root may be passed as the second argument and is rejected on
   mismatch rather than trusted."
  ([config]
   (let [v (validate-resubmission-chain-configuration config)]
     (when-not (:valid? v)
       (throw (ex-info "resubmission-chain-configuration.v1 is invalid"
                       {:type :configuration/invalid
                        :schema resubmission-chain-configuration-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
     (hc/domain-hash :prf-resubmission-chain-configuration-v1
                     (hc/project-resubmission-chain-configuration config :prf-resubmission-chain-configuration-v1))))
  ([config expected]
   (let [computed (resubmission-chain-configuration-root config)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied configuration root does not match computed root"
                       {:type :configuration/root-mismatch
                         :declared expected
                          :computed computed})))
      computed)))


;; ── Genesis derivation ────────────────────────────────────────────────

(defn- configuration-from-keys
  "Build a resubmission-chain-configuration.v1 from authority key hex strings
   (nil when absent)."
  [disposition-public-hex receipt-public-hex]
  {:configuration/schema resubmission-chain-configuration-schema
   :disposition-authority/public-key disposition-public-hex
   :receipt-authority/public-key receipt-public-hex})

(defn initial-state-root
  "Compute the canonical root of the initial (empty) chain state for a family
   and disposition authority key. This is the G0 state root that the genesis
   commits to.

   The empty state is derived deterministically from family-id and the
   disposition-authority public key, matching transition/empty-state."
  [family-id disposition-public-hex]
  (transition/state-root
   (transition/empty-state family-id disposition-public-hex)))

(defn chain-identity-root
  "Compute the canonical chain-id for a family and its initial configuration.

   chain-id = sha256(domain-hash(prf.resubmission-chain-identity.v1,
                                 {family/id,
                                  disposition-authority/public-key,
                                  receipt-authority/public-key}))

   Derives the persistent chain identity from the immutable genesis identity
   basis — the family-id and the INITIAL authority key set. Deliberately does
   NOT incorporate the genesis root, so authority rotation (Stage 4) can
   change configuration without recomputing chain identity (design §8,
   Interpretation A: chain identity is bound to the genesis authority basis,
   not to the current authority configuration).

   Consequence: family F with initial keys A and family F with initial keys B
   are necessarily distinct chains. This prevents masquerading under a
   different initial trust anchor."
  [family-id configuration]
   (hash-ref/sha256-ref
    (hc/domain-hash :prf-resubmission-chain-identity-v1
                    (hc/project-resubmission-chain-identity
                     {:family/id family-id
                      :disposition-authority/public-key
                      (:disposition-authority/public-key configuration)
                      :receipt-authority/public-key
                      (:receipt-authority/public-key configuration)}
                     :prf-resubmission-chain-identity-v1))))

(defn ->genesis
  "Construct a resubmission-chain-genesis.v1 from legacy new-chain parameters.

   Convenience construction: derives chain/id from family-id + configuration
   (identity basis), initial-state/root from the empty state (G0), and
   inlines the configuration (self-contained, realizable).

   The result is a well-formed genesis map but is NOT validated or
   authorized. Callers that need structural validation should use
   validate-resubmission-chain-genesis; callers that need governance
   authorization should use a future genesis-authorization binding."
  ([family-id] (->genesis family-id nil))
  ([family-id disposition-public-hex]
   (->genesis family-id disposition-public-hex nil))
  ([family-id disposition-public-hex receipt-public-hex]
   (let [configuration (configuration-from-keys disposition-public-hex
                                                receipt-public-hex)
         chain-id (chain-identity-root family-id configuration)
         state-root (initial-state-root family-id disposition-public-hex)]
     {:genesis/schema resubmission-chain-genesis-schema
      :chain/id chain-id
      :family/id family-id
      :configuration configuration
      :initial-state/root state-root})))

;; ── Genesis validation ────────────────────────────────────────────────

(def ^:private genesis-root-fields
  "sha256 reference fields of resubmission-chain-genesis.v1."
  #{:chain/id :initial-state/root})

(defn validate-resubmission-chain-genesis
  "Strict, closed-shape validator for resubmission-chain-genesis.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong genesis/schema;
     - unknown top-level keys (closed shape);
     - missing required keys;
     - configuration that fails resubmission-chain-configuration.v1 validation;
     - :chain/id that is not a valid sha256 reference;
     - :family/id that is nil;
     - :initial-state/root that is not a valid sha256 reference;
     - :initial-state/root mismatch against the computed empty-state root
       for the declared family and disposition authority."
   [genesis]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))
        expect (set hc/resubmission-chain-genesis-fields)]
    (when-not (map? genesis)
      (report! "resubmission-chain-genesis must be a map"))
    (when (map? genesis)
      (when-not (= resubmission-chain-genesis-schema
                   (get genesis :genesis/schema))
        (report! (str "genesis/schema must be "
                      resubmission-chain-genesis-schema
                      ", got " (pr-str (:genesis/schema genesis)))))
      (let [have (set (keys genesis))
            extra (set/difference have expect)
            missing (set/difference expect have)]
        (when (seq extra)
          (report! (str "unknown top-level keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing required keys: " (sort missing))))
       ;; chain/id and initial-state/root must be valid sha256 references
       (doseq [f genesis-root-fields
               :let [v (get genesis f)]]
         (cond
           (nil? v) (report! (str f " must not be nil"))
           (not (hash-ref/valid-sha256-ref? v))
           (report! (str f " must be a valid sha256 reference, got "
                         (pr-str v)))))
        ;; family/id must not be nil
        (when (nil? (:family/id genesis))
          (report! "family/id must not be nil"))
       ;; configuration must be a valid configuration
        (let [cfg (:configuration genesis)
              cv (validate-resubmission-chain-configuration cfg)]
          (when-not (:valid? cv)
            (doseq [e (:errors cv)]
              (report! (str "configuration: " e)))))
        ;; verify chain-id matches its derivation
        (when (and (hash-ref/valid-sha256-ref? (:chain/id genesis))
                   (some? (:family/id genesis)))
          (let [cfg (:configuration genesis)
                expected-cid (chain-identity-root (:family/id genesis) cfg)]
            (when (and (hash-ref/valid-sha256-ref? expected-cid)
                       (not= expected-cid (:chain/id genesis)))
              (report! (str ":chain/id does not match family/configuration derivation: "
                            (pr-str (:chain/id genesis)) " vs " (pr-str expected-cid))))))
        ;; verify initial-state/root matches computed G0
        (when (and (hash-ref/valid-sha256-ref? (:initial-state/root genesis))
                   (some? (:family/id genesis))
                   (map? (:configuration genesis)))
          (let [cfg (:configuration genesis)
                disp-k (:disposition-authority/public-key cfg)
                expected-sr (initial-state-root (:family/id genesis) disp-k)]
            (when (not= expected-sr (:initial-state/root genesis))
              (report! (str ":initial-state/root does not match computed empty-state root: "
                            (pr-str (:initial-state/root genesis))
                            " vs " (pr-str expected-sr))))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn resubmission-chain-genesis-valid?
  "Quick boolean structural validity check for resubmission-chain-genesis.v1."
  [genesis]
  (:valid? (validate-resubmission-chain-genesis genesis)))

;; ── Genesis root ──────────────────────────────────────────────────────

(defn resubmission-chain-genesis-root
  "Compute the canonical SHA-256 root of a resubmission-chain-genesis.v1.

   Validates strict closed-shape first (fail-closed); a caller-supplied
   expected root may be passed as the second argument and is rejected on
   mismatch."
  ([genesis]
   (let [v (validate-resubmission-chain-genesis genesis)]
     (when-not (:valid? v)
       (throw (ex-info "resubmission-chain-genesis.v1 is invalid"
                       {:type :genesis/invalid
                        :schema resubmission-chain-genesis-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
     (hc/domain-hash :prf-resubmission-chain-genesis-v1
                     (hc/project-resubmission-chain-genesis genesis :prf-resubmission-chain-genesis-v1))))
  ([genesis expected]
   (let [computed (resubmission-chain-genesis-root genesis)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied genesis root does not match computed root"
                       {:type :genesis/root-mismatch
                        :declared expected
                         :computed computed})))
      computed)))