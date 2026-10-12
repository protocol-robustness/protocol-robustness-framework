(ns resolver-sim.benchmark.researcher-reward
  "Frozen researcher reward policy and deterministic eligibility evaluation."
  (:require [resolver-sim.research.api :as research]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-assignment-target-binding :as target-binding]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const policy-schema-version "researcher-reward-policy.v1")
(def ^:const eligibility-schema-version "researcher-reward-eligibility.v1")
(def ^:const proposal-schema-version "researcher-reward-proposal.v1")
(def ^:const obligation-schema-version "researcher-reward-obligation.v1")
(def ^:const contribution-types #{:approval :dissent :reproduction :adversarial-finding})

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn reward-key
  "Stable duplicate identity for one researcher contribution under one policy."
  [{:keys [researcher assignment-root contribution-root contribution-type policy-root]}]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-reward-key
                   {:researcher/id researcher
                    :research-assignment/root assignment-root
                    :contribution/root contribution-root
                    :contribution/type contribution-type
                    :researcher-reward-policy/root policy-root})))

(defn policy-root [policy]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-reward-policy
                   (-> policy
                       (dissoc :researcher-reward-policy/root)
                       (update :accepted-contribution-types #(vec (sort %)))
                       (update :required-verification #(vec (sort %)))
                       (update :required-claim-statuses #(vec (sort %)))))))

(defn build-policy [{:keys [accepted-contribution-types required-verification
                            required-claim-statuses reward-amount reward-currency
                            split-rules duplicate-rule conflict-of-interest-rule] :as fields}]
  (when-not (and (set? accepted-contribution-types) (seq accepted-contribution-types)
                 (every? contribution-types accepted-contribution-types)
                 (set? required-verification)
                 (set? required-claim-statuses)
                 (every? keyword? required-claim-statuses)
                 (integer? reward-amount) (not (neg? reward-amount))
                 (string? reward-currency) (seq reward-currency)
                 (map? split-rules) (map? duplicate-rule) (map? conflict-of-interest-rule))
    (throw (ex-info "Invalid researcher reward policy" {:policy fields})))
  (let [policy (assoc fields :schema-version policy-schema-version)]
    (assoc policy :researcher-reward-policy/root (policy-root policy))))

(defn eligibility-root [eligibility]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-reward-eligibility
                   (dissoc eligibility :researcher-reward-eligibility/root))))

(defn evaluate
  "Determine reward eligibility from frozen policy and supplied evidence.
   Duplicate and conflict flags are authoritative inputs from their respective
   registries; this function neither creates an obligation nor moves funds."
  [{:keys [policy researcher assignment-root contribution-root contribution-type
           verification claim duplicate? conflict-of-interest?]}]
  (let [policy-root* (:researcher-reward-policy/root policy)
        required-verification (:required-verification policy)
        required-claim-statuses (:required-claim-statuses policy)
        reasons (cond-> []
                  (not= policy-schema-version (:schema-version policy)) (conj :invalid-policy)
                  (not= policy-root* (policy-root policy)) (conj :policy-root-mismatch)
                  (not (string? researcher)) (conj :invalid-researcher)
                  (not (root? assignment-root)) (conj :invalid-assignment-root)
                  (not (root? contribution-root)) (conj :invalid-contribution-root)
                  (not (contains? (set (:accepted-contribution-types policy)) contribution-type)) (conj :unsupported-contribution-type)
                  (and (contains? required-verification :verified)
                       (not (:verified? verification))) (conj :verification-failed)
                  (and (contains? required-verification :claimable)
                       (not (:claimable? claim))) (conj :claim-not-claimable)
                  (and (seq required-claim-statuses)
                       (not (contains? required-claim-statuses
                                       (:research-claim/status claim))))
                  (conj :claim-status-not-accepted)
                  duplicate? (conj :duplicate-submission)
                  conflict-of-interest? (conj :conflict-of-interest))
        eligible? (empty? reasons)
        eligibility {:schema-version eligibility-schema-version
                     :researcher/id researcher
                     :research-assignment/root assignment-root
                     :contribution/root contribution-root
                     :contribution/type contribution-type
                     :verification/evidence verification
                     :claim/evidence claim
                     :researcher-reward-policy/root policy-root*
                     :reward/key (reward-key {:researcher researcher
                                              :assignment-root assignment-root
                                              :contribution-root contribution-root
                                              :contribution-type contribution-type
                                              :policy-root policy-root*})
                     :reward/amount (:reward-amount policy)
                     :reward/currency (:reward-currency policy)
                     :reward/eligible? eligible?
                     :reward/reasons reasons}]
    (assoc eligibility :researcher-reward-eligibility/root (eligibility-root eligibility))))

(defn valid-eligibility? [eligibility]
  (and (= eligibility-schema-version (:schema-version eligibility))
       (:reward/eligible? eligibility)
       (= (:reward/key eligibility)
          (reward-key {:researcher (:researcher/id eligibility)
                       :assignment-root (:research-assignment/root eligibility)
                       :contribution-root (:contribution/root eligibility)
                       :contribution-type (:contribution/type eligibility)
                       :policy-root (:researcher-reward-policy/root eligibility)}))
       (= (:researcher-reward-eligibility/root eligibility)
          (eligibility-root eligibility))))

(defn proposal-root [proposal]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-reward-proposal
                   (dissoc proposal :researcher-reward-proposal/root))))

(defn build-proposal
  "Build an immutable reward proposal from one valid eligibility result and its
   frozen policy. This is not an approval and creates no payment authority."
  [{:keys [eligibility policy]}]
  (when-not (and (valid-eligibility? eligibility)
                 (= (:researcher-reward-policy/root eligibility)
                    (:researcher-reward-policy/root policy))
                 (= (:reward/amount eligibility) (:reward-amount policy))
                 (= (:reward/currency eligibility) (:reward-currency policy)))
    (throw (ex-info "Reward proposal requires policy-consistent eligible evidence" {})))
  (let [proposal {:schema-version proposal-schema-version
                  :researcher-reward-eligibility/root (:researcher-reward-eligibility/root eligibility)
                  :researcher/id (:researcher/id eligibility)
                  :research-assignment/root (:research-assignment/root eligibility)
                  :contribution/root (:contribution/root eligibility)
                  :contribution/type (:contribution/type eligibility)
                  :researcher-reward-policy/root (:researcher-reward-policy/root eligibility)
                  :reward/key (:reward/key eligibility)
                  :reward/amount (:reward/amount eligibility)
                  :reward/currency (:reward/currency eligibility)}]
    (assoc proposal :researcher-reward-proposal/root (proposal-root proposal))))

(defn valid-proposal? [proposal]
  (and (= proposal-schema-version (:schema-version proposal))
       (root? (:researcher-reward-eligibility/root proposal))
       (root? (:reward/key proposal))
       (root? (:research-assignment/root proposal))
       (root? (:contribution/root proposal))
       (root? (:researcher-reward-policy/root proposal))
       (contains? contribution-types (:contribution/type proposal))
       (integer? (:reward/amount proposal))
       (not (neg? (:reward/amount proposal)))
       (string? (:reward/currency proposal))
       (= (:researcher-reward-proposal/root proposal) (proposal-root proposal))))

(defn obligation-root [obligation]
  (hash-ref/sha256-ref
   (hc/domain-hash :researcher-reward-obligation
                   (dissoc obligation :researcher-reward-obligation/root))))

(defn build-obligation
  "Build an immutable reward obligation from an exact proposal and an
   authorized approval authority. It records neither payment state nor payment
   authority."
  [{:keys [proposal eligibility approval-authority]}]
  (when-not (and (valid-proposal? proposal)
                 (= (:researcher-reward-eligibility/root proposal)
                    (:researcher-reward-eligibility/root eligibility))
                 (valid-eligibility? eligibility)
                 (= (:reward/key proposal) (:reward/key eligibility))
                 (= (select-keys proposal [:researcher/id :research-assignment/root
                                           :contribution/root :contribution/type
                                           :researcher-reward-policy/root :reward/amount :reward/currency])
                    (select-keys eligibility [:researcher/id :research-assignment/root
                                              :contribution/root :contribution/type
                                              :researcher-reward-policy/root :reward/amount :reward/currency]))
                 (= (:researcher-reward-proposal/root proposal) (proposal-root proposal))
                 (:authority/authorized? approval-authority)
                 (string? (:authority/id approval-authority))
                 (root? (:authority/root approval-authority)))
    (throw (ex-info "Reward obligation requires an authorized approval of an exact eligible proposal" {})))
  (let [obligation (assoc (select-keys proposal [:researcher-reward-proposal/root
                                                 :researcher-reward-eligibility/root
                                                 :researcher/id :research-assignment/root
                                                 :contribution/root :contribution/type
                                                 :researcher-reward-policy/root
                                                 :reward/key :reward/amount :reward/currency])
                          :schema-version obligation-schema-version
                          :approval-authority/id (:authority/id approval-authority)
                          :approval-authority/root (:authority/root approval-authority))]
    (assoc obligation :researcher-reward-obligation/root (obligation-root obligation))))

(defn valid-obligation? [obligation]
  (and (= obligation-schema-version (:schema-version obligation))
       (root? (:researcher-reward-proposal/root obligation))
       (root? (:researcher-reward-eligibility/root obligation))
       (root? (:reward/key obligation))
       (root? (:approval-authority/root obligation))
       (string? (:approval-authority/id obligation))
       (= (:researcher-reward-obligation/root obligation) (obligation-root obligation))))

(defn verify-adversarial-finding-lineage
  "Verify an existing reward obligation backwards to the exact Solidity subject
   commissioned by its generic research assignment. This composes validators;
   it creates no reward, target, evidence, or claim artifact."
  [{:keys [obligation proposal eligibility policy claim verification analysis-output
           research-command incentive-model deviation-domain research-assignment
           outcome-manifest target-binding]}]
  (let [expected-verification
        (research/verify-analysis-output
         {:research-command research-command
          :incentive-model incentive-model
          :deviation-domain deviation-domain
          :research-assignment research-assignment
          :outcome-manifest outcome-manifest
          :analysis-output analysis-output})
        expected-claim (when (research/analysis-verification-valid?
                              expected-verification analysis-output)
                         (research/derive-analytical-claim analysis-output expected-verification
                                                           incentive-model))
        errors (cond-> []
                 (not (valid-obligation? obligation)) (conj :invalid-obligation)
                 (not (valid-proposal? proposal)) (conj :invalid-proposal)
                 (not (valid-eligibility? eligibility)) (conj :invalid-eligibility)
                 (not= (:researcher-reward-proposal/root obligation)
                       (:researcher-reward-proposal/root proposal))
                 (conj :obligation-proposal-mismatch)
                 (not= (:researcher-reward-eligibility/root obligation)
                       (:researcher-reward-eligibility/root eligibility))
                 (conj :obligation-eligibility-mismatch)
                 (not= (:researcher-reward-eligibility/root proposal)
                       (:researcher-reward-eligibility/root eligibility))
                 (conj :proposal-eligibility-mismatch)
                 (not= (:researcher-reward-policy/root eligibility)
                       (:researcher-reward-policy/root policy))
                 (conj :eligibility-policy-mismatch)
                 (not= (:research-assignment/root eligibility)
                       (:research-assignment/hash research-assignment))
                 (conj :eligibility-assignment-mismatch)
                 (not= (:research-assignment/root obligation)
                       (:research-assignment/hash research-assignment))
                 (conj :obligation-assignment-mismatch)
                 (not= (:research-assignment-target-binding/assignment-root target-binding)
                       (:research-assignment/hash research-assignment))
                 (conj :target-binding-assignment-mismatch)
                 (not (:valid? (target-binding/validate-binding target-binding)))
                 (conj :invalid-target-binding)
                 (not (:valid? (assignment/validate-assignment research-assignment)))
                 (conj :invalid-research-assignment)
                 (not (:valid? (research/validate-analytical-claim claim))) (conj :invalid-claim)
                 (not= (:research-analysis-verification/root verification)
                       (:research-analysis-verification/root expected-verification))
                 (conj :verification-reconstruction-mismatch)
                 (not= (:research-claim/root claim) (:research-claim/root expected-claim))
                 (conj :claim-reconstruction-mismatch)
                 (not= (:research-claim/root claim)
                       (:contribution/root eligibility))
                 (conj :contribution-claim-mismatch)
                 (not= :adversarial-finding (:contribution/type eligibility))
                 (conj :not-adversarial-finding)
                 (not= (:research-claim/subject-root claim)
                       (:research-assignment-target-binding/subject-root target-binding))
                 (conj :claim-subject-mismatch)
                 (not= (:research-claim/root claim)
                       (get-in eligibility [:claim/evidence :research-claim/root]))
                 (conj :eligibility-claim-evidence-mismatch)
                 (not= (:research-analysis-verification/root verification)
                       (get-in eligibility [:verification/evidence
                                            :research-analysis-verification/root]))
                 (conj :eligibility-verification-evidence-mismatch))]
    {:valid? (empty? errors)
     :errors errors
     :research-assignment-target-binding/root
     (:research-assignment-target-binding/root target-binding)
     :solidity-target/root (:solidity-target/root target-binding)
     :research-claim/root (:research-claim/root claim)
     :researcher-reward-obligation/root
     (:researcher-reward-obligation/root obligation)}))
