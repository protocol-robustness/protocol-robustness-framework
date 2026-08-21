(ns resolver-sim.benchmark.review-governance-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-governance :as rg]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(def at "2026-07-01T00:00:00Z")
(def outcome (hash-ref "d"))
(def request-root (hash-ref "a"))
(def round-hash (hash-ref "b"))

(defn- principal [id group key-id & [basis]]
  {:principal/id id
   :status :active
   :principal/independence-group group
   :principal/independence-basis-root (or basis (hash-ref (subs (name id) 0 1)))
   :principal/keys [{:key/id key-id :status :active :key/algorithm :ed25519
                     :key/public-key (str "public-" key-id)}]})

(defn governance []
  {:schema-version rg/schema-version
   :governance/epoch 17
   :governance/roles #{:model-steward :independent-reproducer :adversarial-reviewer}
   :governance/principals [(principal :principal/a :group/a :key/a)
                           (principal :principal/b :group/b :key/b)
                           (principal :principal/c :group/c :key/c)]
   :governance/members [{:reviewer/member-id "member-a" :principal/id :principal/a
                         :status :active :granted-roles #{:model-steward}}
                        {:reviewer/member-id "member-b" :principal/id :principal/b
                         :status :active :granted-roles #{:independent-reproducer}}
                        {:reviewer/member-id "member-c" :principal/id :principal/c
                         :status :active :granted-roles #{:adversarial-reviewer}}]
   :governance/policies [{:policy/id :policy/three-independent
                          :member-count 3 :threshold 2
                          :required-roles #{:model-steward :independent-reproducer :adversarial-reviewer}
                          :role-cardinality :unique
                          :equivocation-policy :invalid-seat}]})

(def members
  [{:researcher/id "member-a" :role :model-steward}
   {:researcher/id "member-b" :role :independent-reproducer}
   {:researcher/id "member-c" :role :adversarial-reviewer}])

(deftest governance-is-closed-and-content-addressed
  (let [g (governance)]
    (is (:valid? (rg/validate-governance g)))
    (is (hash-ref/valid-sha256-ref? (rg/governance-root g)))
    (is (not= (rg/governance-root g)
              (rg/governance-root (assoc g :governance/epoch 18))))
    (is (not (:valid? (rg/validate-governance (assoc g :unexpected true)))))))

(deftest root-scoped-p0-rejects-ignored-validity-windows
  (let [g (governance)]
    (is (not (:valid? (rg/validate-governance
                       (assoc-in g [:governance/principals 0 :principal/keys 0 :valid-until]
                                 "2027-01-01T00:00:00Z")))))
    (is (not (:valid? (rg/validate-governance
                       (assoc-in g [:governance/members 0 :valid-from]
                                 "2026-01-01T00:00:00Z")))))))

(deftest governed-round-commits-its-governance-snapshot
  (let [g-root (rg/governance-root (governance))
        c-root (hash-ref "e")
        p-hash (hash-ref "f")
        input {:benchmark/content-root (hash-ref "1")
               :review-round/purpose :model-admission
               :review-round/members members
               :review-round/membership-frozen-at at
               :review-round/policy-root (hash-ref "2")
               :review-round/chain-configuration-root c-root
               :review-round/governance-root g-root
               :review-round/governance-epoch 17
               :review-round/constituted-at at
               :review-round/policy-id :policy/three-independent
               :review-round/policy-hash p-hash}
        round (rr/build-review-round input)]
    (is (rr/governed-round? round))
    (is (:valid? (rr/validate-round round)))
    (is (not= (:review-round/hash round)
              (:review-round/hash (rr/build-review-round
                                   (assoc input :review-round/governance-root (hash-ref "9"))))))
    (is (not (:valid? (rr/validate-round
                       (assoc-in round [:review-round/members 0 :role] :independent-reproducer)))))))

(deftest constitution-requires-independent-governed-seats
  (let [g (governance)
        result (rg/evaluate-constitution g :policy/three-independent members at)]
    (is (= :valid (:constitution-status result)))
    (is (= #{:key/a} (first (:eligible-key-sets result))))
    (is (every? #(= :independent (:status %)) (:independence result)))))

(deftest shared-eligible-key-and-principal-alias-are-invalid
  (let [g (governance)
        shared-key (assoc-in g [:governance/principals 1 :principal/keys 0 :key/id] :key/a)
        alias (assoc-in g [:governance/members 1 :principal/id] :principal/a)]
    (is (= :invalid (:constitution-status
                     (rg/evaluate-constitution shared-key :policy/three-independent members at))))
    (is (= :invalid (:constitution-status
                     (rg/evaluate-constitution alias :policy/three-independent members at))))))

(deftest missing-independence-basis-is-unresolved
  (let [g (update-in (governance) [:governance/principals 1]
                     dissoc :principal/independence-basis-root)
        result (rg/evaluate-constitution g :policy/three-independent members at)]
    (is (:valid? (rg/validate-governance g)))
    (is (= :unresolved (:constitution-status result)))
    (is (some #(= :independence-unresolved (:status %)) (:independence result)))))

(defn- position [member key-id]
  (let [preimage {:researcher/id member :authorisation/id :authorisation/test
                  :authorisation/request-root request-root :review-round/hash round-hash
                  :outcome/root outcome :decision :approve :signing-key/id key-id}]
    {:schema-version "researcher-decision.v2"
     :researcher/id member :authorisation/id :authorisation/test
     :authorisation/request-root request-root :review-round/hash round-hash
     :outcome/root outcome :decision :approve :signing-key/id key-id
     :decision/hash (hash-ref/sha256-ref (hc/domain-hash :researcher-decision-v2 preimage))
     :signature {:value "test" :signed-at at}}))

(deftest governed-authority-requires-valid-constitution-and-position-key
  (let [g (governance)
        auth {:authorisation/id :authorisation/test
              :authorisation/request-root request-root
              :authorisation/review-round {:review-round/hash round-hash}
              :authorisation/target {:target/proposed-content-root outcome}
              :authorisation/decision-references [(position "member-a" :key/a)
                                                  (position "member-b" :key/b)
                                                  (position "member-c" :key/c)]}
        round {:review-round/members members :review-round/policy-id :policy/three-independent
               :review-round/governance-root (rg/governance-root g)
               :review-round/constituted-at at}
        good (tma/evaluate-three-member-authority :authorisation auth :review-round round
                                                  :governance g :signature-valid? (constantly true)
                                                  :position-time-resolver (constantly at)
                                                  :governance-current? (constantly true))
        bad (tma/evaluate-three-member-authority :authorisation
                                                 (assoc auth :authorisation/decision-references
                                                        [(position "member-a" :key/b)
                                                         (position "member-b" :key/c)
                                                         (position "member-c" :key/c)])
                                                 :review-round round :governance g
                                                 :position-time-resolver (constantly at)
                                                 :governance-current? (constantly true)
                                                 :signature-valid? (constantly true))]
    (is (= :valid (:constitution-status good)))
    (is (= :authorised (:authority-status good)))
    (is (= :not-authorised (:authority-status bad)))
    (is (= 2 (count (:invalid-positions bad))))))

(deftest governed-positions-require-key-id-and-current-governance
  (let [g (governance)
        round {:schema-version rr/governed-schema-version
               :review-round/members members :review-round/policy-id :policy/three-independent
               :review-round/governance-root (rg/governance-root g)
               :review-round/constituted-at at}
        auth {:authorisation/id :authorisation/test
              :authorisation/request-root request-root
              :authorisation/review-round {:review-round/hash round-hash}
              :authorisation/target {:target/proposed-content-root outcome}
              :authorisation/decision-references [(dissoc (position "member-a" :key/a) :signing-key/id)
                                                  (position "member-b" :key/b)
                                                  (position "member-c" :key/c)]}
        report (tma/evaluate-three-member-authority
                :authorisation auth :review-round round :governance g
                :signature-valid? (constantly true)
                :position-time-resolver (constantly at)
                :governance-current? (constantly false))]
    (is (= :not-authorised (:authority-status report)))
    (is (false? (:governance-fresh? report)))
    (is (some #(= ::tma/missing-signing-key-id (:reason %))
              (:invalid-position-reasons report)))))
