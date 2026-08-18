(ns resolver-sim.genesis
  "Authoritative PRF canonical contracts, validation, hashing, and fixtures for:

    protocol-genesis.v1
    chain-instance-genesis.v1

  Architectural invariant (frozen):

    protocol-genesis-root     identifies WHAT SEW/PRF means (chain-neutral
                              constitutional identity).
    chain-instance-genesis-root identifies WHERE one concrete EVM execution
                              instance began — its initial authority,
                              configuration, and execution domain.

  Both roots are immutable identities. Later governance/configuration changes
  are descendants and never mutate either genesis identity. PRF SHA-256
  canonical hashing (resolver-sim.hash.canonical) is authoritative; Solidity
  receives the resulting roots as bytes32.

  Canonical boundaries:
    - Closed top-level and nested shapes: unknown keys FAIL rather than being
      silently normalised or ignored.
    - SHA-256 references use the canonical \"sha256:<64 lowercase hex>\" form
      (resolver-sim.hash.reference). Keccak runtime-code references use a
      distinct \"keccak256:<64 lowercase hex>\" form and cannot be confused
      with SHA-256 references.
    - EVM addresses have exactly one canonical textual representation:
      0x + 40 lowercase hex chars, non-zero.
    - Chain IDs are unsigned EVM-domain integers bounded 1 <= id < 2^256."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Schema constants
;; ──────────────────────────────────────

(def ^:const protocol-genesis-schema
  "Schema identifier for protocol-genesis.v1."
  "protocol-genesis.v1")

(def ^:const chain-instance-genesis-schema
  "Schema identifier for chain-instance-genesis.v1."
  "chain-instance-genesis.v1")

(def protocol-genesis-fields
  "Top-level identity fields of protocol-genesis.v1 (re-exported from the
   canonical projections so the validator and projection share one source of
   truth)."
  hc/protocol-genesis-fields)

(def chain-instance-genesis-fields
  "Top-level identity fields of chain-instance-genesis.v1."
  hc/chain-instance-genesis-fields)

;; ──────────────────────────────────────────────────────────────────────────────
;; Canonical reference / field validation helpers
;; ──────────────────────────────────────────────────────────────────────────────

(def evm-address-regex
  #"^0x[0-9a-f]{40}$")

(def ^:private zero-evm-address
  "0x + 40 zero bytes. The zero address is not a canonical deployed identity."
  "0x0000000000000000000000000000000000000000")

(defn valid-evm-address?
  "True when s is a canonical EVM address: exactly 0x + 40 lowercase hex chars,
   non-zero. Mixed-case / EIP-55 checksum form, uppercase 0X, wrong length,
   missing prefix, non-hex, and the zero address are all rejected. Input is
   never silently lowercased."
  [s]
  (and (string? s)
       (re-matches evm-address-regex s)
       (not= s zero-evm-address)))

(def keccak256-ref-regex
  #"^keccak256:[0-9a-f]{64}$")

(defn valid-keccak256-ref?
  "True when s is a canonical keccak256 reference: the exact \"keccak256:\" prefix,
   exactly 32 bytes / 64 lowercase hex chars, no uppercase/mixed-case, no extra
   whitespace. This is intentionally distinct from the SHA-256 reference form, so
   a sha256 reference cannot be supplied in a keccak field."
  [s]
  (and (string? s) (re-matches keccak256-ref-regex s)))

(def ^:private protocol-id-segment
  #"^[a-z0-9][a-z0-9._-]{0,127}$")

(defn valid-protocol-id?
  "True when id is a canonical PRF protocol identifier.

   Reuses the repository's :protocol/id convention (a qualified keyword such as
   :protocol/sew) and applies the strict lower-case machine-identifier rule from
   the canonical contract to each segment:
   ^[a-z0-9][a-z0-9._-]{0,127}$  (1..128 chars). Neither segment may be empty."
  [id]
  (and (qualified-keyword? id)
       (let [ns (namespace id)
             n (name id)]
         (and (some? ns)
              (re-matches protocol-id-segment ns)
              (re-matches protocol-id-segment n)))))

(def ^:private chain-id-upper-bound
  "Exclusive upper bound for a canonical EVM chain id: 2^256 (uint256 boundary)."
  (.pow (biginteger 2) 256))

(defn valid-chain-id?
  "True when id is a canonical EVM chain id: an integer (Long/BigInt/BigInteger)
   with 1 <= id < 2^256. Strings, floats, ratios, BigDecimal, nil, zero,
   negative, and 2^256 itself are rejected."
  [id]
  (and (integer? id)
       (>= id 1)
       (< id chain-id-upper-bound)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Protocol genesis (protocol-genesis.v1)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:private protocol-genesis-root-fields
  "sha256 reference fields of protocol-genesis.v1 (identity-bearing roots)."
  [:canonicalisation/root :semantics/root
   :governance/constitution-root :governance/evolution-policy-root
   :configuration/contract-root :evidence/contract-root
   :verification/contract-root :cross-domain/authority-policy-root])

(defn validate-protocol-genesis
  "Strict, closed-shape validator for protocol-genesis.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong genesis/schema (must be exactly \"protocol-genesis.v1\");
     - unknown top-level keys (closed shape);
     - missing required keys;
     - nil roots;
     - malformed sha256 references;
     - protocol id that is not a qualified lower-case machine identifier.

   Unknown keys are never silently ignored: they are reported as errors."
  [genesis]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))
        expect (set protocol-genesis-fields)]
    (when-not (map? genesis)
      (report! "protocol-genesis must be a map"))
    (when (map? genesis)
      (when-not (= protocol-genesis-schema (get genesis :genesis/schema))
        (report! (str "genesis/schema must be " protocol-genesis-schema
                      ", got " (pr-str (:genesis/schema genesis)))))
      (let [have (set (keys genesis))
            extra (set/difference have expect)
            missing (set/difference expect have)]
        (when (seq extra)
          (report! (str "unknown top-level keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing required keys: " (sort missing)))))
      (when-not (valid-protocol-id? (:protocol/id genesis))
        (report! "protocol/id must be a qualified lower-case machine identifier"))
      (doseq [f protocol-genesis-root-fields
              :let [v (get genesis f)]]
        (cond
          (nil? v) (report! (str f " must not be nil"))
          (not (hash-ref/valid-sha256-ref? v))
          (report! (str f " must be a valid sha256 reference, got " (pr-str v))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn protocol-genesis-valid?
  "Quick boolean structural validity check for protocol-genesis.v1."
  [genesis]
  (:valid? (validate-protocol-genesis genesis)))

(defn protocol-genesis-projection
  "Explicit versioned projection of protocol-genesis.v1: exactly the canonical
   identity fields, projected canonical-safe. Semantically equivalent to
   (hc/domain-hash :prf-protocol-genesis-v1 (protocol-genesis-projection genesis))."
  [genesis]
  (hc/project-protocol-genesis genesis :prf-protocol-genesis-v1))

(defn protocol-genesis-root
  "Compute the canonical SHA-256 protocol-genesis.v1 root as
   \"sha256:<64 lowercase hex>\".

   Validates strict closed-shape first (fail-closed); a caller-supplied expected
   root may be supplied as the second argument and is rejected on mismatch rather
   than trusted."
  ([genesis]
   (let [v (validate-protocol-genesis genesis)]
     (when-not (:valid? v)
       (throw (ex-info "protocol-genesis.v1 is invalid"
                       {:type :genesis/invalid
                        :schema protocol-genesis-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
    (hc/domain-hash :prf-protocol-genesis-v1 (protocol-genesis-projection genesis))))
  ([genesis expected]
   (let [computed (protocol-genesis-root genesis)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied protocol-genesis root does not match computed root"
                       {:type :genesis/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Chain instance genesis (chain-instance-genesis.v1)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:private chain-instance-root-fields
  "sha256 reference fields in chain-instance-genesis.v1 (identity-bearing roots)."
  [:protocol/genesis-root :configuration/initial-root])

(defn validate-chain-instance-genesis
  "Strict, closed-shape validator for chain-instance-genesis.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong genesis/schema;
     - unknown top-level keys, or unknown nested keys under :control-plane /
       :governance (both nested shapes are closed);
     - missing required keys;
     - invalid sha256 / keccak references;
     - EVM addresses that are not canonical lowercase non-zero;
     - chain ids that are not 1 <= id < 2^256 integers;
     - deployment/provenance fields (rejected by the closed shape).

   Unknown keys never enter the canonical preimage."
  [genesis]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))
        expect (set chain-instance-genesis-fields)
        cp-fields (set hc/chain-instance-genesis-control-plane-fields)
        gov-fields (set hc/chain-instance-genesis-governance-fields)]
    (when-not (map? genesis)
      (report! "chain-instance-genesis must be a map"))
    (when (map? genesis)
      (when-not (= chain-instance-genesis-schema (get genesis :genesis/schema))
        (report! (str "genesis/schema must be " chain-instance-genesis-schema
                      ", got " (pr-str (:genesis/schema genesis)))))
      (let [have (set (keys genesis))
            extra (set/difference have expect)
            missing (set/difference expect have)]
        (when (seq extra)
          (report! (str "unknown top-level keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing required keys: " (sort missing))))
        (doseq [f chain-instance-root-fields
                :let [v (get genesis f)]
                :when (contains? (set (keys genesis)) f)]
          (cond
            (nil? v) (report! (str f " must not be nil"))
            (not (hash-ref/valid-sha256-ref? v))
            (report! (str f " must be a valid sha256 reference, got " (pr-str v))))))
      (when-not (valid-chain-id? (:execution/chain-id genesis))
        (report! "execution/chain-id must be an integer in [1, 2^256)"))
      (when-not (valid-chain-id? (:settlement/chain-id genesis))
        (report! "settlement/chain-id must be an integer in [1, 2^256)"))
      (let [cp (:control-plane genesis)
            gov (:governance genesis)]
        (when-not (map? cp)
          (report! "control-plane must be a map"))
        (when (map? cp)
          (let [have (set (keys cp))
                extra (set/difference have cp-fields)
                missing (set/difference cp-fields have)]
            (when (seq extra)
              (report! (str "unknown control-plane keys: " (sort extra))))
            (when (seq missing)
              (report! (str "missing control-plane keys: " (sort missing)))))
          (when-not (valid-evm-address? (:address cp))
            (report! "control-plane/address must be a canonical non-zero EVM address"))
          (when-not (valid-keccak256-ref? (:runtime-code-keccak256 cp))
            (report! "control-plane/runtime-code-keccak256 must be a valid keccak256 reference")))
        (when-not (map? gov)
          (report! "governance must be a map"))
        (when (map? gov)
          (let [have (set (keys gov))
                extra (set/difference have gov-fields)
                missing (set/difference gov-fields have)]
            (when (seq extra)
              (report! (str "unknown governance keys: " (sort extra))))
            (when (seq missing)
              (report! (str "missing governance keys: " (sort missing)))))
          (when-not (valid-evm-address? (:authority-adapter gov))
            (report! "governance/authority-adapter must be a canonical non-zero EVM address"))
          (when-not (valid-keccak256-ref? (:authority-adapter-code-keccak256 gov))
            (report! "governance/authority-adapter-code-keccak256 must be a valid keccak256 reference"))
          (when-not (hash-ref/valid-sha256-ref? (:initial-authority-state-root gov))
            (report! "governance/initial-authority-state-root must be a valid sha256 reference")))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn chain-instance-genesis-valid?
  "Quick boolean structural validity check for chain-instance-genesis.v1."
  [genesis]
  (:valid? (validate-chain-instance-genesis genesis)))

(defn chain-instance-genesis-projection
  "Explicit versioned projection of chain-instance-genesis.v1: exactly the
   canonical identity fields, with nested :control-plane and :governance
   projected to their exact sub-field sets."
  [genesis]
  (hc/project-chain-instance-genesis genesis :prf-chain-instance-genesis-v1))

(defn chain-instance-genesis-root
  "Compute the canonical SHA-256 chain-instance-genesis.v1 root as
   \"sha256:<64 lowercase hex>\". This 32-byte value is suitable to pass
   directly as the bytes32 canonical-chain-instance-genesis-root argument of
   PrfChainInstanceGenesis.sealGenesis(...).

   Validates strict closed-shape first (fail-closed); a caller-supplied expected
   root may be passed as the second argument and is rejected on mismatch."
  ([genesis]
   (let [v (validate-chain-instance-genesis genesis)]
     (when-not (:valid? v)
       (throw (ex-info "chain-instance-genesis.v1 is invalid"
                       {:type :genesis/invalid
                        :schema chain-instance-genesis-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
    (hc/domain-hash :prf-chain-instance-genesis-v1
                    (chain-instance-genesis-projection genesis))))
  ([genesis expected]
   (let [computed (chain-instance-genesis-root genesis)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied chain-instance genesis root does not match computed root"
                       {:type :genesis/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Deterministic canonical fixtures
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Fixture sub-roots are opaque, deterministic canonical references derived from
;; stable fixture labels. They are NOT real protocol artefacts; they exist only
;; to make the fixtures reproducible and cross-language conformance vectors.

(defn- fixture-ref
  "Deterministic opaque sha256 reference for a fixture sub-root."
  [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record label)))

(defn- fixture-keccak
  "Deterministic opaque keccak256 reference for a fixture code-hash field.
   The hex digest is structurally a valid keccak256 reference; its semantic
   origin is irrelevant to the canonical identity."
  [label]
  (str "keccak256:" (hc/domain-hash :evidence-record label)))

(def protocol-genesis-fixture
  "Canonical protocol-genesis.v1 fixture. Represents the SEW/PRF constitutional
   identity. All sub-roots are deterministic, valid sha256 references."
  {:genesis/schema "protocol-genesis.v1"
   :protocol/id :protocol/sew
   :canonicalisation/root (fixture-ref "protocol-genesis.canonicalisation.v1")
   :semantics/root (fixture-ref "protocol-genesis.semantics.v1")
   :governance/constitution-root (fixture-ref "governance.constitution.v1")
   :governance/evolution-policy-root (fixture-ref "governance.evolution-policy.v1")
   :configuration/contract-root (fixture-ref "configuration.contract.v1")
   :evidence/contract-root (fixture-ref "evidence.contract.v1")
   :verification/contract-root (fixture-ref "verification.contract.v1")
   :cross-domain/authority-policy-root (fixture-ref "cross-domain.authority-policy.v1")})

(def protocol-genesis-fixture-root
  "Canonical root of protocol-genesis-fixture (computed at load for reuse by the
   chain-instance fixtures)."
  (protocol-genesis-root protocol-genesis-fixture))

(def chain-instance-genesis-ethereum-fixture
  "Canonical chain-instance-genesis.v1 fixture for an Ethereum execution instance:
   execution = settlement = 1."
  {:genesis/schema "chain-instance-genesis.v1"
   :protocol/genesis-root protocol-genesis-fixture-root
   :execution/chain-id 1
   :settlement/chain-id 1
   :control-plane
   {:address "0x00112233445566778899aabbccddeeff00112233"
    :runtime-code-keccak256 (fixture-keccak "control-plane.runtime-code.ethereum.v1")}
   :governance
   {:authority-adapter "0x112233445566778899aabbccddeeff0011223344"
    :authority-adapter-code-keccak256 (fixture-keccak "authority-adapter.runtime-code.ethereum.v1")
    :initial-authority-state-root (fixture-ref "authority-state.ethereum.v1")}
   :configuration/initial-root (fixture-ref "configuration.initial.ethereum.v1")})

(def chain-instance-genesis-eez-fixture
  "Canonical chain-instance-genesis.v1 fixture for a synthetic EEZ/L2 execution
   instance sharing protocol-genesis-fixture-root. execution chain id is a
   synthetic non-production value (2718); settlement = 1."
  {:genesis/schema "chain-instance-genesis.v1"
   :protocol/genesis-root protocol-genesis-fixture-root
   :execution/chain-id 2718
   :settlement/chain-id 1
   :control-plane
   {:address "0x2233445566778899aabbccddeeff001122334455"
    :runtime-code-keccak256 (fixture-keccak "control-plane.runtime-code.eez.v1")}
   :governance
   {:authority-adapter "0x33445566778899aabbccddeeff00112233445566"
    :authority-adapter-code-keccak256 (fixture-keccak "authority-adapter.runtime-code.eez.v1")
    :initial-authority-state-root (fixture-ref "authority-state.eez.v1")}
   :configuration/initial-root (fixture-ref "configuration.initial.eez.v1")})

(def chain-instance-genesis-ethereum-fixture-root
  "Canonical root of the Ethereum chain-instance fixture."
  (chain-instance-genesis-root chain-instance-genesis-ethereum-fixture))

(def chain-instance-genesis-eez-fixture-root
  "Canonical root of the EEZ/L2 chain-instance fixture (shares the protocol
   genesis root but differs in execution domain, addresses, and sub-roots)."
  (chain-instance-genesis-root chain-instance-genesis-eez-fixture))

;; ──────────────────────────────────────────────────────────────────────────────
;; Chain configuration (chain-configuration.v1)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:const chain-configuration-schema
  "Schema identifier for chain-configuration.v1."
  "chain-configuration.v1")

(def ^:private chain-configuration-root-fields
  "sha256 reference fields of chain-configuration.v1 (identity-bearing roots)."
  [:module-registry/root :verifier-registry/root
   :evidence-policy/root :escrow-template-registry/root
   :parameter-policy/root :governance-policy/root
   :interoperability-policy/root])

(defn validate-chain-configuration
  "Strict, closed-shape validator for chain-configuration.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong configuration/schema;
     - unknown top-level keys (closed shape);
     - missing required keys;
     - nil roots;
     - malformed sha256 references."
  [config]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))
        expect (set hc/chain-configuration-fields)]
    (when-not (map? config)
      (report! "chain-configuration must be a map"))
    (when (map? config)
      (when-not (= chain-configuration-schema (get config :configuration/schema))
        (report! (str "configuration/schema must be " chain-configuration-schema
                      ", got " (pr-str (:configuration/schema config)))))
      (let [have (set (keys config))
            extra (set/difference have expect)
            missing (set/difference expect have)]
        (when (seq extra)
          (report! (str "unknown top-level keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing required keys: " (sort missing))))
        (doseq [f chain-configuration-root-fields]
          (let [v (get config f)]
            (cond
              (nil? v) (report! (str f " must not be nil"))
              (not (hash-ref/valid-sha256-ref? v))
              (report! (str f " must be a valid sha256 reference, got " (pr-str v))))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn chain-configuration-valid?
  "Quick boolean structural validity check for chain-configuration.v1."
  [config]
  (:valid? (validate-chain-configuration config)))

(def chain-configuration-fields
  "Explicit, reusable set of the canonical configuration field surface.
   Derives from hc/chain-configuration-fields (single source of truth
   in hash.canonical) and exposes it as a set for membership checks.

   Invariant: validator field set == canonical projection field set
   == exported chain-configuration-fields."
  (set hc/chain-configuration-fields))

(def chain-configuration-change-identity-required-fields
  "Canonical pre-change basis for configuration-transition change-identity.

   Captures the complete logical request:

   - :configuration/parent-root — the exact parent state the change applies to
   - :configuration/new-root — the exact proposed configuration identity
     (commits ALL sub-roots: module-registry, verifier-registry, evidence-policy,
     escrow-templates, parameter-policy, governance-policy, interoperability-policy)
   - :target — the target scope of the transition

   No individual sub-root (e.g. verifier-registry/root) is included separately
   because :configuration/new-root already cryptographically commits every
   configurable field. Including them independently would create a
   consistency-mismatch surface (e.g. a new-root that commits R1 but a
   separately-supplied verifier-registry/root that says R2).

   Excluded (sequencing / metadata, NOT part of the logical request):
   - :transition/schema (metadata)
   - :protocol/genesis-root (chain identity, immutable)
   - :epoch (position in chain sequence)"
  #{:configuration/parent-root
    :configuration/new-root
    :target})

(def chain-configuration-change-identity-domain
  "Domain tag for the internally-derived configuration-transition change identity."
  :prf-chain-configuration-change-identity-v1)

(defn chain-configuration-change-identity-basis
  "Canonical pre-change basis map for a chain-scoped configuration change request.

   Includes the complete logical request: parent-root, new-root (the proposed
   full configuration identity that commits all sub-roots), and target.

   Excludes :epoch (sequence metadata), :transition/schema (metadata), and
   :protocol/genesis-root (chain identity)."
  [transition]
  (select-keys transition chain-configuration-change-identity-required-fields))

(defn chain-configuration-change-identity-hash
  "Internally-derived, chain-scoped identity of a configuration change request.
   Pure function of {parent-root, new-root, target}, never of epoch,
   transition-schema, or protocol/genesis-root, so the same requested change
   retains its identity across resequencing."
  [transition]
  (hash-ref/sha256-ref
   (hc/domain-hash chain-configuration-change-identity-domain
                   (chain-configuration-change-identity-basis transition))))

(defn chain-configuration-projection
  "Explicit versioned projection of chain-configuration.v1: exactly the canonical
   identity fields, projected canonical-safe."
  [config]
  (hc/project-chain-configuration config :prf-chain-configuration-v1))

(defn chain-configuration-root
  "Compute the canonical SHA-256 chain-configuration.v1 root as
   sha256:<64 lowercase hex>."
  ([config]
   (let [v (validate-chain-configuration config)]
     (when-not (:valid? v)
       (throw (ex-info "chain-configuration.v1 is invalid"
                       {:type :configuration/invalid
                        :schema chain-configuration-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
    (hc/domain-hash :prf-chain-configuration-v1
                    (chain-configuration-projection config))))
  ([config expected]
   (let [computed (chain-configuration-root config)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied chain-configuration root does not match computed root"
                       {:type :configuration/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Chain configuration transition (chain-configuration-transition.v1)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:const chain-configuration-transition-schema
  "Schema identifier for chain-configuration-transition.v1."
  "chain-configuration-transition.v1")

(def ^:private transition-target-types
  "Allowed values for :target/type in chain-configuration-transition.v1."
  #{:chain-instance :chain-instance-set})

(def ^:private epoch-upper-bound
  "Exclusive upper bound for a canonical EVM epoch (uint64 boundary): 2^64."
  (.pow (biginteger 2) 64))

(defn valid-epoch?
  "True when epoch is an integer with 1 <= epoch < 2^64.
   Strings, floats, ratios, BigDecimal, nil, zero, negative, and 2^64+ are rejected."
  [epoch]
  (and (integer? epoch)
       (>= epoch 1)
       (< epoch epoch-upper-bound)))

(defn- valid-target-root?
  "Validate the target root based on target type.
   :chain-instance requires a sha256 reference.
   :chain-instance-set requires a keccak256 reference.
   Returns nil if valid, an error string otherwise."
  [target-type target-root]
  (case target-type
    :chain-instance
    (if (hash-ref/valid-sha256-ref? target-root)
      nil
      "target/root for :chain-instance must be a valid sha256 reference")
    :chain-instance-set
    (if (valid-keccak256-ref? target-root)
      nil
      "target/root for :chain-instance-set must be a valid keccak256 reference")
    "unknown target/type"))

(defn validate-chain-configuration-transition
  "Strict, closed-shape validator for chain-configuration-transition.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong transition/schema;
     - unknown top-level keys;
     - unknown nested keys under :target (closed nested shape);
     - missing required keys;
     - malformed sha256 / keccak references;
     - epoch outside [1, 2^64);
     - self-transition (parent-root == new-root);
     - :chain-instance target root that is not a sha256 reference;
     - :chain-instance-set target root that is not a keccak256 reference.

   Unknown keys never enter the canonical preimage."
  [transition]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))
        expect (set hc/chain-configuration-transition-fields)
        target-fields (set hc/chain-configuration-transition-target-fields)]
    (when-not (map? transition)
      (report! "chain-configuration-transition must be a map"))
    (when (map? transition)
      (when-not (= chain-configuration-transition-schema (get transition :transition/schema))
        (report! (str "transition/schema must be " chain-configuration-transition-schema
                      ", got " (pr-str (:transition/schema transition)))))
      (let [have (set (keys transition))
            extra (set/difference have expect)
            missing (set/difference expect have)]
        (when (seq extra)
          (report! (str "unknown top-level keys: " (sort extra))))
        (when (seq missing)
          (report! (str "missing required keys: " (sort missing))))
        (when-not (hash-ref/valid-sha256-ref? (:protocol/genesis-root transition))
          (report! "protocol/genesis-root must be a valid sha256 reference"))
        (let [parent (:configuration/parent-root transition)
              new-root (:configuration/new-root transition)
              vr-root (:verifier-registry/root transition)]
          (when-not (hash-ref/valid-sha256-ref? parent)
            (report! "configuration/parent-root must be a valid sha256 reference"))
          (when-not (hash-ref/valid-sha256-ref? new-root)
            (report! "configuration/new-root must be a valid sha256 reference"))
          (when-not (hash-ref/valid-sha256-ref? vr-root)
            (report! "verifier-registry/root must be a valid sha256 reference"))
          (when (and (some? vr-root) (some? parent) (= vr-root parent))
            (report! "verifier-registry/root must not equal configuration/parent-root"))
          (when (and (some? parent) (some? new-root) (= parent new-root))
            (report! "self-transition rejected: configuration/parent-root equals configuration/new-root")))
        (when-not (valid-epoch? (:epoch transition))
          (report! "epoch must be an integer in [1, 2^64)"))
        (let [target (:target transition)]
          (cond
            (not (map? target))
            (report! "target must be a map")
            :else
            (let [have (set (keys target))
                  extra-t (set/difference have target-fields)
                  missing-t (set/difference target-fields have)]
              (when (seq extra-t)
                (report! (str "unknown target keys: " (sort extra-t))))
              (when (seq missing-t)
                (report! (str "missing target keys: " (sort missing-t))))
              (let [ttype (:target/type target)
                    troot (:target/root target)]
                (when-not (contains? transition-target-types ttype)
                  (report! (str "target/type must be :chain-instance or :chain-instance-set, got " (pr-str ttype))))
                (when (and (contains? transition-target-types ttype) troot)
                  (when-let [err (valid-target-root? ttype troot)]
                    (report! err)))))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn chain-configuration-transition-valid?
  "Quick boolean structural validity check for chain-configuration-transition.v1."
  [transition]
  (:valid? (validate-chain-configuration-transition transition)))

(defn chain-configuration-transition-projection
  "Explicit versioned projection of chain-configuration-transition.v1: exactly the
   canonical identity fields, with nested :target projected to its exact
   sub-field set."
  [transition]
  (hc/project-chain-configuration-transition transition :prf-chain-configuration-transition-v1))

(defn chain-configuration-transition-root
  "Compute the canonical SHA-256 chain-configuration-transition.v1 root as
   sha256:<64 lowercase hex>. This 32-byte value IS the Solidity decisionRoot
   consumed by GovGovernor.authorisePrfDecision(…)."
  ([transition]
   (let [v (validate-chain-configuration-transition transition)]
     (when-not (:valid? v)
       (throw (ex-info "chain-configuration-transition.v1 is invalid"
                       {:type :transition/invalid
                        :schema chain-configuration-transition-schema
                        :errors (:errors v)}))))
   (hash-ref/sha256-ref
    (hc/domain-hash :prf-chain-configuration-transition-v1
                    (chain-configuration-transition-projection transition))))
  ([transition expected]
   (let [computed (chain-configuration-transition-root transition)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied transition root does not match computed root"
                       {:type :transition/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Deterministic canonical fixtures
;; ──────────────────────────────────────────────────────────────────────────────

(def chain-configuration-fixture
  "Canonical chain-configuration.v1 fixture. Represents a semantic configuration
   state with deterministic, valid sha256 references to all sub-root concepts."
  {:configuration/schema "chain-configuration.v1"
   :module-registry/root (fixture-ref "module-registry.v1")
   :verifier-registry/root (fixture-ref "verifier-registry.v1")
   :evidence-policy/root (fixture-ref "evidence-policy.v1")
   :escrow-template-registry/root (fixture-ref "escrow-template-registry.v1")
   :parameter-policy/root (fixture-ref "parameter-policy.v1")
   :governance-policy/root (fixture-ref "governance-policy.v1")
   :interoperability-policy/root (fixture-ref "interoperability-policy.v1")})

(def chain-configuration-v0-fixture
  "C0: Initial chain-configuration.v1 fixture for the verifier-registry
   authority binding task. Uses the existing verifier-registry.v1 registry root.
   This is an alias of chain-configuration-fixture to give the C0/C1 distinction
   explicit identity in the application-plan fixtures."
  chain-configuration-fixture)

(def chain-configuration-v1-fixture
  "C1: New chain-configuration.v1 fixture representing a configuration transition
   that adds a synthetic test verifier to the verifier registry. The only field
   that differs from C0 is :verifier-registry/root, which points to a new
   deterministic fixture reference verifier-registry.v2."
  {:configuration/schema "chain-configuration.v1"
   :module-registry/root (fixture-ref "module-registry.v1")
   :verifier-registry/root (fixture-ref "verifier-registry.v2")
   :evidence-policy/root (fixture-ref "evidence-policy.v1")
   :escrow-template-registry/root (fixture-ref "escrow-template-registry.v1")
   :parameter-policy/root (fixture-ref "parameter-policy.v1")
   :governance-policy/root (fixture-ref "governance-policy.v1")
   :interoperability-policy/root (fixture-ref "interoperability-policy.v1")})

(def chain-configuration-v0-fixture-root
  "Canonical root of C0 (chain-configuration-v0-fixture)."
  (chain-configuration-root chain-configuration-v0-fixture))

(def chain-configuration-v1-fixture-root
  "Canonical root of C1 (chain-configuration-v1-fixture)."
  (chain-configuration-root chain-configuration-v1-fixture))

(def chain-configuration-transition-v0-to-v1-fixture
  "Canonical chain-configuration-transition.v1 fixture for the transition
   C0/R0 → C1/R1. The parent root is the canonical root of C0; the new root
   is the canonical root of C1; the verifier-registry/root is R1 (C1's
   verifier-registry root, since the canonical transition commits the new
   registry root)."
  {:transition/schema "chain-configuration-transition.v1"
   :protocol/genesis-root protocol-genesis-fixture-root
   :target {:target/type :chain-instance
            :target/root chain-instance-genesis-ethereum-fixture-root}
   :configuration/parent-root (str chain-configuration-v0-fixture-root)
   :configuration/new-root (str chain-configuration-v1-fixture-root)
   :verifier-registry/root (fixture-ref "verifier-registry.v2")
   :epoch 1})

(def chain-configuration-transition-v0-to-v1-fixture-root
  "Canonical root (decisionRoot) of the v0→v1 transition fixture."
  (chain-configuration-transition-root chain-configuration-transition-v0-to-v1-fixture))

(def chain-configuration-fixture-root
  "Canonical root of chain-configuration-fixture (computed at load for reuse by
   the transition fixtures)."
  (chain-configuration-root chain-configuration-fixture))

(def chain-configuration-transition-direct-fixture
  "Canonical chain-configuration-transition.v1 fixture with a direct
   :chain-instance target (targetMode = 0 in Solidity). The target root is the
   Ethereum chain-instance genesis root; parent/new configuration roots and
   verifier-registry root are deterministic fixture refs."
  {:transition/schema "chain-configuration-transition.v1"
   :protocol/genesis-root protocol-genesis-fixture-root
   :target {:target/type :chain-instance
            :target/root chain-instance-genesis-ethereum-fixture-root}
   :configuration/parent-root (fixture-ref "configuration.parent.ethereum.v1")
   :configuration/new-root (fixture-ref "configuration.new.ethereum.v1")
   :verifier-registry/root (fixture-ref "verifier-registry.v1")
   :epoch 1})

(def chain-configuration-transition-set-fixture
  "Canonical chain-configuration-transition.v1 fixture with a :chain-instance-set
   target (targetMode = 1 in Solidity). The target root is a deterministic
   keccak256 Merkle membership root; parent/new configuration roots and
   verifier-registry root are identical to the direct fixture to prove fan-out
   produces a different transition identity."
  {:transition/schema "chain-configuration-transition.v1"
   :protocol/genesis-root protocol-genesis-fixture-root
   :target {:target/type :chain-instance-set
            :target/root (fixture-keccak "chain-instance-set.ethereum.v1")}
   :configuration/parent-root (fixture-ref "configuration.parent.ethereum.v1")
   :configuration/new-root (fixture-ref "configuration.new.ethereum.v1")
   :verifier-registry/root (fixture-ref "verifier-registry.v1")
   :epoch 1})

(def chain-configuration-transition-direct-fixture-root
  "Canonical root of the direct-target transition fixture."
  (chain-configuration-transition-root chain-configuration-transition-direct-fixture))

(def chain-configuration-transition-set-fixture-root
  "Canonical root of the set-target transition fixture."
  (chain-configuration-transition-root chain-configuration-transition-set-fixture))

;; ──────────────────────────────────────────────────────────────────────────────
;; Solidity authorization projection
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:const solidity-target-mode-direct
  "Solidity targetMode for a single chain-instance transition (TARGET_DIRECT = 0).
   Corresponds to PRF :target/type :chain-instance."
  0)

(def ^:const solidity-target-mode-set
  "Solidity targetMode for a multi-instance EEZ transition (TARGET_SET = 1).
   Corresponds to PRF :target/type :chain-instance-set."
  1)

(defn- prf-ref->bytes32
  "Strict conversion of a canonical PRF reference string to a Solidity bytes32
   hex literal (0x + 64 lowercase hex chars).

   Accepts only well-formed sha256: or keccak256: refs. Never lowercases
   malformed caller input as normalization — malformed input throws."
  [ref]
  (cond
    (not (string? ref))
    (throw (ex-info "PRF reference must be a string" {:ref ref}))
    (str/starts-with? ref "sha256:")
    (let [hex (subs ref 7)]
      (when-not (re-matches #"[0-9a-f]{64}" hex)
        (throw (ex-info "malformed sha256 reference" {:ref ref})))
      (str "0x" hex))
    (str/starts-with? ref "keccak256:")
    (let [hex (subs ref 10)]
      (when-not (re-matches #"[0-9a-f]{64}" hex)
        (throw (ex-info "malformed keccak256 reference" {:ref ref})))
      (str "0x" hex))
    :else
    (throw (ex-info "unrecognised PRF reference prefix" {:ref ref}))))

(defn chain-configuration-transition->solidity-authorization
  "Project a validated chain-configuration-transition.v1 into the exact argument
   tuple consumed by GovGovernor.authorisePrfDecision(…).

   The transition is first validated with the authoritative validator
   (fail-closed). The decisionRoot is derived internally as the canonical
   transition root — a caller cannot supply it separately.

    Returns:
    {:decision-root              \"0x<64 hex>\"         ;; = chain-configuration-transition-root
     :target-mode                0 | 1                 ;; Solidity bytes1
     :target-root                \"0x<64 hex>\"         ;; bytes32
     :parent-configuration-root  \"0x<64 hex>\"         ;; bytes32
     :new-configuration-root     \"0x<64 hex>\"         ;; bytes32
     :verifier-registry-root     \"0x<64 hex>\"         ;; bytes32
     :epoch                      <uint64>}

    No new canonical identity is created. This is derived data only."
  [transition]
  (let [v (validate-chain-configuration-transition transition)]
    (when-not (:valid? v)
      (throw (ex-info "chain-configuration-transition.v1 is invalid"
                      {:type :transition/invalid
                       :schema chain-configuration-transition-schema
                       :errors (:errors v)}))))
  (let [target-type (-> transition :target :target/type)
        target-mode (case target-type
                      :chain-instance solidity-target-mode-direct
                      :chain-instance-set solidity-target-mode-set)]
    {:decision-root (str "0x"
                         (subs (chain-configuration-transition-root transition) 7))
     :target-mode target-mode
     :target-root (prf-ref->bytes32 (-> transition :target :target/root))
     :parent-configuration-root (prf-ref->bytes32 (:configuration/parent-root transition))
     :new-configuration-root (prf-ref->bytes32 (:configuration/new-root transition))
     :verifier-registry-root (prf-ref->bytes32 (:verifier-registry/root transition))
     :epoch (:epoch transition)}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Solidity initialization projection (non-canonical, derived)
;; ──────────────────────────────────────────────────────────────────────────────

(defn chain-configuration->solidity-initialization
  "Derived, non-canonical projection from a validated chain-configuration.v1 into
   the Solidity initialization tuple for SewConfigurationExecutor + PrfVerifierRegistry.

   Validates the configuration first (fail-closed). Both roots are derived
   internally — a caller cannot supply a separately caller-supplied configuration root
   or verifier-registry root.

   Returns:
   {:configuration-root           \"0x<64 hex>\"  ;; = chain-configuration.v1 root as bytes32
    :verifier-registry-root       \"0x<64 hex>\"  ;; = :verifier-registry/root as bytes32
    }

   No new canonical identity is created. This is derived data only."
  [configuration]
  (let [v (validate-chain-configuration configuration)]
    (when-not (:valid? v)
      (throw (ex-info "chain-configuration.v1 is invalid"
                      {:type :configuration/invalid
                       :schema chain-configuration-schema
                       :errors (:errors v)}))))
  {:configuration-root (str "0x"
                            (subs (chain-configuration-root configuration) 7))
   :verifier-registry-root (prf-ref->bytes32 (:verifier-registry/root configuration))})

;; ──────────────────────────────────────────────────────────────────────────────
;; Solidity application plan projection (non-canonical, derived)
;; ──────────────────────────────────────────────────────────────────────────────

(defn chain-configuration-transition->solidity-application-plan
  "Derived, non-canonical projection from a validated chain-configuration-transition.v1
   plus the parent and new canonical chain-configuration.v1 objects into the exact
   argument tuple consumed by GovGovernor.authorisePrfDecision(…).

   The transition is first validated (fail-closed). It then verifies:
   - root(parent-configuration) == transition.configuration/parent-root
   - root(new-configuration)    == transition.configuration/new-root

   All roots are derived internally — a caller cannot override any derived root.

   Returns:
   {:decision-root                    \"0x<64 hex>\"  ;; = chain-configuration-transition-root
    :target-mode                      0 | 1
    :target-root                      \"0x<64 hex>\"
    :parent-configuration-root        \"0x<64 hex>\"
    :new-configuration-root           \"0x<64 hex>\"
    :parent-verifier-registry-root    \"0x<64 hex>\"
    :new-verifier-registry-root       \"0x<64 hex>\"
    :epoch                            <uint64>}

   No new canonical identity, hash intent, or domain tag is created. This is derived data only."
  [transition parent-configuration new-configuration]
  (let [v (validate-chain-configuration-transition transition)]
    (when-not (:valid? v)
      (throw (ex-info "chain-configuration-transition.v1 is invalid"
                      {:type :transition/invalid
                       :schema chain-configuration-transition-schema
                       :errors (:errors v)}))))
  (let [parent-root (chain-configuration-root parent-configuration)
        new-root (chain-configuration-root new-configuration)
        transition-parent (:configuration/parent-root transition)
        transition-new (:configuration/new-root transition)]
    (when (not= parent-root transition-parent)
      (throw (ex-info "parent configuration root does not match transition parent root"
                      {:type :configuration/parent-root-mismatch
                       :computed parent-root
                       :declared transition-parent})))
    (when (not= new-root transition-new)
      (throw (ex-info "new configuration root does not match transition new root"
                      {:type :configuration/new-root-mismatch
                       :computed new-root
                       :declared transition-new})))
    (let [target-type (-> transition :target :target/type)
          target-mode (case target-type
                        :chain-instance solidity-target-mode-direct
                        :chain-instance-set solidity-target-mode-set)]
      {:decision-root (str "0x"
                           (subs (chain-configuration-transition-root transition) 7))
       :target-mode target-mode
       :target-root (prf-ref->bytes32 (-> transition :target :target/root))
       :parent-configuration-root (prf-ref->bytes32 (:configuration/parent-root transition))
       :new-configuration-root (prf-ref->bytes32 (:configuration/new-root transition))
       :parent-verifier-registry-root (prf-ref->bytes32 (:verifier-registry/root parent-configuration))
       :new-verifier-registry-root (prf-ref->bytes32 (:verifier-registry/root new-configuration))
       :epoch (:epoch transition)})))
