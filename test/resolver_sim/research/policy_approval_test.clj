(ns resolver-sim.research.policy-approval-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as review-round]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.research.policy-approval :as approval]
            [resolver-sim.research.ratification :as ratification]))

(def ^:private fixed-time "2026-01-01T00:00:00Z")
(def ^:private subject-root (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private basis-root (str "sha256:" (apply str (repeat 64 "b"))))
(def ^:private key-dir "test/fixtures/signing-keys/research-policy-approvers")

(def ^:private approvers
  [{:member "member-a" :principal :principal/a :key-id "key/a"
    :role :model-steward}
   {:member "member-b" :principal :principal/b :key-id "key/b"
    :role :independent-reproducer}
   {:member "member-c" :principal :principal/c :key-id "key/c"
    :role :adversarial-reviewer}])

(defn- public-key-content [member]
  (slurp (str key-dir "/" (:member member) ".pub")))

(defn- private-key-path [member]
  (str key-dir "/" (:member member) ".key"))

(defn- fixture []
  (let [review-governance
        {:schema-version governance/schema-version
         :governance/epoch 1
         :governance/roles #{:model-steward :independent-reproducer
                             :adversarial-reviewer}
         :governance/principals
         (mapv (fn [{:keys [principal key-id] :as member}]
                 {:principal/id principal
                  :status :active
                  :principal/independence-group (keyword "group" (name principal))
                  :principal/independence-basis-root basis-root
                  :principal/keys
                  [{:key/id key-id
                    :status :active
                    :key/algorithm :ed25519
                    :key/public-key (public-key-content member)}]})
               approvers)
         :governance/members
         (mapv (fn [{:keys [member principal role]}]
                 {:reviewer/member-id member
                  :principal/id principal
                  :status :active
                  :granted-roles #{role}})
               approvers)
         :governance/policies
         [{:policy/id :policy/research-policy-approval
           :member-count 3
           :threshold 2
           :required-roles #{:model-steward :independent-reproducer
                             :adversarial-reviewer}
           :role-cardinality :unique
           :equivocation-policy :invalid-seat}]}
        authority-root (governance/governance-root review-governance)
        policy (ratification/build-policy
                {:id :policy/research-results
                 :authority-root authority-root
                 :eligible-principals (mapv :principal approvers)
                 :threshold 2
                 :allow-dissent? true
                 :challenges-block? true})
        approval-policy
        (approval/build-approval-ratification-policy
         {:policy policy :review-governance review-governance
          :governance-policy-id :policy/research-policy-approval
          :trusted-authority-root authority-root})
        round (review-round/build-review-round
               {:benchmark/content-root (:policy/root policy)
                :review-round/purpose :research-policy-approval
                :review-round/members
                (mapv (fn [{:keys [member role]}]
                        {:researcher/id member :role role})
                      approvers)
                :review-round/membership-frozen-at fixed-time
                :review-round/policy-root (:policy/root approval-policy)})
        target (ratification/build-target
                {:domain :prf/research-ratification-policy-approval
                 :subject-root (:policy/root policy)
                 :evidence-root authority-root
                 :round-root (:review-round/hash round)
                 :policy-root (:policy/root approval-policy)
                 :schema "prf/research-ratification-policy-approval.v1"})
        decisions
        (mapv (fn [{:keys [member principal key-id] :as approver}]
                (let [outcome-root
                      (ratification/contribution-subject-root
                       {:target-root (:target/root target)
                        :principal-id principal
                        :kind :approve
                        :evidence-root nil})
                      decision
                      (decisions/build-signed-decision-v2
                       member approval/approval-authorisation-id
                       (:target/root target) (:review-round/hash round)
                       outcome-root :approve (private-key-path approver)
                       :signing-key-id key-id)]
                  (assoc-in decision [:signature :signed-at] fixed-time)))
              approvers)]
    {:policy policy
     :review-governance review-governance
     :review-round round
     :governance-policy-id :policy/research-policy-approval
     :trusted-authority-root authority-root
     :decisions decisions
     :authority-root authority-root}))

(deftest governed-group-approves-exact-research-policy
  (let [inputs (fixture)
        receipt (approval/build-policy-approval inputs)
        verification (approval/verify-policy-approval
                      receipt {:trusted-authority-root (:authority-root inputs)})]
    (is (= :approved (:approval/status receipt)))
    (is (= :ratified (get-in receipt [:approval/ratification
                                      :ratification/status])))
    (is (:valid? verification))
    (is (false? (:approval/operational-authority? receipt)))
    (is (= (:policy/root (:policy inputs))
           (get-in receipt [:approval/target :target/subject-root])))))

(deftest fixed-fixture-produces-a-deterministic-approval-receipt
  (let [first-receipt (approval/build-policy-approval (fixture))
        second-receipt (approval/build-policy-approval (fixture))]
    (is (= first-receipt second-receipt))
    (is (= (:approval/root first-receipt) (:approval/root second-receipt)))))

(deftest approval-preparation-derives-member-signing-requests
  (let [inputs (fixture)
        request (approval/prepare-policy-approval (dissoc inputs :decisions))]
    (is (= :awaiting-signatures (:approval/status request)))
    (is (false? (:approval/operational-authority? request)))
    (is (= 3 (count (:approval/signing-requests request))))
    (is (every? #(= (get-in request [:approval/target :target/root])
                    (:authorisation/request-root %))
                (:approval/signing-requests request)))
    (is (every? #(= (get-in request [:approval/review-round :review-round/hash])
                    (:review-round/hash %))
                (:approval/signing-requests request)))))

(deftest policy-approval-requires-the-governance-threshold
  (let [inputs (update (fixture) :decisions subvec 0 1)
        receipt (approval/build-policy-approval inputs)
        verification (approval/verify-policy-approval
                      receipt {:trusted-authority-root (:authority-root inputs)})]
    (is (= :pending (:approval/status receipt)))
    (is (:valid? verification))
    (is (= 1 (get-in receipt [:approval/ratification
                              :ratification/approval-count])))))

(deftest policy-approval-fails-closed-on-anchor-replay-or-signature-change
  (let [inputs (fixture)
        receipt (approval/build-policy-approval inputs)
        wrong-anchor (approval/verify-policy-approval
                      receipt {:trusted-authority-root subject-root})
        altered-decision (update-in receipt [:approval/decisions 0 :signature :value]
                                    #(str (if (= \0 (first %)) \1 \0) (subs % 1)))
        altered-signature (approval/verify-policy-approval
                           altered-decision
                           {:trusted-authority-root (:authority-root inputs)})
        replayed-policy (ratification/build-policy
                         {:id :policy/research-results
                          :authority-root (:authority-root inputs)
                          :eligible-principals [:principal/a :principal/b]
                          :threshold 2 :allow-dissent? true
                          :challenges-block? true})
        replayed (approval/verify-policy-approval
                  (assoc receipt :approval/research-policy replayed-policy)
                  {:trusted-authority-root (:authority-root inputs)})]
    (is (not (:valid? wrong-anchor)))
    (is (not (:valid? altered-signature)))
    (is (not (:valid? replayed)))))

(deftest panel-membership-and-independence-are-required
  (let [inputs (fixture)
        governance (assoc-in (:review-governance inputs)
                             [:governance/principals 1 :principal/independence-group]
                             :group/a)
        authority-root (governance/governance-root governance)
        policy (ratification/build-policy
                {:id :policy/research-results
                 :authority-root authority-root
                 :eligible-principals (mapv :principal approvers)
                 :threshold 2 :allow-dissent? true :challenges-block? true})
        approval-policy (approval/build-approval-ratification-policy
                         {:policy policy :review-governance governance
                          :governance-policy-id :policy/research-policy-approval
                          :trusted-authority-root authority-root})
        round (review-round/build-review-round
               {:benchmark/content-root (:policy/root policy)
                :review-round/purpose :research-policy-approval
                :review-round/members
                (mapv (fn [{:keys [member role]}]
                        {:researcher/id member :role role}) approvers)
                :review-round/membership-frozen-at fixed-time
                :review-round/policy-root (:policy/root approval-policy)})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (approval/build-policy-approval
                  (assoc inputs :policy policy :review-governance governance
                         :trusted-authority-root authority-root
                         :review-round round :decisions []))))))
