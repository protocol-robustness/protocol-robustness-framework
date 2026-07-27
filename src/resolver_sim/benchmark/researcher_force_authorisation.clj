(ns resolver-sim.benchmark.researcher-force-authorisation
  "Research force-authorisation: a content-addressed, immutably-final
   three-member authorisation artifact.

   This implements a strict policy/instance split:
     - POLICY: defines member-count, threshold, scope rules, single-use,
       dissent-preservation, expiry. Stored independently in the run-layer
       force-authorisation-policy artifact; referenced here by portable identity.
     - INSTANCE: an immutable final artifact approved once signed researcher
       decisions have been collected. No mutable state, no incremental
       approval.

   The artifact records the authorisation id, policy reference, review-round
   identity, target, decision references with verifiable signatures,
   threshold counts, and the immutable decision status — which must not be
   confused with runtime usability (expired, consumed, revoked, scope-mismatch).

   A force-authorisation is NOT the same as a consensus certificate.
   Certificate consensus says 'the model supports the conclusions.'
   Force-authorisation says 'exceptional permission was granted to
   deviate from the established benchmark protocol.' They are independent
   truth domains."
  (:require [clojure.set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.signing :as signing]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Constants
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const schema-version "researcher-force-authorisation.v1")

(def ^:const decision-schema-version "researcher-decision.v1")

(def ^:const decision-statuses
  "Immutable decision statuses. These reflect the final determination
   of the collected signed decisions — not runtime state."
  #{:approved :approved-with-dissent :declined})

(def ^:const decision-vocabulary
  "Valid values for individual member decisions."
  #{:approve :dissent})

(def ^:const target-kinds
  "Controlled vocabulary for authorisation target kinds."
  #{:benchmark-branch :benchmark-registry-update :benchmark-parameter-override
    :benchmark-model-supersede :governance-mandated :emergency})

(def ^:const policy-reference-fields
  "Fields required in :authorisation/policy reference map."
  #{:policy/id :policy/version :policy/schema-version :policy/hash})

(def ^:const round-reference-fields
  "Fields required in :authorisation/review-round reference map."
  #{:review-round/id :review-round/hash})

(def ^:const target-required-fields
  "Fields required in :authorisation/target map."
  #{:target/kind :target/baseline-content-root
    :target/branch-descriptor-hash :target/proposed-content-root})

(def ^:const decision-reference-fields
  "Fields required in each :authorisation/decision-references entry."
  #{:researcher/id :decision :decision/hash :signature})

(def ^:const signature-fields
  "Fields required in :signature map within each decision reference."
  #{:algorithm :value :signed-at})

;; ═══════════════════════════════════════════════════════════════════════════
;; Vocabulary predicates
;; ═══════════════════════════════════════════════════════════════════════════

(defn valid-decision-status?
  [s]
  (contains? decision-statuses s))

(defn valid-decision?
  [d]
  (contains? decision-vocabulary d))

(defn valid-target-kind?
  [t]
  (contains? target-kinds t))

;; ═══════════════════════════════════════════════════════════════════════════
;; Decision signing helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn- decision-preimage
  "Construct the canonical preimage for a researcher decision.
   This preimage is domain-hashed to produce :decision/hash.
   The hash is then stripped of the sha256: prefix and signed."
  [researcher-id authorisation-id decision dissent-reason]
  (cond-> {:researcher/id researcher-id
           :authorisation/id authorisation-id
           :decision decision}
    (and (= :dissent decision) (some? dissent-reason))
    (assoc :dissent/reason dissent-reason)))

(defn- compute-decision-hash
  "Compute the content-addressed hash of a decision preimage."
  [preimage]
  (str "sha256:" (hc/domain-hash :researcher-decision preimage)))

(defn build-signed-decision
  "Build a signed researcher decision artifact.

   researcher-id       — string identifying the researcher
   authorisation-id    — qualified keyword identifying the force-authorisation
   decision            — :approve or :dissent
   private-key-path    — path to the researcher's Ed25519 private key
   dissent-reason      — required when decision is :dissent (optional)
   password            — optional key password

   Returns the signed decision reference map:
     {:researcher/id id
      :decision :approve | :dissent
      :dissent/reason (only for dissents)
      :decision/hash \"sha256:...\"
      :signature {:algorithm :ed25519 :value \"...\" :signed-at \"...\"}}

   Throws on missing private key or signing error."
  [researcher-id authorisation-id decision private-key-path
   & {:keys [dissent-reason password]}]
  (when-not (valid-decision? decision)
    (throw (ex-info "Invalid decision value" {:decision decision
                                              :allowed decision-vocabulary})))
  (when (and (= :dissent decision) (nil? dissent-reason))
    (throw (ex-info "Dissent requires a reason" {})))
  (let [preimage (decision-preimage researcher-id authorisation-id
                                    decision dissent-reason)
        d-hash (compute-decision-hash preimage)
        stripped (clojure.string/replace d-hash #"^sha256:" "")
        signature (signing/sign-hash stripped private-key-path password)]
    (cond-> {:researcher/id researcher-id
             :decision decision
             :decision/hash d-hash
             :signature {:algorithm :ed25519
                         :value signature
                         :signed-at (str (java.time.Instant/now))}}
      dissent-reason (assoc :dissent/reason dissent-reason))))

(defn verify-signed-decision
  "Verify a signed decision reference against a public key.

   decision-ref        — the decision reference map from decision-references
   authorisation-id    — the authorisation id (to reconstruct preimage)
   public-key-path     — path to the researcher's Ed25519 public key

   Returns {:valid? true} or {:valid? false :reason str}."
  [decision-ref authorisation-id public-key-path]
  (let [signature (:signature decision-ref)]
    (if-not signature
      {:valid? false :reason "no signature present"}
      (let [preimage (decision-preimage
                      (:researcher/id decision-ref)
                      authorisation-id
                      (:decision decision-ref)
                      (:dissent/reason decision-ref))
            expected-hash (compute-decision-hash preimage)
            actual-hash (:decision/hash decision-ref)]
        (if-not (= expected-hash actual-hash)
          {:valid? false :reason "decision/hash mismatch"}
          (try
            (let [stripped (clojure.string/replace actual-hash #"^sha256:" "")
                  valid? (signing/verify-signature
                          stripped (:value signature) public-key-path)]
              {:valid? valid?
               :reason (when-not valid? "signature does not verify")})
            (catch Exception e
              {:valid? false
               :reason (str "signature verification error: "
                            (.getMessage e))})))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Pre-authorisation checks
;; ═══════════════════════════════════════════════════════════════════════════

(defn pre-authorisation-checks
  "Pre-condition validation before building a force-authorisation artifact.

   Verifies:
     1. Review-round reference is valid (id, hash present)
     2. Policy reference is valid (all fields present)
     3. Target is valid (required fields, kind in vocabulary)
     4. Request-root is present
     5. All decision references have required fields
     6. No researcher appears in both approvals and dissents
     7. All decisions are valid (:approve or :dissent)
     8. Dissents have reasons

   Returns {:pre-auth-valid? bool :errors [string]}."
  [{:keys [authorisation/policy
           authorisation/review-round
           authorisation/request-root
           authorisation/target
           authorisation/decision-references]}]
  (let [errors (atom [])]
    ;; 1. Review-round
    (when-not (map? review-round)
      (swap! errors conj "missing :authorisation/review-round"))
    (when (map? review-round)
      (doseq [f round-reference-fields]
        (when-not (contains? review-round f)
          (swap! errors conj (str "review-round missing " (name f))))))
    ;; 2. Policy reference
    (when-not (map? policy)
      (swap! errors conj "missing :authorisation/policy"))
    (when (map? policy)
      (doseq [f policy-reference-fields]
        (when-not (contains? policy f)
          (swap! errors conj (str "policy reference missing " (name f))))))
    ;; 3. Target
    (when-not (map? target)
      (swap! errors conj "missing :authorisation/target"))
    (when (map? target)
      (doseq [f target-required-fields]
        (when-not (contains? target f)
          (swap! errors conj (str "target missing " (name f)))))
      (let [kind (:target/kind target)]
        (when (and kind (not (valid-target-kind? kind)))
          (swap! errors conj (str "invalid target kind: " kind)))))
    ;; 4. Request-root
    (when-not (some? request-root)
      (swap! errors conj "missing :authorisation/request-root"))
    ;; 5. Decision references
    (when-not (sequential? decision-references)
      (swap! errors conj "missing :authorisation/decision-references"))
    (when (sequential? decision-references)
      (let [researcher-ids (map :researcher/id decision-references)
            unique-ids (set researcher-ids)]
        (when-not (= (count researcher-ids) (count unique-ids))
          (swap! errors conj "duplicate researcher/id in decision-references"))
        (doseq [d decision-references]
          (doseq [f decision-reference-fields]
            (when-not (contains? d f)
              (swap! errors conj (str "decision-reference missing " (name f)))))
          (when-not (valid-decision? (:decision d))
            (swap! errors conj (str "invalid decision: " (:decision d))))
          (when (and (= :dissent (:decision d)) (nil? (:dissent/reason d)))
            (swap! errors conj "dissent missing :dissent/reason")))))
    {:pre-auth-valid? (empty? @errors) :errors @errors}))
(defn build-authorisation
  "Build a final, immutable research force-authorisation artifact.

   Required:
     authorisation/id                — qualified keyword identifying this auth
     authorisation/policy            — portable policy reference map:
                                       {:policy/id kw
                                        :policy/version int
                                        :policy/schema-version str
                                        :policy/hash sha256}
     authorisation/review-round      — review-round reference map:
                                       {:review-round/id kw
                                        :review-round/hash sha256}
     authorisation/request-root      — sha256 of the authorisation request artifact
     authorisation/target            — target commitment map:
                                       {:target/kind kw
                                        :target/baseline-content-root sha256
                                        :target/branch-descriptor-hash sha256
                                        :target/proposed-content-root sha256}
     authorisation/decision-references — vector of signed decision maps
                                       [{:researcher/id id
                                         :decision :approve | :dissent
                                         :decision/hash sha256
                                         :signature {:algorithm :ed25519
                                                     :value hex
                                                     :signed-at iso}}
                                        ...]
     authorisation/threshold         — {:required int :eligible int}
                                       (approved/dissented are computed)

   Optional:
     authorisation/command-root      — sha256 of research-command.v1 (when available)
     authorisation/evidence-root     — sha256 of supporting evidence (policy-governed)
     authorisation/valid-from        — ISO-8601 string (defaults to now)
     authorisation/expires-at        — ISO-8601 string
     authorisation/hash              — pre-computed hash (rejected on mismatch)

   Returns the finalised authorisation artifact with:
     - :authorisation/threshold populated with computed counts
     - :authorisation/decision-status deduced from threshold vs decisions
     - :authorisation/consumption-key computed
     - :authorisation/hash computed

   Throws on invalid inputs. Does NOT verify decision signatures —
   that is a separate runtime check (see verify-decision-signatures)."
  [{:keys [authorisation/id
           authorisation/policy
           authorisation/review-round
           authorisation/request-root
           authorisation/target
           authorisation/decision-references
           authorisation/threshold
           authorisation/command-root
           authorisation/evidence-root
           authorisation/valid-from
           authorisation/expires-at
           authorisation/hash]}]
  (let [pre-checks (pre-authorisation-checks
                    {:authorisation/policy policy
                     :authorisation/review-round review-round
                     :authorisation/request-root request-root
                     :authorisation/target target
                     :authorisation/decision-references decision-references})]
    (when-not (:pre-auth-valid? pre-checks)
      (throw (ex-info "Force-authorisation pre-conditions not met"
                      {:errors (:errors pre-checks)})))
    (let [required (:required threshold)
          eligible (:eligible threshold)
          _ (when-not (and (integer? required) (>= required 1))
              (throw (ex-info ":authorisation/threshold :required must be >= 1"
                              {:required required})))
          _ (when-not (and (integer? eligible) (>= eligible required))
              (throw (ex-info ":authorisation/threshold :eligible must be >= :required"
                              {:required required :eligible eligible})))
          approved (count (filter #(= :approve (:decision %)) decision-references))
          dissented (count (filter #(= :dissent (:decision %)) decision-references))
          decision-status (cond
                            (and (>= approved required) (zero? dissented)) :approved
                            (and (>= approved required) (pos? dissented)) :approved-with-dissent
                            :else :declined)
          threshold-map {:required required
                         :eligible eligible
                         :approved approved
                         :dissented dissented}
          consumption-key
          (str "sha256:"
               (hc/domain-hash :evidence-collection
                               {:auth/id id
                                :auth/policy-hash (:policy/hash policy)
                                :auth/target target}))
          base {:schema-version schema-version
                :authorisation/id id
                :authorisation/policy policy
                :authorisation/review-round review-round
                :authorisation/request-root request-root
                :authorisation/target target
                :authorisation/decision-references
                (vec decision-references)
                :authorisation/threshold threshold-map
                :authorisation/decision-status decision-status
                :authorisation/consumption-key consumption-key
                :authorisation/valid-from
                (or valid-from (str (java.time.Instant/now)))
                :authorisation/expires-at expires-at
                :authorisation/command-root command-root
                :authorisation/evidence-root evidence-root}
          computed-hash (str "sha256:"
                             (hc/domain-hash :research-force-authorisation base))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared authorisation/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :authorisation/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Accessors (compatibility helpers for verdict-policy.clj)
;; ═══════════════════════════════════════════════════════════════════════════

(defn authorisation-status
  "Return the immutable decision-status of a force-authorisation artifact.
   Compat helper: unchanged signature."
  [instance]
  (:authorisation/decision-status instance))

(defn authorisation-approved?
  "True when the artifact's decision-status is :approved or
   :approved-with-dissent. Compat helper: unchanged signature."
  [instance]
  (contains? #{:approved :approved-with-dissent}
             (:authorisation/decision-status instance)))

(defn authorisation-valid?
  "Quick structural validity check for builder-produced artifacts.
   Compat helper: unchanged signature."
  [instance]
  (and (= schema-version (:schema-version instance))
       (some? (:authorisation/id instance))
       (some? (:authorisation/hash instance))
       (some? (:authorisation/decision-status instance))
       (valid-decision-status? (:authorisation/decision-status instance))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-authorisation
  "Standalone validator for a loaded force-authorisation artifact.
   Recomputes the hash, checks all required fields, and verifies
   structural integrity.

   Returns {:valid? bool :errors [string]}."
  [instance]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version instance))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version instance))))
    (when-not (some? (:authorisation/id instance))
      (swap! errors conj "missing :authorisation/id"))
    (when-not (some? (:authorisation/hash instance))
      (swap! errors conj "missing :authorisation/hash"))
    (let [ds (:authorisation/decision-status instance)]
      (when-not (valid-decision-status? ds)
        (swap! errors conj (str "invalid :authorisation/decision-status: " ds))))
    (let [th (:authorisation/threshold instance)]
      (when (map? th)
        (when-not (integer? (:required th))
          (swap! errors conj "threshold :required must be an integer"))
        (when-not (integer? (:eligible th))
          (swap! errors conj "threshold :eligible must be an integer"))
        (when-not (integer? (:approved th))
          (swap! errors conj "threshold :approved must be an integer"))
        (when-not (integer? (:dissented th))
          (swap! errors conj "threshold :dissented must be an integer"))))
    (when (some? (:authorisation/hash instance))
      (let [without-hash (dissoc instance :authorisation/hash)
            computed (str "sha256:"
                          (hc/domain-hash :research-force-authorisation without-hash))]
        (when-not (= computed (:authorisation/hash instance))
          (swap! errors conj (str "authorisation/hash mismatch: declared "
                                  (:authorisation/hash instance)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Cross-artifact verification
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-against-policy
  "Verify that an authorisation artifact's decision-references satisfy
   the referenced policy's rules.

   policy          — the fully resolved policy map (from run-layer artifact)
   authorisation    — the force-authorisation artifact

   The policy reference in the authorisation contains the policy hash.
   This function checks:
     1. The resolved policy's hash matches the reference's hash
     2. The policy's member-count >= authorisation's :eligible count
     3. The policy's threshold <= authorisation's :required count
     4. Policy flags are compatible (single-use, preserve-dissent, etc.)

   Returns {:valid? bool :errors [string]}."
  [policy authorisation]
  (let [errors (atom [])
        threshold (:authorisation/threshold authorisation)]
    ;; Check threshold against policy
    (let [policy-mc (get policy "member_count")
          policy-th (get policy "threshold")]
      (when (and policy-mc (< policy-mc (:eligible threshold)))
        (swap! errors conj (str "policy member-count " policy-mc
                                " is less than eligible " (:eligible threshold))))
      (when (and policy-th (> policy-th (:required threshold)))
        (swap! errors conj (str "policy threshold " policy-th
                                " is greater than required " (:required threshold)))))
    ;; Check policy flags
    (when (and (get policy "single_use?" true)
               (nil? (:authorisation/consumption-key authorisation)))
      (swap! errors conj "single-use policy requires consumption-key"))
    (when (and (get policy "preserve_dissent?" true)
               (pos? (:dissented threshold))
               (not= :approved-with-dissent
                     (:authorisation/decision-status authorisation)))
      (swap! errors conj "policy requires preserved dissent but status does not reflect it"))
    {:valid? (empty? @errors) :errors @errors}))

(defn verify-against-round
  "Verify that an authorisation artifact's decision-references correspond
   to members of the referenced review-round.

   review-round    — the resolved review-round map
   authorisation   — the force-authorisation artifact

   Checks:
     1. :authorisation/review-round hash matches the resolved round's hash
     2. All researchers in decision-references are members of the round
     3. No researcher appears in both approvals and dissents

   Returns {:valid? bool :errors [string]}."
  [review-round authorisation]
  (let [errors (atom [])
        member-ids (set (map :researcher/id
                             (:review-round/members review-round)))
        decision-refs (:authorisation/decision-references authorisation)
        decider-ids (map :researcher/id decision-refs)]
    ;; Check all deciders are members
    (doseq [id decider-ids]
      (when-not (contains? member-ids id)
        (swap! errors conj (str "researcher " id " is not a member of review-round"))))
    ;; Check no overlap between approvals and dissents
    (let [approvers (set (map :researcher/id
                              (filter #(= :approve (:decision %))
                                      decision-refs)))
          dissenters (set (map :researcher/id
                               (filter #(= :dissent (:decision %))
                                       decision-refs)))
          overlap (clojure.set/intersection approvers dissenters)]
      (when (seq overlap)
        (swap! errors conj (str "researchers in both approve and dissent: " overlap))))
    {:valid? (empty? @errors) :errors @errors}))

(defn verify-decision-signatures
  "Verify that every decision reference in an authorisation artifact
   carries a valid signature.

   public-key-resolver — function (researcher-id) -> public-key-path
   authorisation       — the force-authorisation artifact

   Fails closed: if the resolver throws, the affected researcher is
   reported as :valid? false with a 'resolver error' reason.

   Returns {:valid? bool :results [{:researcher/id id
                                    :decision kw
                                    :valid? bool
                                    :reason str}]}"
  [public-key-resolver authorisation]
  (let [auth-id (:authorisation/id authorisation)
        results (mapv
                 (fn [d]
                   (try
                     (let [pub-key-path (public-key-resolver
                                         (:researcher/id d))
                           result (verify-signed-decision
                                   d auth-id pub-key-path)]
                       {:researcher/id (:researcher/id d)
                        :decision (:decision d)
                        :valid? (:valid? result)
                        :reason (:reason result)})
                     (catch Exception e
                       {:researcher/id (:researcher/id d)
                        :decision (:decision d)
                        :valid? false
                        :reason (str "resolver error: " (.getMessage e))})))
                 (:authorisation/decision-references authorisation))]
    {:valid? (every? :valid? results)
     :results results}))

(defn verify-authorisation-usable
  "Runtime verification — NOT part of the immutable artifact.
   
   Determines whether a force-authorisation artifact is usable RIGHT NOW.

   Checks:
     1. The artifact is structurally valid (validate-authorisation)
     2. The artifact is approved (authorisation-approved?)
     3. Not expired (expires-at is in the future, if set)
     4. Not yet valid (valid-from is in the past, if set)
     5. Not already consumed (via consumption-registry callback)
     6. Not revoked (via revocation-registry callback)
     7. Not scope-mismatched (the proposed action matches the target)

   Expiry boundary: expires-at is the last valid instant (inclusive).
   If now == expires-at the authorisation is NOT yet expired.
   Comparison: (.isBefore expires now) — strictly before.

   Valid-from boundary: valid-from is the first valid instant (inclusive).
   If now == valid-from the authorisation IS valid.
   Comparison: (.isAfter valid-from now) — strictly after.

   consumption-checker — fn (consumption-key) -> consumed? bool
   revocation-checker  — fn (authorisation-id) -> revoked? bool
   scope-validator     — optional fn (authorisation, proposed-action) -> mismatch? str/nil

   Returns {:usable? bool :checks {:valid? bool :approved? bool ...}
            :blocking-reasons [str]}"
  [authorisation & {:keys [consumption-checker revocation-checker scope-validator]
                    :or {consumption-checker (constantly false)
                         revocation-checker (constantly false)}}]
  (let [checks (atom {})
        reasons (atom [])]
    ;; 1. Structural validity
    (let [v (validate-authorisation authorisation)]
      (swap! checks assoc :valid? (:valid? v))
      (when-not (:valid? v)
        (swap! reasons conj "authorisation is not structurally valid")))
    ;; 2. Approved
    (let [approved? (authorisation-approved? authorisation)]
      (swap! checks assoc :approved? approved?)
      (when-not approved?
        (swap! reasons conj "authorisation decision-status is not approved")))
    ;; 3. Expiry
    (let [expires (:authorisation/expires-at authorisation)
          expired? (and (some? expires)
                        (.isBefore (java.time.Instant/parse expires)
                                   (java.time.Instant/now)))]
      (swap! checks assoc :expired? expired?)
      (when expired?
        (swap! reasons conj "authorisation has expired")))
    ;; 4. Valid-from
    (let [valid-from (:authorisation/valid-from authorisation)
          not-yet-valid? (and (some? valid-from)
                              (.isAfter (java.time.Instant/parse valid-from)
                                        (java.time.Instant/now)))]
      (swap! checks assoc :not-yet-valid? not-yet-valid?)
      (when not-yet-valid?
        (swap! reasons conj "authorisation is not yet valid")))
    ;; 5. Consumption
    (let [ck (:authorisation/consumption-key authorisation)
          consumed? (and (some? ck) (consumption-checker ck))]
      (swap! checks assoc :consumed? consumed?)
      (when consumed?
        (swap! reasons conj "authorisation has already been consumed")))
    ;; 6. Revocation
    (let [revoked? (revocation-checker (:authorisation/id authorisation))]
      (swap! checks assoc :revoked? revoked?)
      (when revoked?
        (swap! reasons conj "authorisation has been revoked")))
    ;; 7. Scope mismatch
    (when scope-validator
      (let [mismatch (scope-validator authorisation)]
        (swap! checks assoc :scope-mismatch? (some? mismatch))
        (when mismatch
          (swap! reasons conj (str "scope mismatch: " mismatch)))))
    {:usable? (empty? @reasons)
     :checks @checks
     :blocking-reasons (vec @reasons)}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Consumption-key helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn consumption-key
  "Return the single-use consumption key for a force-authorisation artifact."
  [instance]
  (:authorisation/consumption-key instance))

;; ═══════════════════════════════════════════════════════════════════════════
;; Reservation artifact (pre-execution)
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; Created BEFORE execution. The outcome manifest references this reservation
;; by hash.  The terminal consumption receipt (created AFTER execution)
;; references the outcome manifest hash — no circular commitment.

(def ^:const reservation-schema-version
  "force-authorisation-reservation.v1")

(defn build-reservation
  "Build a reservation artifact — created before execution to commit to
   the authorisation, command, plan, and execution attempt.

   Acyclic construction order:
     1. build-reservation (pre-execution)
     2. outcome manifest with :reservation-hash (post-execution)
     3. build-consumption-receipt references outcome hash (terminal)

   Required:
     reservation/authorisation-hash   — sha256 of the FA artifact
     reservation/consumption-key      — the deterministic consumption key
     reservation/execution-attempt-id — qualified keyword
     reservation/command-root         — sha256 of research-command.v1
     reservation/plan-root            — sha256 of execution plan

   Optional:
     reservation/hash                 — pre-computed hash (rejected on mismatch)

   Returns the reservation artifact with :reservation/hash."
  [{:keys [reservation/authorisation-hash
           reservation/consumption-key
           reservation/execution-attempt-id
           reservation/command-root
           reservation/plan-root
           reservation/hash]}]
  (let [errors (atom [])]
    (when-not (some? authorisation-hash)
      (swap! errors conj "missing :reservation/authorisation-hash"))
    (when-not (some? consumption-key)
      (swap! errors conj "missing :reservation/consumption-key"))
    (when-not (some? execution-attempt-id)
      (swap! errors conj "missing :reservation/execution-attempt-id"))
    (when-not (some? command-root)
      (swap! errors conj "missing :reservation/command-root"))
    (when-not (some? plan-root)
      (swap! errors conj "missing :reservation/plan-root"))
    (when (seq @errors)
      (throw (ex-info "Reservation build failed" {:errors @errors})))
    (let [base {:schema-version reservation-schema-version
                :reservation/authorisation-hash authorisation-hash
                :reservation/consumption-key consumption-key
                :reservation/execution-attempt-id execution-attempt-id
                :reservation/command-root command-root
                :reservation/plan-root plan-root}
          computed-hash (str "sha256:"
                             (hc/domain-hash :force-authorisation-reservation base))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared reservation/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :reservation/hash computed-hash))))

(defn reservation-valid?
  "Quick structural validity check for a reservation artifact."
  [r]
  (and (= reservation-schema-version (:schema-version r))
       (some? (:reservation/authorisation-hash r))
       (some? (:reservation/consumption-key r))
       (some? (:reservation/hash r))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Terminal consumption receipt artifact (post-execution)
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; Created AFTER the outcome manifest.  References the reservation hash
;; (not the manifest hash — no cycle) and the resulting outcome hash
;; (one-way reference, acyclic).

;; Status/root rules:
;;   Status                          resulting-outcome-hash   terminal-evidence-hash
;;   :consumed                       required                  optional
;;   :failed-after-consumption       optional                  required (or :not-captured)
;;   :rolled-back-after-consumption  required                  required
;;
;; For :failed-after-consumption, :terminal-evidence-hash must be present.
;; When no failure artifact was captured (e.g. runner terminated before
;; evidence could be written), use the explicit status:
;;   {:status :not-captured :reason-code kw}
;; so the absence is semantic, not ambiguous.

(def ^:const receipt-schema-version
  "force-authorisation-consumption.v1")

(def ^:const receipt-statuses
  "Terminal consumption statuses.  All are terminal — reuse is not permitted.
   :consumed                     — execution completed successfully
   :failed-after-consumption     — reserved, then execution failed
   :rolled-back-after-consumption — reserved, consumed, then rolled back
                                   (the attempt occurred even if reversed)"
  #{:consumed :failed-after-consumption :rolled-back-after-consumption})

(defn valid-receipt-status?
  [s]
  (contains? receipt-statuses s))

(def ^:private receipt-status-rules
  "Status/root validation rules.
   For :failed-after-consumption, :terminal-evidence-hash must be present.
   If none was captured, use {:status :not-captured :reason-code kw}
   to make the absence explicit rather than ambiguous."
  {:consumed                      {:outcome-hash :required :terminal-evidence :optional}
   :failed-after-consumption      {:outcome-hash :optional :terminal-evidence :required}
   :rolled-back-after-consumption {:outcome-hash :required :terminal-evidence :required}})

(defn build-consumption-receipt
  "Build a terminal consumption receipt — created AFTER the outcome manifest
   to avoid circular commitments.

   The outcome manifest references the reservation hash (pre-execution).
   The receipt references the outcome manifest hash (post-execution).
   Order: reservation → outcome → receipt.

   Status/root rules:
     :consumed                     → :consumption/resulting-outcome-hash required
     :failed-after-consumption     → :consumption/resulting-outcome-hash optional
     :rolled-back-after-consumption → :terminal-evidence-hash required

   Required:
     consumption/reservation-hash       — sha256 of the reservation artifact
     consumption/authorisation-hash     — sha256 of the FA artifact
     consumption/consumption-key        — the deterministic consumption key
     consumption/status                 — :consumed | :failed-after-consumption
                                        | :rolled-back-after-consumption

   Conditional:
     consumption/resulting-outcome-hash — required for :consumed and
                                         :rolled-back-after-consumption;
                                         optional for :failed-after-consumption
     consumption/terminal-evidence-hash — sha256 of failure/rollback evidence;
                                         required for :rolled-back-after-consumption

   Optional:
     consumption/hash                   — pre-computed hash (rejected on mismatch)

   Returns the receipt artifact with :consumption/hash."
  [{:keys [consumption/reservation-hash
           consumption/authorisation-hash
           consumption/consumption-key
           consumption/resulting-outcome-hash
           consumption/terminal-evidence-hash
           consumption/status
           consumption/hash]}]
  (let [errors (atom [])
        rule (get receipt-status-rules status)]
    (when-not (some? reservation-hash)
      (swap! errors conj "missing :consumption/reservation-hash"))
    (when-not (some? authorisation-hash)
      (swap! errors conj "missing :consumption/authorisation-hash"))
    (when-not (some? consumption-key)
      (swap! errors conj "missing :consumption/consumption-key"))
    (when-not (and (some? status) (valid-receipt-status? status))
      (swap! errors conj (str "invalid or missing :consumption/status: " status)))
    (when (and rule (= :required (:outcome-hash rule))
               (nil? resulting-outcome-hash))
      (swap! errors conj (str "status " status " requires :consumption/resulting-outcome-hash")))
    (when (and rule (= :required (:terminal-evidence rule))
               (nil? terminal-evidence-hash))
      (swap! errors conj (str "status " status " requires :consumption/terminal-evidence-hash")))
    (when (seq @errors)
      (throw (ex-info "Consumption receipt build failed"
                      {:errors @errors})))
    (let [base (merge {:schema-version receipt-schema-version
                       :consumption/reservation-hash reservation-hash
                       :consumption/authorisation-hash authorisation-hash
                       :consumption/consumption-key consumption-key
                       :consumption/resulting-outcome-hash resulting-outcome-hash
                       :consumption/status status}
                      (when terminal-evidence-hash
                        {:consumption/terminal-evidence-hash terminal-evidence-hash}))
          computed-hash (str "sha256:"
                             (hc/domain-hash :force-authorisation-consumption base))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared consumption/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :consumption/hash computed-hash))))

(defn receipt-valid?
  "Quick structural validity check for a consumption receipt artifact."
  [r]
  (and (= receipt-schema-version (:schema-version r))
       (some? (:consumption/reservation-hash r))
       (some? (:consumption/resulting-outcome-hash r))
       (some? (:consumption/hash r))
       (some? (:consumption/status r))
       (valid-receipt-status? (:consumption/status r))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Reservation / Consumption flow helpers
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; In-process atomic reservation registry.
;;
;; ATOMICITY BOUNDARY: compare-and-set! on a Clojure atom proves correct
;; single-consumer behaviour within one JVM process.  It does NOT prove
;; durable single-use across separate runner processes, restarts,
;; distributed workers, or concurrent machines.
;;
;; The append-only reservation and consumption receipt artifacts provide
;; portable audit evidence of what was recorded.  They alone do NOT
;; prevent a distributed double execution — a durable reservation backend
;; (filesystem lock, database transaction, etc.) must enforce exclusivity
;; across processes.
;;
;; A production runner should implement the reservation interface using
;; the same three-phase pattern (reserve → execute → finalise) backed by
;; a storage-level compare-and-set or transaction.
;;
;; Flow:
;;   1. verify-authorisation-usable — precondition check
;;   2. reserve-consumption!        — atomically mark key as reserved
;;   3. build-reservation           — create pre-execution artifact
;;   4. execute authorised side effects
;;   5. build outcome manifest (references reservation hash by value)
;;   6. build-consumption-receipt   — create post-outcome terminal receipt
;;   7. finalise-consumption!       — atomically record :consumed or
;;                                    :failed-after-consumption

(defn reserve-consumption!
  "Atomically reserve a consumption key for execution.
   registration    — atom map of consumption-key -> {:status ...}
   consumption-key — the deterministic consumption key

   Returns {:reserved? true :key key} on success.
   Returns {:reserved? false :reason str} if already reserved or consumed."
  [registration consumption-key]
  (let [existing (get @registration consumption-key)]
    (if existing
      {:reserved? false
       :reason (str "consumption key already has status: "
                    (:status existing))}
      (let [reservation {:status :reserved :reserved-at (str (java.time.Instant/now))}]
        (if (compare-and-set! registration
                              (dissoc @registration consumption-key)
                              (assoc @registration consumption-key reservation))
          {:reserved? true :key consumption-key}
          (recur registration consumption-key))))))

(defn finalise-consumption!
  "Atomically finalise a reserved consumption key.
   registration    — atom map of consumption-key -> {:status ...}
   consumption-key — the deterministic consumption key
   status          — :consumed | :failed-after-consumption
                     | :rolled-back-after-consumption

   All statuses are terminal — the key cannot be reused.

   Returns {:finalised? true :status status} on success.
   Returns {:finalised? false :reason str} if not found or already finalised."
  [registration consumption-key status]
  (let [existing (get @registration consumption-key)]
    (if-not existing
      {:finalised? false :reason "no reservation found for consumption key"}
      (let [updated (assoc existing :status status
                           :finalised-at (str (java.time.Instant/now)))]
        (if (compare-and-set! registration
                              @registration
                              (assoc @registration consumption-key updated))
          {:finalised? true :status status}
          (recur registration consumption-key status))))))

(defn registration-consumed?
  "True when a consumption key is registered with a terminal status
   (any of :consumed, :failed-after-consumption,
    :rolled-back-after-consumption)."
  [registration consumption-key]
  (let [entry (get @registration consumption-key)]
    (contains? #{:consumed :failed-after-consumption
                 :rolled-back-after-consumption}
               (:status entry))))

(defn registration-reserved?
  "True when a consumption key is currently reserved (in-flight)."
  [registration consumption-key]
  (= :reserved (get-in @registration [consumption-key :status])))

;; ═══════════════════════════════════════════════════════════════════════════
;; Cross-artifact FA binding verification
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-fa-binding
  "Verify that a manifest's :execution/force-authorisation section is
   consistent with the referenced force-authorisation and reservation
   artifacts.

   Manifest ↔ Authorisation:
     - authorisation-hash
     - policy-hash
     - review-round-hash
     - request-root
     - consumption-key
     - baseline-content-root matches FA target baseline
     - branch-descriptor-hash matches FA target
     - executed-content-root equals the FA target proposed-content-root
       (or satisfies an explicit branch-resolution rule)

   Manifest ↔ Reservation:
     - reservation-hash
     - authorisation-hash (consistent)
     - consumption-key (consistent)
     - execution-attempt-id
     - command-root (consistent with reservation)
     - plan-root (consistent with reservation)

   Returns {:consistent? bool :mismatches [{:field kw :reason str}]}"
  [manifest fa-artifact reservation-artifact]
  (let [fa-sec (:execution/force-authorisation manifest)]
    (if-not fa-sec
      {:consistent? false
       :mismatches [{:field :execution/force-authorisation
                     :reason "manifest has no force-authorisation section"}]}
      (let [errors (atom [])
            ;; Manifest ↔ Authorisation
            _ (when-not (= (:authorisation-hash fa-sec)
                           (:authorisation/hash fa-artifact))
                (swap! errors conj {:field :authorisation-hash
                                    :reason "manifest does not match FA artifact"}))
            _ (when-not (= (:consumption-key fa-sec)
                           (:authorisation/consumption-key fa-artifact))
                (swap! errors conj {:field :consumption-key
                                    :reason "manifest does not match FA artifact"}))
            _ (when-not (= (:baseline-content-root fa-sec)
                           (get-in fa-artifact
                                   [:authorisation/target
                                    :target/baseline-content-root]))
                (swap! errors conj {:field :baseline-content-root
                                    :reason "manifest does not match FA target"}))
            _ (when-not (= (:branch-descriptor-hash fa-sec)
                           (get-in fa-artifact
                                   [:authorisation/target
                                    :target/branch-descriptor-hash]))
                (swap! errors conj {:field :branch-descriptor-hash
                                    :reason "manifest does not match FA target"}))
            _ (when-not (= (:executed-content-root fa-sec)
                           (get-in fa-artifact
                                   [:authorisation/target
                                    :target/proposed-content-root]))
                (swap! errors conj {:field :executed-content-root
                                    :reason "manifest executed root does not match FA proposed root"}))
            ;; Manifest ↔ Reservation
            _ (when-not (= (:reservation-hash fa-sec)
                           (:reservation/hash reservation-artifact))
                (swap! errors conj {:field :reservation-hash
                                    :reason "manifest does not match reservation artifact"}))
            _ (when-not (= (:authorisation-hash fa-sec)
                           (:reservation/authorisation-hash reservation-artifact))
                (swap! errors conj {:field :reservation-auth-hash
                                    :reason "manifest FA auth hash does not match reservation"}))
            _ (when-not (= (:consumption-key fa-sec)
                           (:reservation/consumption-key reservation-artifact))
                (swap! errors conj {:field :reservation-consumption-key
                                    :reason "manifest consumption key does not match reservation"}))
            _ (when-not (= (:execution-attempt-id fa-sec)
                           (:reservation/execution-attempt-id reservation-artifact))
                (swap! errors conj {:field :execution-attempt-id
                                    :reason "manifest attempt id does not match reservation"}))]
        {:consistent? (empty? @errors)
         :mismatches @errors}))))

(defn verify-consumption-receipt
  "Verify that a consumption receipt is consistent with the referenced
   reservation and outcome manifest.

   Receipt ↔ Reservation:
     - reservation-hash
     - authorisation-hash
     - consumption-key
     - execution-attempt-id

   Receipt ↔ Outcome manifest:
     - resulting-outcome-hash matches manifest's :benchmark-outcome/hash

   Status rules:
     :consumed                     → resulting-outcome-hash required
     :failed-after-consumption     → resulting-outcome-hash optional
     :rolled-back-after-consumption → resulting-outcome-hash required

   Returns {:consistent? bool :mismatches [{:field kw :reason str}]}"
  [receipt reservation manifest]
  (let [errors (atom [])
        r-hash (:consumption/reservation-hash receipt)
        a-hash (:consumption/authorisation-hash receipt)
        c-key (:consumption/consumption-key receipt)
        o-hash (:consumption/resulting-outcome-hash receipt)
        status (:consumption/status receipt)]
    ;; Receipt ↔ Reservation
    (when-not (= r-hash (:reservation/hash reservation))
      (swap! errors conj {:field :reservation-hash
                          :reason "receipt does not match reservation artifact"}))
    (when-not (= a-hash (:reservation/authorisation-hash reservation))
      (swap! errors conj {:field :authorisation-hash
                          :reason "receipt auth hash does not match reservation"}))
    (when-not (= c-key (:reservation/consumption-key reservation))
      (swap! errors conj {:field :consumption-key
                          :reason "receipt key does not match reservation"}))
    ;; Receipt ↔ Outcome
    (when manifest
      (when-not (= o-hash (:benchmark-outcome/hash manifest))
        (swap! errors conj {:field :resulting-outcome-hash
                            :reason "receipt outcome hash does not match manifest"})))
    ;; Status rules
    (case status
      :consumed
      (when-not (some? o-hash)
        (swap! errors conj {:field :resulting-outcome-hash
                            :reason ":consumed requires resulting-outcome-hash"}))
      :rolled-back-after-consumption
      (when-not (some? (:consumption/terminal-evidence-hash receipt))
        (swap! errors conj {:field :terminal-evidence-hash
                            :reason ":rolled-back-after-consumption requires terminal-evidence-hash"}))
      nil)
    {:consistent? (empty? @errors)
     :mismatches @errors}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Durable reservation backend protocol
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol ReservationBackend
  "Durable reservation interface for single-use consumption enforcement.
   
   A production runner implements this protocol using a filesystem lock,
   database transaction, or other durable compare-and-set mechanism.
   
   The in-process atom implementation below is correct for single-JVM
   testing but does NOT enforce exclusivity across processes, restarts,
   or distributed workers."
  (reserve! [backend consumption-key reservation]
    "Atomically reserve a consumption key.
     Returns {:reserved? true :key key} on success.
     Returns {:reserved? false :reason str} if already reserved or consumed.")
  (finalise! [backend consumption-key terminal-receipt]
    "Atomically finalise a reserved key with terminal status.
     Returns {:finalised? true :status status} on success.
     Returns {:finalised? false :reason str} if not found or already finalised.")
  (consumed? [backend consumption-key]
    "True when the key has a terminal status.")
  (read-state [backend consumption-key]
    "Returns the recorded state map or nil."))

;; ═══════════════════════════════════════════════════════════════════════════
;; Atom-based reservation backend (in-process, single-JVM)
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord AtomBackend [registry]
  ReservationBackend
  (reserve! [_ consumption-key reservation]
    (loop []
      (let [existing (get @registry consumption-key)]
        (if existing
          {:reserved? false
           :reason (str "consumption key already has status: "
                        (:status existing))}
          (let [entry (assoc reservation :status :reserved
                             :reserved-at (str (java.time.Instant/now)))]
            (if (compare-and-set! registry
                                  @registry
                                  (assoc @registry consumption-key entry))
              {:reserved? true :key consumption-key}
              (recur)))))))
  (finalise! [_ consumption-key terminal-receipt]
    (loop []
      (let [existing (get @registry consumption-key)]
        (if-not existing
          {:finalised? false
           :reason "no reservation found for consumption key"}
          (let [updated (assoc existing
                               :status (:consumption/status terminal-receipt)
                               :receipt-hash (:consumption/hash terminal-receipt)
                               :finalised-at (str (java.time.Instant/now)))]
            (if (compare-and-set! registry
                                  @registry
                                  (assoc @registry consumption-key updated))
              {:finalised? true :status (:consumption/status terminal-receipt)}
              (recur)))))))
  (consumed? [_ consumption-key]
    (let [entry (get @registry consumption-key)]
      (contains? #{:consumed :failed-after-consumption
                   :rolled-back-after-consumption} (:status entry))))
  (read-state [_ consumption-key]
    (get @registry consumption-key)))

(defn atom-backend
  "Create an in-process atom-based reservation backend.
   Returns a ReservationBackend implementation suitable for single-JVM use."
  []
  (->AtomBackend (atom {})))

;; ═══════════════════════════════════════════════════════════════════════════
;; Package completion verification for force-authorised executions
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-package-completion-force-authorised
  "Verify that a package contains the complete force-authorisation chain
   required when the manifest declares :execution/force-authorisation.

   Required artifacts in the package index:
     - policy (by hash referenced in authorisation)
     - review round (by hash referenced in authorisation)
     - force-authorisation artifact
     - reservation artifact
     - outcome manifest
     - terminal consumption receipt
     - evidence profile

   package-resolver — fn (sha256) -> resolved artifact map | nil
   manifest         — the outcome manifest
   profile          — the evidence profile (or nil, will be recomputed)

   Returns {:valid? bool :required? bool :errors [str] :checks map}"
  [package-resolver manifest profile]
  (let [fa-sec (:execution/force-authorisation manifest)]
    (if-not fa-sec
      {:valid? true :required? false
       :errors [] :checks {:has-fa-section? false}}
      (let [errors (atom [])
            checks (atom {:has-fa-section? true})
            auth-hash (:authorisation-hash fa-sec)
            auth (when auth-hash (package-resolver auth-hash))
            _ (when-not auth (swap! errors conj "authorisation not found"))
            _ (swap! checks assoc :authorisation-resolved? (some? auth))
            policy-hash (get-in auth [:authorisation/policy :policy/hash])
            policy (when policy-hash (package-resolver policy-hash))
            _ (when-not policy (swap! errors conj "policy not found"))
            _ (swap! checks assoc :policy-resolved? (some? policy))
            round-hash (get-in auth [:authorisation/review-round :review-round/hash])
            round (when round-hash (package-resolver round-hash))
            _ (when-not round (swap! errors conj "review-round not found"))
            _ (swap! checks assoc :round-resolved? (some? round))
            res-hash (:reservation-hash fa-sec)
            reservation (when res-hash (package-resolver res-hash))
            _ (when-not reservation (swap! errors conj "reservation not found"))
            _ (swap! checks assoc :reservation-resolved? (some? reservation))
            ;; Terminal receipt: resolved from profile when available
            receipt-hash (when profile
                           (:evidence-profile/consumption-receipt-hash profile))
            receipt (when receipt-hash (package-resolver receipt-hash))
            _ (when-not receipt (swap! errors conj "terminal receipt not found"))
            _ (swap! checks assoc :receipt-resolved? (some? receipt))]
        {:valid? (empty? @errors) :required? true
         :errors @errors :checks @checks}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Reviewer-facing summary
;; ═══════════════════════════════════════════════════════════════════════════

(defn force-authorisation-summary
  "Produce a concentrated human-readable summary of a force-authorisation
   execution chain from the resolved artifacts.

   Returns a map with :decision, :execution, :qualification keys."
  [authorisation reservation manifest receipt profile]
  (let [fa-sec (:execution/force-authorisation manifest)
        thresh (:authorisation/threshold authorisation)
        dec-refs (:authorisation/decision-references authorisation)
        approvals (filter #(= :approve (:decision %)) dec-refs)
        dissents (filter #(= :dissent (:decision %)) dec-refs)
        target (:authorisation/target authorisation)
        status (when receipt (:consumption/status receipt))
        decision-label
        (case (:authorisation/decision-status authorisation)
          :approved "Approved unanimously"
          :approved-with-dissent (str "Approved with dissent — "
                                      (count approvals) " approvals, "
                                      (count dissents) " dissent(s).")
          :declined (str "Declined — " (count approvals) " of "
                         (:required thresh) " required approvals."))
        target-label (str (name (:target/kind target))
                          " — baseline: " (:target/baseline-content-root target)
                          ", proposed: " (:target/proposed-content-root target))
        policy-ref (:authorisation/policy authorisation)]
    {:decision
     {:label decision-label
      :target target-label
      :threshold (str (:approved thresh) " approved of "
                      (:required thresh) " required ("
                      (:eligible thresh) " eligible)")
      :approvals (mapv :researcher/id approvals)
      :dissents (mapv :researcher/id dissents)}
     :execution
     {:attempt (:reservation/execution-attempt-id reservation)
      :terminal-status status
      :consumption-key (:authorisation/consumption-key authorisation)
      :receipt-hash (:consumption/hash receipt)
      :outcome-produced? (some? (:consumption/resulting-outcome-hash receipt))}
     :verification
     {:authorisation-hash (:authorisation/hash authorisation)
      :policy-ref policy-ref
      :profile-hash (:evidence-profile/hash profile)}
     :qualification
     "This verifies authorisation and execution provenance; it does not by
      itself establish that the resulting research conclusion is correct."}))
