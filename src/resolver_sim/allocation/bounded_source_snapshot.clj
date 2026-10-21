(ns resolver-sim.allocation.bounded-source-snapshot
  "Fail-closed adapter for authority-signed, bounded allocation source snapshots.

   This validates what a trusted source authority attests about a bounded
   population. It cannot prove that the authority's external source is truthful
   or complete; that trust decision remains outside the supplied bundle."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as sed]))

(def snapshot-schema "prf-bounded-allocation-source-snapshot.v1")
(def snapshot-domain "PRF_BOUNDED_ALLOCATION_SOURCE_SNAPSHOT_V1")
(def authority-domain "PRF_PRO_RATA_AUTHORITY_BINDING_V1")

(def source-role :allocation-source-authority)
(def policy-role :allocation-policy-authority)
(def beneficiary-role :beneficiary-authorization-authority)
(def funding-role :capital-funding-authority)

(def event-kinds
  #{:request/open :request/amend :request/cancel :asset/observe :reserve/observe})

(declare valid-request-lifecycle? bind-beneficiaries-to-positive-claims)

(defn snapshot-root [snapshot]
  (hash-ref/sha256-ref
   (hc/domain-hash snapshot-domain (dissoc snapshot :signature :snapshot/root))))

(defn- valid-event-sequence? [snapshot]
  (let [events (:snapshot/events snapshot)
        ids (mapv :event/id events)
        sequences (mapv :event/sequence events)
        request-events (filter #(contains? #{:request/open :request/amend :request/cancel}
                                           (:event/kind %)) events)]
    (and (vector? events)
         (every? map? events)
         (every? #(and (string? (:event/id %)) (not-empty (:event/id %))
                       (string? (:event/entity-id %)) (not-empty (:event/entity-id %))
                       (contains? event-kinds (:event/kind %))
                       (integer? (:event/sequence %))
                       (integer? (:event/time %))
                       (<= (:snapshot/start-time snapshot) (:event/time %)
                           (:snapshot/cutoff snapshot))
                       (map? (:event/payload %))) events)
         (= (count ids) (count (distinct ids)))
         (= sequences (vec (sort sequences)))
         (= (count sequences) (count (distinct sequences)))
         (every? (fn [[a b]] (= (inc a) b)) (partition 2 1 sequences))
         (every? (fn [[a b]] (<= (:event/time a) (:event/time b)))
                 (partition 2 1 events))
         (every? #(<= (:event/time %) (:snapshot/watermark snapshot)) events)
         (or (empty? sequences)
             (and (= (:snapshot/first-sequence snapshot) (first sequences))
                  (= (:snapshot/last-sequence snapshot) (last sequences))))
         (= (:snapshot/event-count snapshot) (count events))
         (valid-request-lifecycle? request-events))))

(defn- valid-request-lifecycle? [events]
  (loop [remaining events, states {}]
    (if-let [event (first remaining)]
      (let [id (:event/entity-id event)
            state (get states id)
            next-state (case (:event/kind event)
                         :request/open (when-not state :open)
                         :request/amend (when (= :open state) :open)
                         :request/cancel (when (= :open state) :cancelled)
                         state)]
        (if next-state
          (recur (next remaining) (assoc states id next-state))
          false))
      true)))

(defn validate-snapshot-shape [snapshot]
  (let [required #{:snapshot/schema :snapshot/id :snapshot/source-id
                   :snapshot/population-id :snapshot/query-root
                   :snapshot/start-time :snapshot/cutoff :snapshot/watermark
                   :snapshot/first-sequence :snapshot/last-sequence
                   :snapshot/event-count :snapshot/events :snapshot/root
                   :signature}
        integer-fields [:snapshot/start-time :snapshot/cutoff :snapshot/watermark
                        :snapshot/event-count]]
    (cond
      (not (map? snapshot)) {:valid? false :reason :snapshot-not-map}
      (not= required (set (keys snapshot))) {:valid? false :reason :snapshot-fields-mismatch}
      (not= snapshot-schema (:snapshot/schema snapshot)) {:valid? false :reason :unsupported-snapshot-schema}
      (not-every? string? [(:snapshot/id snapshot) (:snapshot/source-id snapshot)
                           (:snapshot/population-id snapshot)])
      {:valid? false :reason :invalid-snapshot-identity}
      (not (and (string? (:snapshot/query-root snapshot))
                (re-matches #"sha256:[0-9a-f]{64}" (:snapshot/query-root snapshot))))
      {:valid? false :reason :invalid-snapshot-identity}
      (not (every? integer? (map snapshot integer-fields)))
      {:valid? false :reason :invalid-snapshot-boundary}
      (or (> (:snapshot/start-time snapshot) (:snapshot/cutoff snapshot))
          (< (:snapshot/watermark snapshot) (:snapshot/cutoff snapshot)))
      {:valid? false :reason :invalid-snapshot-time-order}
      (not (valid-event-sequence? snapshot)) {:valid? false :reason :invalid-or-incomplete-event-sequence}
      (not= (:snapshot/root snapshot) (snapshot-root snapshot))
      {:valid? false :reason :snapshot-root-mismatch}
      :else {:valid? true :snapshot/root (:snapshot/root snapshot)})))

(defn verify-snapshot [snapshot trust-policy]
  (let [shape (validate-snapshot-shape snapshot)
        authority (when (:valid? shape)
                    (sed/verify-envelope snapshot snapshot-domain trust-policy source-role))]
    (cond
      (not (:valid? shape)) shape
      (not (:valid? authority)) authority
      :else {:valid? true
             :snapshot/root (:snapshot/root snapshot)
             :authority/key-id (:key-id authority)
             :scope :authority-attested-bounded-population
             :limitations [:source-truth-not-independently-established
                           :population-completeness-depends-on-trusted-authority
                           :external-source-revocation-status-not-inferred]})))

(defn binding-root [binding]
  (hash-ref/sha256-ref
   (hc/domain-hash authority-domain (dissoc binding :signature :binding/root))))

(defn- unique-by? [key rows]
  (let [values (mapv key rows)]
    (and (every? some? values) (= (count values) (count (distinct values))))))

(defn validate-beneficiary-binding [binding]
  (let [rows (:beneficiary/authorizations binding)]
    (and (map? binding)
         (string? (:binding/id binding))
         (string? (:binding/source-root binding))
         (string? (:policy/root binding))
         (vector? rows)
         (every? #(and (map? %)
                       (string? (:claim/id %))
                       (string? (:owner/pseudonym %))
                       (string? (:beneficiary/destination %))
                       (re-matches #"0x[0-9a-fA-F]{40}" (:beneficiary/destination %))
                       (string? (:authorization/id %))) rows)
         (unique-by? :claim/id rows)
         (unique-by? :authorization/id rows)
         (= (:binding/root binding) (binding-root binding)))))

(defn verify-authority-binding
  "Verify a policy, beneficiary, or funding authority envelope. The role is
   explicit so one authority cannot be silently substituted for another."
  [binding role trust-policy]
  (let [expected-role (get {:policy policy-role
                            :beneficiary beneficiary-role
                            :funding funding-role} role)
        valid-shape? (and expected-role
                          (map? binding)
                          (string? (:binding/id binding))
                          (= (:binding/root binding) (binding-root binding))
                          (if (= role :beneficiary)
                            (validate-beneficiary-binding binding)
                            (and (string? (:binding/source-root binding))
                                 (string? (:policy/root binding))
                                 (map? (:binding/subject binding))
                                 (if (= role :policy)
                                   (and (string? (get-in binding [:binding/subject :policy/version]))
                                        (= (:policy/root binding)
                                           (get-in binding [:binding/subject :policy/root]))
                                        (map? (get-in binding [:binding/subject :policy/rules])))
                                   (let [subject (:binding/subject binding)]
                                     (and (string? (:operation/id subject))
                                          (string? (:reserve/root subject))
                                          (string? (:asset/id subject))
                                          (string? (:funder/id subject))
                                          (string? (:chain/instance-id subject))
                                          (string? (:deployment/code-hash subject))
                                          (integer? (:amount subject))
                                          (pos? (:amount subject))))))))
        signature (when valid-shape?
                    (sed/verify-envelope binding authority-domain trust-policy expected-role))]
    (cond
      (not expected-role) {:valid? false :reason :unsupported-authority-role}
      (not valid-shape?) {:valid? false :reason :invalid-authority-binding}
      (not (:valid? signature)) signature
      :else {:valid? true :binding/root (:binding/root binding)
             :authority/role role :authority/key-id (:key-id signature)})))

(defn qualify-stage-b-inputs
  "Compose existing signed envelopes for one bounded input population. The
   four trust policies are verifier supplied and may name different operators.
   A valid result means the envelopes are cryptographically and structurally
   bound together; it does not authenticate the external facts they attest."
  [{:keys [snapshot source-trust policy policy-trust beneficiaries beneficiary-trust
           funding funding-trust allocation]}]
  (let [source-result (verify-snapshot snapshot source-trust)
        policy-result (verify-authority-binding policy :policy policy-trust)
        beneficiary-result (verify-authority-binding beneficiaries :beneficiary beneficiary-trust)
        funding-result (verify-authority-binding funding :funding funding-trust)
        source-root (:snapshot/root snapshot)
        policy-root (:policy/root policy)
        roots-match? (and (= source-root (:binding/source-root policy))
                          (= source-root (:binding/source-root beneficiaries))
                          (= source-root (:binding/source-root funding))
                          (= policy-root (:policy/root beneficiaries))
                          (= policy-root (:policy/root funding)))
        beneficiary-result-map (bind-beneficiaries-to-positive-claims allocation beneficiaries)
        valid? (and (:valid? source-result) (:valid? policy-result)
                    (:valid? beneficiary-result) (:valid? funding-result)
                    roots-match? (:valid? beneficiary-result-map))]
    {:valid? (boolean valid?)
     :status (if valid? :bound :unverified)
     :source {:root source-root :verification source-result}
     :policy {:root policy-root :verification policy-result}
     :beneficiary {:root (:binding/root beneficiaries)
                   :verification beneficiary-result
                   :coverage beneficiary-result-map}
     :funding {:root (:binding/root funding) :verification funding-result}
     :cross-bindings {:roots-match? (boolean roots-match?)}
     :limitations [:external-source-truth-and-completeness-require-trusted-source-authority
                   :pseudonymous-authorization-does-not-assert-legal-identity
                   :funding-authorization-is-not-chain-execution-evidence
                   :local-rpc-receipt-evidence-does-not-establish-public-chain-finality]}))

(defn bind-beneficiaries-to-positive-claims
  "Require an authorization for every positive allocation claim and no extras.
   The owner field is explicitly pseudonymous; it is not a legal-identity claim."
  [allocation beneficiary-binding]
  (let [valid-allocation? (and (map? allocation)
                               (every? (fn [[claim amount]]
                                         (and (string? claim) (not-empty claim)
                                              (integer? amount) (not (neg? amount))))
                                       allocation))]
    (if-not valid-allocation?
      {:valid? false
       :reason :invalid-allocation-map
       :missing []
       :unexpected []
       :mapping {}
       :identity-scope :pseudonymous-owner-authorization
       :limitations [:allocation-claim-ids-must-be-nonempty-strings
                     :allocation-amounts-must-be-nonnegative-integers
                     :does-not-establish-legal-identity-from-wallet-address]}
      (let [positive (set (keep (fn [[claim amount]] (when (pos? amount) claim)) allocation))
            rows (:beneficiary/authorizations beneficiary-binding)
            authorized (set (map :claim/id rows))
            coverage-matches? (= positive authorized)]
        {:valid? (and coverage-matches? (validate-beneficiary-binding beneficiary-binding))
         :reason (when-not coverage-matches? :beneficiary-coverage-mismatch)
         :missing (vec (sort (set/difference positive authorized)))
         :unexpected (vec (sort (set/difference authorized positive)))
         :mapping (into (sorted-map) (map (juxt :claim/id :beneficiary/destination) rows))
         :identity-scope :pseudonymous-owner-authorization
         :limitations [:does-not-establish-legal-identity-from-wallet-address]}))))
