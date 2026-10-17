(ns resolver-sim.authority-state
  "Canonical authority-state.v1 and local-authority-policy.v1/v2 contracts.
  Validation establishes semantic consistency, never governor approval or
  agreement with live adapter storage. Existing demo identities are unchanged."
  (:require [clojure.set :as set]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.evm :as evm]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.signed-external-decision :as signed]))

(def capabilities
  #{"authorise-decision" "revoke-decision" "set-proof-mode" "update-authority-state"})
(def transition-validator-role :prf-authority-state-transition-validator)
(def transition-attestation-domain "PRF_AUTHORITY_STATE_TRANSITION_V1")
(def transition-attestation-schema "prf-authority-state-execution-attestation.v1")

(def ^:private max-revision 18446744073709551615N)
(def ^:private decision-fields
  #{:decision/root :decision/revoked? :decision/proof-mode})
(def ^:private transition-envelope-fields
  #{:authority-transition/schema :authority-transition/instance-root
    :authority-transition/adapter-address :authority-transition/previous-root
    :authority-transition/successor-root :authority-transition/current-policy-root
    :authority-transition/successor-policy-root :authority-transition/previous-revision
    :authority-transition/successor-revision :authority-transition/mutations
    :authority-transition/decision-preimages :authority-transition/execution-mutations
    :authority-transition/execution-mutation-root :authority-transition/decision-preimages-root
    :authority-transition/execution-commitment})

(defn- closed? [value fields]
  (and (map? value) (= (set fields) (set (keys value)))))

(defn- ordered-unique? [xs]
  (and (vector? xs) (every? string? xs)
       (= xs (vec (sort (distinct xs))))))

(defn- scope-valid? [value]
  (and (reference/valid-sha256-ref? (:protocol/genesis-root value))
       (genesis/valid-chain-id? (:execution/chain-id value))))

(defn validate-local-authority-policy [policy]
  (let [schema (:authority-policy/schema policy)
        expected-fields (case schema
                          "local-authority-policy.v1" canonical/local-authority-policy-v1-fields
                          "local-authority-policy.v2" canonical/local-authority-policy-v2-fields
                          "local-authority-policy.v3" canonical/local-authority-policy-v3-fields
                          [])
        errors
        (cond-> []
          (or (empty? expected-fields) (not (closed? policy expected-fields)))
          (conj "policy must have exactly the declared local-authority-policy.v1 or v2 fields")
          (not (scope-valid? policy)) (conj "invalid protocol or execution scope")
          (not (genesis/valid-evm-address? (:authority/governor policy)))
          (conj "governor must be a canonical non-zero EVM address")
          (not (and (ordered-unique? (:authority/capabilities policy))
                    (seq (:authority/capabilities policy))
                    (every? capabilities (:authority/capabilities policy))))
          (conj "capabilities must be nonempty, sorted, unique and supported")
          (and (= "local-authority-policy.v2" schema)
               (not (and (string? (:authority/transition-validator-key-id policy))
                         (re-matches #"[a-z][a-z0-9._-]{0,63}"
                                     (:authority/transition-validator-key-id policy)))))
          (conj "v2 transition validator key id is invalid")
          (and (= "local-authority-policy.v2" schema)
               (not (and (string? (:authority/transition-validator-public-key policy))
                         (re-matches #"[0-9a-f]{64}"
                                     (:authority/transition-validator-public-key policy)))))
          (conj "v2 transition validator public key must be 32-byte lowercase hex")
          (and (= "local-authority-policy.v3" schema)
               (not (and (string? (:authority/transition-validator-key-id policy))
                         (re-matches #"[a-z][a-z0-9._-]{0,63}"
                                     (:authority/transition-validator-key-id policy)))))
          (conj "v3 transition validator key id is invalid")
          (and (= "local-authority-policy.v3" schema)
               (not (genesis/valid-evm-address? (:authority/transition-validator-address policy))))
          (conj "v3 transition validator address must be a canonical non-zero EVM address"))]
    {:valid? (empty? errors) :errors errors}))

(defn- decision-valid? [decision]
  (and (closed? decision decision-fields)
       (reference/valid-sha256-ref? (:decision/root decision))
       (boolean? (:decision/revoked? decision))
       (contains? #{nil 0 1} (:decision/proof-mode decision))))

(defn validate-authority-state [state]
  (let [revision (:authority/revision state)
        decisions (:authority/decisions state)
        revision? (and (integer? revision) (<= 0 revision max-revision))
        errors
        (cond-> []
          (not (closed? state canonical/authority-state-v1-fields))
          (conj "state must have exactly the authority-state.v1 fields")
          (not= "authority-state.v1" (:authority-state/schema state))
          (conj "invalid authority-state/schema")
          (not (scope-valid? state)) (conj "invalid protocol or execution scope")
          (not (reference/valid-sha256-ref? (:authority/policy-root state)))
          (conj "authority/policy-root must be a canonical SHA-256 reference")
          (not revision?) (conj "revision must be a uint64")
          (not (if (and revision? (zero? revision))
                 (nil? (:authority/previous-root state))
                 (reference/valid-sha256-ref? (:authority/previous-root state))))
          (conj "initial state requires nil predecessor; later states require its root")
          (not (and (vector? decisions) (every? decision-valid? decisions)
                    (ordered-unique? (mapv :decision/root decisions))))
          (conj "decisions must be closed records sorted uniquely by canonical decision root")
          (and revision? (zero? revision) (not= [] decisions))
          (conj "initial state must have no decisions"))]
    {:valid? (empty? errors) :errors errors}))

(defn- checked-root [value validator domain projection]
  (let [result (validator value)]
    (when-not (:valid? result)
      (throw (ex-info "Invalid canonical authority artifact" result)))
    (reference/sha256-ref (canonical/domain-hash domain (projection value nil)))))

(defn authority-state-root [state]
  (checked-root state validate-authority-state :prf-authority-state-v1
                canonical/project-authority-state-v1))

(defn local-authority-policy-root [policy]
  (let [result (validate-local-authority-policy policy)]
    (when-not (:valid? result)
      (throw (ex-info "Invalid canonical authority policy" result)))
    (case (:authority-policy/schema policy)
      "local-authority-policy.v1"
      (reference/sha256-ref (canonical/domain-hash :prf-local-authority-policy-v1
                                                   (canonical/project-local-authority-policy-v1 policy nil)))
      "local-authority-policy.v2"
      (reference/sha256-ref (canonical/domain-hash :prf-local-authority-policy-v2
                                                   (canonical/project-local-authority-policy-v2 policy nil)))
      "local-authority-policy.v3"
      (reference/sha256-ref (canonical/domain-hash :prf-local-authority-policy-v3
                                                   (canonical/project-local-authority-policy-v3 policy nil))))))

(defn validate-policy-binding
  "Check exact policy root and protocol/chain scope; does not approve the policy."
  [state policy]
  (let [state-result (validate-authority-state state)
        policy-result (validate-local-authority-policy policy)
        errors (into (:errors state-result) (:errors policy-result))
        errors (if (and (:valid? state-result) (:valid? policy-result))
                 (cond-> errors
                   (not= (:authority/policy-root state) (local-authority-policy-root policy))
                   (conj "authority policy root mismatch")
                   (not= (select-keys state [:protocol/genesis-root :execution/chain-id])
                         (select-keys policy [:protocol/genesis-root :execution/chain-id]))
                   (conj "authority policy scope mismatch"))
                 errors)]
    {:valid? (empty? errors) :errors errors}))

(defn validate-transition
  "Validate successor lineage and capability-bounded decision changes under the
  current policy. Caller authorization and live EVM enforcement are separate.
  Decision roots bind canonical configuration transitions; revocation is final.
  Policy replacement requires update-authority-state and a separately validated
  successor policy binding."
  [previous successor current-policy successor-policy]
  (let [errors (into (:errors (validate-policy-binding previous current-policy))
                     (:errors (validate-policy-binding successor successor-policy)))]
    (if (seq errors)
      {:valid? false :errors errors}
      (let [old (into {} (map (juxt :decision/root identity)) (:authority/decisions previous))
            next-decisions (into {} (map (juxt :decision/root identity)) (:authority/decisions successor))
            removed (set/difference (set (keys old)) (set (keys next-decisions)))
            added (set/difference (set (keys next-decisions)) (set (keys old)))
            changed (filter #(not= (old %) (next-decisions %)) (keys old))
            required (cond-> #{"update-authority-state"}
                       (seq added) (conj "authorise-decision")
                       (some #(and (not (:decision/revoked? (old %)))
                                   (:decision/revoked? (next-decisions %))) changed)
                       (conj "revoke-decision")
                       (or (some #(some? (:decision/proof-mode (next-decisions %))) added)
                           (some #(not= (:decision/proof-mode (old %))
                                        (:decision/proof-mode (next-decisions %))) changed))
                       (conj "set-proof-mode"))
            errors (cond-> []
                     (not= (select-keys previous [:protocol/genesis-root :execution/chain-id])
                           (select-keys successor [:protocol/genesis-root :execution/chain-id]))
                     (conj "authority scope cannot change")
                     (not= (inc (:authority/revision previous)) (:authority/revision successor))
                     (conj "revision must advance exactly once")
                     (not= (authority-state-root previous) (:authority/previous-root successor))
                     (conj "predecessor root mismatch")
                     (seq removed) (conj "decisions cannot be removed")
                     (some #(and (:decision/revoked? (old %))
                                 (not (:decision/revoked? (next-decisions %)))) changed)
                     (conj "revoked decisions cannot be restored")
                     (some #(:decision/revoked? (next-decisions %)) added)
                     (conj "new decisions must be initially authorised")
                     (some #(and (some? (:decision/proof-mode (old %)))
                                 (not= (:decision/proof-mode (old %))
                                       (:decision/proof-mode (next-decisions %)))) changed)
                     (conj "assigned proof modes cannot be replaced or removed")
                     (some #(and (:decision/revoked? (old %))
                                 (not= (:decision/proof-mode (old %))
                                       (:decision/proof-mode (next-decisions %)))) changed)
                     (conj "revoked decisions cannot receive proof modes")
                     (not (set/subset? required (set (:authority/capabilities current-policy))))
                     (conj "current policy lacks required transition capabilities"))]
        {:valid? (empty? errors) :errors errors}))))

(defn- authority-mutations [previous successor]
  (let [before (into {} (map (juxt :decision/root identity)) (:authority/decisions previous))
        after (into {} (map (juxt :decision/root identity)) (:authority/decisions successor))
        added (sort (set/difference (set (keys after)) (set (keys before))))
        changed (filter #(not= (before %) (after %)) (keys before))
        revoked (->> changed (filter #(and (not (:decision/revoked? (before %)))
                                           (:decision/revoked? (after %)))) sort vec)
        proof-mode-changes (->> (concat added changed)
                                distinct
                                (filter #(some? (:decision/proof-mode (after %))))
                                (filter #(not= (:decision/proof-mode (before %))
                                               (:decision/proof-mode (after %))))
                                sort
                                (mapv (fn [root] {:decision/root root
                                                  :decision/proof-mode (:decision/proof-mode (after root))})))]
    {:authorise-decision (vec added)
     :revoke-decision revoked
     :set-proof-mode proof-mode-changes
     :update-authority-state true}))

(defn- transition-execution-mutations [previous successor decision-preimages]
  (let [summary (authority-mutations previous successor)
        preimage-by-root (into {} (map (juxt :decision/root identity)) decision-preimages)
        authorisations
        (map (fn [decision-root]
               (let [preimage (get preimage-by-root decision-root)
                     transition (:decision/transition preimage)
                     target (:target transition)]
                 (when-not transition
                   (throw (ex-info "new authority decision is missing its transition preimage"
                                   {:decision-root decision-root})))
                 {:kind 0
                  :decision-root decision-root
                  :target-mode (case (:target/type target)
                                 :chain-instance 0
                                 :chain-instance-set 1
                                 (throw (ex-info "unsupported decision target type"
                                                 {:target-type (:target/type target)})))
                  :target-root (:target/root target)
                  :parent-configuration-root (:configuration/parent-root transition)
                  :new-configuration-root (:configuration/new-root transition)
                  :parent-verifier-registry-root (:decision/parent-verifier-registry-root preimage)
                  :new-verifier-registry-root (:verifier-registry/root transition)
                  :epoch (:epoch transition)
                  :proof-mode 0}))
             (:authorise-decision summary))
        revocations (map (fn [root] {:kind 1 :decision-root root})
                         (:revoke-decision summary))
        proof-modes (map (fn [{:decision/keys [root proof-mode]}]
                           {:kind 2 :decision-root root :proof-mode proof-mode})
                         (:set-proof-mode summary))]
    (->> (concat authorisations revocations proof-modes)
         (sort-by (juxt :decision-root :kind))
         vec)))

(defn- decision-preimages-root [preimages]
  (reference/sha256-ref
   (canonical/hash-with-intent {:hash/intent :evidence-content} preimages)))

(defn- execution-mutation-root [mutations]
  (evm/hex-ref (evm/authority-mutation-root mutations)))

(defn- execution-commitment [chain-instance previous successor current-policy
                             mutation-root preimages-root]
  (evm/hex-ref
   (evm/authority-transition-execution-commitment
    {:chain-id (:execution/chain-id chain-instance)
     :adapter-address (get-in chain-instance [:governance :authority-adapter])
     :instance-root (genesis/chain-instance-genesis-root chain-instance)
     :previous-root (authority-state-root previous)
     :successor-root (authority-state-root successor)
     :policy-root (local-authority-policy-root current-policy)
     :previous-revision (:authority/revision previous)
     :successor-revision (:authority/revision successor)
     :mutation-root mutation-root
     :decision-preimages-root preimages-root})))

(defn authority-transition-envelope
  "Build the exact semantic envelope a trusted local validator must attest.
  This builder does not authenticate it. Policy replacement is deliberately
  excluded until a separately versioned policy-transition rule exists."
  [previous successor current-policy successor-policy chain-instance decision-preimages]
  (when-not (contains? #{"local-authority-policy.v2" "local-authority-policy.v3"}
                       (:authority-policy/schema current-policy))
    (throw (ex-info "authority-state-transition.v1 requires a versioned validator key in local-authority-policy.v2 or v3" {})))
  (when-not (= current-policy successor-policy)
    (throw (ex-info "local authority policy replacement is unsupported by transition envelope v1" {})))
  (let [instance-root (genesis/chain-instance-genesis-root chain-instance)
        previous-root (authority-state-root previous)
        successor-root (authority-state-root successor)
        preimages (vec (sort-by :decision/root decision-preimages))
        execution-mutations (transition-execution-mutations previous successor preimages)
        mutation-root (execution-mutation-root execution-mutations)
        preimages-root (decision-preimages-root preimages)
        commitment (execution-commitment chain-instance previous successor current-policy
                                         mutation-root preimages-root)]
    {:authority-transition/schema "authority-state-transition.v1"
     :authority-transition/instance-root instance-root
     :authority-transition/adapter-address (get-in chain-instance [:governance :authority-adapter])
     :authority-transition/previous-root previous-root
     :authority-transition/successor-root successor-root
     :authority-transition/current-policy-root (local-authority-policy-root current-policy)
     :authority-transition/successor-policy-root (local-authority-policy-root successor-policy)
     :authority-transition/previous-revision (:authority/revision previous)
     :authority-transition/successor-revision (:authority/revision successor)
     :authority-transition/mutations (authority-mutations previous successor)
     :authority-transition/decision-preimages preimages
     :authority-transition/execution-mutations execution-mutations
     :authority-transition/execution-mutation-root mutation-root
     :authority-transition/decision-preimages-root preimages-root
     :authority-transition/execution-commitment commitment}))

(defn validate-authority-transition-envelope
  "Validate canonical decision preimages and bind the state mutation to one
  sealed chain instance and adapter. The caller still must authenticate the
  envelope with the validator authority explicitly approved for the deployment."
  [envelope previous successor current-policy successor-policy chain-instance]
  (let [shape-errors (cond-> []
                       (not (closed? envelope transition-envelope-fields))
                       (conj "transition envelope has missing or unknown fields")
                       (not= "authority-state-transition.v1" (:authority-transition/schema envelope))
                       (conj "transition envelope schema is invalid"))
        base (:errors (validate-transition previous successor current-policy successor-policy))
        instance-result (genesis/validate-chain-instance-genesis chain-instance)
        instance-errors (if (:valid? instance-result) [] (:errors instance-result))
        added (set (:authorise-decision (authority-mutations previous successor)))
        preimages (:authority-transition/decision-preimages envelope)
        preimage-errors
        (cond
          (not (and (vector? preimages)
                    (every? #(closed? % #{:decision/root :decision/transition
                                          :decision/parent-verifier-registry-root}) preimages)
                    (= (mapv :decision/root preimages)
                       (vec (sort (map :decision/root preimages))))))
          ["decision preimages must bind parent registry roots and be sorted uniquely by decision root"]

          (not= added (set (map :decision/root preimages)))
          ["decision preimages must exist exactly for newly authorised decisions"]

          :else
          (vec
           (mapcat
            (fn [{:keys [decision/root decision/transition] :as preimage}]
              (let [computed (try (genesis/chain-configuration-transition-root transition)
                                  (catch Exception _ nil))]
                (cond-> []
                  (not (reference/valid-sha256-ref? (:decision/parent-verifier-registry-root preimage)))
                  (conj (str "decision parent verifier-registry root is invalid for " root))
                  (not= root computed) (conj (str "decision preimage root mismatch for " root))
                  (not= (:protocol/genesis-root previous) (:protocol/genesis-root transition))
                  (conj (str "decision preimage protocol mismatch for " root))
                  (not= (:authority-transition/instance-root envelope)
                        (get-in transition [:target :target/root]))
                  (conj (str "decision preimage chain-instance target mismatch for " root)))))
            preimages)))
        expected (try
                   (let [execution-mutations (transition-execution-mutations previous successor preimages)
                         mutation-root (execution-mutation-root execution-mutations)
                         preimages-root (decision-preimages-root preimages)]
                     {:authority-transition/instance-root (genesis/chain-instance-genesis-root chain-instance)
                      :authority-transition/adapter-address (get-in chain-instance [:governance :authority-adapter])
                      :authority-transition/previous-root (authority-state-root previous)
                      :authority-transition/successor-root (authority-state-root successor)
                      :authority-transition/current-policy-root (local-authority-policy-root current-policy)
                      :authority-transition/successor-policy-root (local-authority-policy-root successor-policy)
                      :authority-transition/previous-revision (:authority/revision previous)
                      :authority-transition/successor-revision (:authority/revision successor)
                      :authority-transition/mutations (authority-mutations previous successor)
                      :authority-transition/execution-mutations execution-mutations
                      :authority-transition/execution-mutation-root mutation-root
                      :authority-transition/decision-preimages-root preimages-root
                      :authority-transition/execution-commitment
                      (execution-commitment chain-instance previous successor current-policy
                                            mutation-root preimages-root)})
                   (catch Exception error {:error (.getMessage error)}))
        binding-errors (if (:error expected)
                         [(:error expected)]
                         (vec (for [[field value] expected
                                    :when (not= value (get envelope field))]
                                (str "transition envelope binding mismatch: " field))))
        policy-replacement-error (if (= current-policy successor-policy) []
                                     ["policy/governor replacement is unsupported by transition envelope v1"])
        errors (vec (concat shape-errors base instance-errors preimage-errors
                            binding-errors policy-replacement-error))]
    {:valid? (empty? errors) :errors errors}))

(defn sign-authority-transition-envelope
  "Attach a policy-designated Ed25519 attestation over the exact fixed-width
  EVM execution commitment. The signature bytes are passed to the EVM validator."
  [envelope policy private-key]
  (when-not (and (= "local-authority-policy.v2" (:authority-policy/schema policy))
                 (= "authority-state-transition.v1" (:authority-transition/schema envelope)))
    (throw (ex-info "transition attestation requires a v2 local authority policy and v1 transition envelope" {})))
  (let [commitment (:authority-transition/execution-commitment envelope)
        raw (when (and (string? commitment) (re-matches #"^0x[0-9a-f]{64}$" commitment))
              (canonical/hex->bytes (subs commitment 2)))]
    (when-not raw (throw (ex-info "transition envelope has no canonical EVM execution commitment" {})))
    (assoc envelope :signature
           {:schema-version transition-attestation-schema
            :key-id (:authority/transition-validator-key-id policy)
            :algorithm :ed25519
            :execution-commitment commitment
            :signature-encoding :hex
            :signature-bytes (signed/ed25519-sign-bytes raw private-key)})))

(defn attach-authority-transition-ecdsa-attestation
  "Attach a personal-sign secp256k1 signature for policy v3. Signing remains
  outside PRF so deployment key custody is supplied by the configured signer."
  [envelope policy signature-hex]
  (when-not (and (= "local-authority-policy.v3" (:authority-policy/schema policy))
                 (= "authority-state-transition.v1" (:authority-transition/schema envelope))
                 (string? signature-hex)
                 (re-matches #"^0x[0-9a-fA-F]{130}$" signature-hex))
    (throw (ex-info "EVM transition attestation requires policy v3, a v1 transition envelope and 65-byte hex signature" {})))
  (assoc envelope :signature
         {:schema-version "prf-authority-state-transition-ecdsa-attestation.v1"
          :key-id (:authority/transition-validator-key-id policy)
          :algorithm :ecdsa-secp256k1
          :execution-commitment (:authority-transition/execution-commitment envelope)
          :signature-encoding :hex
          :signature-bytes signature-hex}))

(defn verify-authority-transition-attestation
  "Verify both transition semantics and the policy-pinned validator signature.
  The trust policy is independently provisioned; its matching active public key
  must match the key committed by local-authority-policy.v2 or v3."
  [envelope previous successor current-policy successor-policy chain-instance trust-policy]
  (let [policy-validity (validate-local-authority-policy current-policy)
        policy-root (when (:valid? policy-validity) (local-authority-policy-root current-policy))
        semantic (validate-authority-transition-envelope (dissoc envelope :signature)
                                                         previous successor current-policy
                                                         successor-policy chain-instance)
        signature (:signature envelope)
        key-id (:key-id signature)
        trusted-entry (some #(when (= (:key/id %) key-id) %) (:trusted-keys trust-policy))
        commitment (:authority-transition/execution-commitment envelope)
        commitment-bytes (when (and (string? commitment) (re-matches #"^0x[0-9a-f]{64}$" commitment))
                           (canonical/hex->bytes (subs commitment 2)))
        policy-schema (:authority-policy/schema current-policy)
        signature-valid?
        (and (= commitment (:execution-commitment signature))
             (let [keys (:trusted-keys trust-policy)
                   ids (mapv :key/id keys)]
               (and (vector? keys) (every? some? ids)
                    (= (count ids) (count (distinct ids)))))
             (= :active (:key/status trusted-entry))
             (= transition-validator-role (:key/role trusted-entry))
             (case policy-schema
               "local-authority-policy.v2"
               (and (= transition-attestation-schema (:schema-version signature))
                    (= :ed25519 (:algorithm signature))
                    (string? (:signature-bytes signature))
                    commitment-bytes
                    (signed/ed25519-verify-bytes commitment-bytes
                                                 (:signature-bytes signature)
                                                 (:key/public trusted-entry)))
               "local-authority-policy.v3"
               (and (= "prf-authority-state-transition-ecdsa-attestation.v1" (:schema-version signature))
                    (= :ecdsa-secp256k1 (:algorithm signature))
                    (string? (:signature-bytes signature))
                    (try
                      (= (:authority/transition-validator-address current-policy)
                         (evm/recover-ethereum-personal-sign-address commitment (:signature-bytes signature)))
                      (catch Exception _ false)))
               false))
        signature-check {:valid? signature-valid?
                         :key-id key-id
                         :reason (when-not signature-valid? :invalid-transition-attestation)}
        key-binding? (case policy-schema
                       "local-authority-policy.v2"
                       (and (= (:authority/transition-validator-key-id current-policy) (some-> key-id name))
                            (= (:authority/transition-validator-public-key current-policy) (:key/public trusted-entry)))
                       "local-authority-policy.v3"
                       (and (= (:authority/transition-validator-key-id current-policy) (some-> key-id name))
                            (= (:authority/transition-validator-address current-policy) (:key/evm-address trusted-entry)))
                       false)
        valid? (and (:valid? policy-validity) (:valid? semantic)
                    (:valid? signature-check) key-binding?)]
    {:valid? valid?
     :qualification (if valid? :authenticated-transition-attestation :rejected)
     :policy-root policy-root
     :key-id (:key-id signature-check)
     :errors (vec (concat (:errors semantic)
                          (when-not (:valid? policy-validity) (:errors policy-validity))
                          (when-not (:valid? signature-check)
                            [(str "attestation signature: " (:reason signature-check))])
                          (when-not key-binding? ["attestor does not match the key committed by the local authority policy"])))}))
