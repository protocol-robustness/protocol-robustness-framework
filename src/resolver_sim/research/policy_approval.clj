(ns resolver-sim.research.policy-approval
  "Bounded approval receipts for an exact research-ratification policy.

   This adapter reuses review-governance membership/independence, signed
   researcher-decision.v2 positions, and the existing research ratification
   threshold evaluator. It introduces no signature or quorum algorithm."
  (:require [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as review-round]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.research.ratification :as ratification]))

(def schema-version "prf/research-ratification-policy-approval.v1")
(def approval-authorisation-id :research/policy-approval)

(declare verify-policy-approval)

(defn- root? [value] (reference/valid-sha256-ref? value))

(defn- round-root [review-round]
  (:review-round/hash review-round))

(defn approval-root [approval]
  (reference/sha256-ref
   (canonical/domain-hash :research-ratification-policy-approval-v1
                          (-> (dissoc approval :approval/root)
                              (update :approval/review-governance
                                      governance/governance-projection)))))

(defn- group-members [review-governance]
  (->> (:governance/members review-governance)
       (filter #(= :active (:status %)))
       (sort-by :reviewer/member-id)
       vec))

(defn build-approval-ratification-policy
  "Derive the approval threshold policy from an already trusted governed
   reviewer group. Build the review round against this policy root."
  [{:keys [policy review-governance governance-policy-id trusted-authority-root]}]
  (let [policy-check (ratification/validate-policy policy)
        governance-check (governance/validate-governance review-governance)
        authority-root (when (:valid? governance-check)
                         (governance/governance-root review-governance))
        group-policy (governance/policy-by-id review-governance governance-policy-id)
        members (group-members review-governance)
        principal-ids (mapv (fn [member]
                              (:principal/id
                               (governance/principal-by-id
                                review-governance (:principal/id member))))
                            members)]
    (when-not (and (:valid? policy-check)
                   (:valid? governance-check)
                   group-policy
                   (= authority-root trusted-authority-root)
                   (= authority-root (:policy/authority-root policy))
                   (= 3 (count members))
                   (every? identity principal-ids))
      (throw (ex-info "Cannot derive approval policy from untrusted or invalid material"
                      {:reason :invalid-policy-approval-authority})))
    (ratification/build-policy
     {:id :policy/research-ratification-policy-approval
      :authority-root authority-root
      :eligible-principals principal-ids
      :threshold (:threshold group-policy)
      :allow-dissent? false
      :challenges-block? true})))

(defn- active-key-bindings [review-governance members]
  (into {}
        (map (fn [{:keys [reviewer/member-id]}]
               (let [member (governance/member-by-id review-governance member-id)
                     principal (governance/principal-by-id
                                review-governance (:principal/id member))
                     active-keys (filter #(= :active (:status %))
                                         (:principal/keys principal))]
                 [member-id
                  (when (= 1 (count active-keys))
                    (let [key (first active-keys)]
                      {:researcher/id member-id
                       :principal/id (:principal/id member)
                       :signing-key/id (:key/id key)
                       :public-key-content (:key/public-key key)}))]))
             members)))

(defn- derive-context
  [{:keys [policy review-governance review-round governance-policy-id
           trusted-authority-root]}]
  (let [policy-check (ratification/validate-policy policy)
        governance-check (governance/validate-governance review-governance)
        computed-authority-root (when (:valid? governance-check)
                                  (governance/governance-root review-governance))
        group-policy (governance/policy-by-id review-governance governance-policy-id)
        members (:review-round/members review-round)
        constitution (when (and (:valid? governance-check) group-policy)
                       (governance/evaluate-constitution review-governance
                                                        governance-policy-id
                                                        members
                                                        (:review-round/constituted-at review-round)))
        member-records (group-members review-governance)
        approval-policy (when (and (:valid? policy-check)
                                   (:valid? governance-check)
                                   group-policy)
                          (try
                            (build-approval-ratification-policy
                             {:policy policy
                              :review-governance review-governance
                              :governance-policy-id governance-policy-id
                              :trusted-authority-root trusted-authority-root})
                            (catch Exception _ nil)))
        target (when (and approval-policy (review-round/round-valid? review-round))
                 (ratification/build-target
                  {:domain :prf/research-ratification-policy-approval
                   :subject-root (:policy/root policy)
                   :evidence-root computed-authority-root
                   :round-root (round-root review-round)
                   :policy-root (:policy/root approval-policy)
                   :schema "prf/research-ratification-policy-approval.v1"}))
        errors (cond-> []
                 (not (:valid? policy-check)) (conj :invalid-research-policy)
                 (not (:valid? governance-check)) (conj :invalid-review-governance)
                 (not= computed-authority-root trusted-authority-root)
                 (conj :authority-root-not-trusted)
                 (not= computed-authority-root (:policy/authority-root policy))
                 (conj :research-policy-authority-root-mismatch)
                 (nil? group-policy) (conj :unknown-governance-review-policy)
                 (and group-policy (not= :valid (:constitution-status constitution)))
                 (conj :review-panel-constitution-invalid)
                 (not (review-round/round-valid? review-round))
                 (conj :invalid-review-round)
                 (and (review-round/round-valid? review-round)
                      (not= :research-policy-approval (:review-round/purpose review-round)))
                 (conj :wrong-review-round-purpose)
                 (and (review-round/round-valid? review-round)
                      (not= (:policy/root policy) (:benchmark/content-root review-round)))
                 (conj :review-round-subject-mismatch)
                 (and approval-policy
                      (not= (:policy/root approval-policy)
                            (:review-round/policy-root review-round)))
                 (conj :review-round-approval-policy-mismatch)
                 (nil? approval-policy) (conj :approval-policy-derivation-failed)
                 (not= (set (map :reviewer/member-id member-records))
                       (set (map :researcher/id members)))
                 (conj :review-round-membership-mismatch)
                 (some nil? (vals (active-key-bindings review-governance member-records)))
                 (conj :member-must-have-one-active-signing-key))]
    {:valid? (empty? errors)
     :errors errors
     :authority-root computed-authority-root
     :governance-policy group-policy
     :approval-policy approval-policy
     :target target
     :constitution constitution
     :key-bindings (active-key-bindings review-governance member-records)}))

(defn- decisions->contributions [context decisions]
  (mapv (fn [decision]
          (let [researcher-id (:researcher/id decision)
                binding (get (:key-bindings context) researcher-id)]
            (when-not (and binding
                           (= approval-authorisation-id (:authorisation/id decision))
                           (= (:target/root (:target context))
                              (:authorisation/request-root decision))
                           (= (:review-round/hash
                               ;; target context is derived only from the supplied
                               ;; frozen round and exact policy roots.
                               (:review-round context))
                              (:review-round/hash decision))
                           (= :approve (:decision decision))
                           (= (:signing-key/id binding) (:signing-key/id decision)))
              (throw (ex-info "Policy approval decision is not bound to its approval basis"
                              {:reason :approval-decision-binding-mismatch
                               :researcher/id researcher-id})))
            (ratification/build-contribution
             {:target (:target context)
              :principal-id (:principal/id binding)
              :decision decision
              :kind :approve})))
        decisions))

(defn- build-body [{:keys [policy review-governance review-round governance-policy-id
                           trusted-authority-root decisions] :as inputs}]
  (let [context (derive-context inputs)]
    (when-not (:valid? context)
      (throw (ex-info "Policy approval basis is invalid"
                      {:errors (:errors context)})))
    (when-not (vector? decisions)
      (throw (ex-info "Policy approval decisions must be a vector" {})))
    (let [context (assoc context :review-round review-round)
          contributions (decisions->contributions context decisions)
          ratification-result
          (ratification/reconstruct-ratification
           {:target (:target context)
            :policy (:approval-policy context)
            :contributions contributions
            :key-bindings (:key-bindings context)})
          status (if (contains? #{:ratified :ratified-with-dissent}
                                (:ratification/status ratification-result))
                   :approved :pending)]
      {:schema/version schema-version
       :approval/research-policy policy
       :approval/review-governance review-governance
       :approval/review-round review-round
       :approval/governance-policy-id governance-policy-id
       :approval/trusted-authority-root trusted-authority-root
       :approval/decisions decisions
       :approval/derived-ratification-policy (:approval-policy context)
       :approval/target (:target context)
       :approval/ratification ratification-result
       :approval/status status
       :approval/operational-authority? false})))

(defn build-policy-approval
  "Build a deterministic, independently reconstructable approval receipt.
   The caller must supply an externally trusted authority root. Approval
   decisions are signed researcher-decision.v2 artifacts over the derived
   exact policy-approval target."
  [inputs]
  (let [body (build-body inputs)
        approval (assoc body :approval/root (approval-root body))
        verification (verify-policy-approval
                      approval {:trusted-authority-root
                                (:approval/trusted-authority-root approval)})]
    (when-not (:valid? verification)
      (throw (ex-info "Policy approval receipt failed verification"
                      {:errors (:errors verification)})))
    approval))

(defn prepare-policy-approval
  "Derive a fixed approval target and per-member signing requests. This does
   not accept signatures or change the policy; each request is later signed as
   a researcher-decision.v2 position and passed to build-policy-approval."
  [{:keys [policy review-governance review-round governance-policy-id
           trusted-authority-root] :as inputs}]
  (let [context (derive-context inputs)]
    (when-not (:valid? context)
      (throw (ex-info "Policy approval basis is invalid"
                      {:errors (:errors context)})))
    {:schema/version "prf/research-policy-approval-request.v1"
     :approval/authorisation-id approval-authorisation-id
     :approval/research-policy-root (:policy/root policy)
     :approval/trusted-authority-root trusted-authority-root
     :approval/target (:target context)
     :approval/review-round review-round
     :approval/derived-ratification-policy (:approval-policy context)
     :approval/signing-requests
     (mapv (fn [member]
             (let [binding (get (:key-bindings context) (:reviewer/member-id member))]
               {:researcher/id (:researcher/id binding)
                :signing-key/id (:signing-key/id binding)
                :authorisation/id approval-authorisation-id
                :authorisation/request-root (get-in context [:target :target/root])
                :review-round/hash (:review-round/hash review-round)
                :outcome/root (ratification/contribution-subject-root
                               {:target-root (get-in context [:target :target/root])
                                :principal-id (:principal/id binding)
                                :kind :approve
                                :evidence-root nil})
                :decision :approve}))
           (group-members review-governance))
     :approval/status :awaiting-signatures
     :approval/operational-authority? false}))

(defn verify-policy-approval
  "Reconstruct a retained approval receipt against an external authority root.
   A valid but below-threshold receipt remains :pending and cannot approve the
   policy."
  [approval {:keys [trusted-authority-root]}]
  (let [inputs {:policy (:approval/research-policy approval)
                :review-governance (:approval/review-governance approval)
                :review-round (:approval/review-round approval)
                :governance-policy-id (:approval/governance-policy-id approval)
                :trusted-authority-root trusted-authority-root
                :decisions (:approval/decisions approval)}
        expected (try (build-body inputs) (catch Exception _ nil))
        expected (when expected (assoc expected :approval/root (approval-root expected)))
        errors (cond-> []
                 (not= schema-version (:schema/version approval))
                 (conj :unsupported-approval-schema)
                 (not (root? trusted-authority-root))
                 (conj :missing-trusted-authority-root)
                 (nil? expected) (conj :approval-reconstruction-failed)
                 (and expected (not= expected approval))
                 (conj :approval-reconstruction-mismatch)
                 (not= (:approval/root approval) (approval-root approval))
                 (conj :approval-root-mismatch))]
    {:valid? (empty? errors)
     :errors errors
     :status (when (empty? errors) (:approval/status approval))
     :approval/root (:approval/root approval)
     :ratification (:approval/ratification approval)}))
