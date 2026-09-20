(ns resolver-sim.benchmark.research-claim-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- root [label]
  (ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- claim-and-verification []
  (let [output-root (root :output)
        verification-base {:schema-version "research-analysis-verification.v1"
                           :research-analysis-verification/output-root output-root
                           :research-analysis-verification/command-root (root :command)
                           :research-analysis-verification/model-root (root :model)
                           :research-analysis-verification/domain-root (root :domain)
                           :research-analysis-verification/assignment-root (root :assignment)
                           :research-analysis-verification/outcome-root (root :outcome)
                           :research-analysis-verification/status :verified
                           :research-analysis-verification/claim-status :not-established
                           :errors []}
        verification (assoc verification-base
                            :research-analysis-verification/root
                            (analysis/verification-root verification-base))
        claim-base {:schema-version "research-analytical-claim.v2"
                    :research-claim/analysis-output-root output-root
                    :research-claim/subject-root (root :subject)
                    :research-claim/model-root (root :model)
                    :research-claim/proposition-id :claim/example
                    :research-claim/domain-root (root :domain)
                    :research-claim/metric-id :metric/example
                    :research-claim/result {:result/value 1}
                    :research-claim/evidence-root (root :evidence)
                    :research-claim/verification-root
                    (:research-analysis-verification/root verification)
                    :research-claim/status :not-established}]
    [(assoc claim-base :research-claim/root (analysis/claim-root claim-base)) verification]))

(defn- fixture []
  (let [[claim verification] (claim-and-verification)
        policy (acceptance/build-policy
                {:acceptance-policy/id :policy/direct
                 :acceptance-policy/authority-root (root :authority)})]
    {:claim claim :verification verification :policy policy}))

(defn- accept! [context seq]
  (acceptance/build-acceptance
   (assoc context
          :decision :accepted
          :decision-evidence-root (root :decision-evidence)
          :accepted-at "2026-09-20T00:00:00Z"
          :acceptance-sequence seq)))

(defn- reject! [context seq]
  (acceptance/build-acceptance
   (assoc context
          :decision :rejected
          :decision-evidence-root (root :decision-evidence)
          :accepted-at "2026-09-20T00:00:00Z"
          :acceptance-sequence seq)))

(defn- first-acceptance-log [context]
  (let [acceptance (accept! context 0)]
    {:claim-root (:research-claim/root (:claim context))
     :policy-root (:research-claim-acceptance-policy/root (:policy context))
     :transitions [(lifecycle/build-transition
                    {:acceptance acceptance
                     :from :unseen :to :accepted
                     :sequence 0 :supersession? false})]
     :acceptances {0 acceptance}}))

(deftest genesis-state-reconstructs-to-unseen
  (let [context (fixture)
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle
                       {:claim-root (:research-claim/root (:claim context))
                        :policy-root (:research-claim-acceptance-policy/root (:policy context))
                        :transitions []})]
    (is (= :unseen (:research-claim-lifecycle/state reconstructed)))
    (is (= -1 (:research-claim-lifecycle/transition-sequence reconstructed)))
    (is (:valid? (lifecycle/validate-persisted-state reconstructed reconstructed)))))

(deftest accepted-claim-reconstructs-to-accepted-state
  (let [context (fixture)
        log (first-acceptance-log context)
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle log)]
    (is (= :accepted (:research-claim-lifecycle/state reconstructed)))
    (is (= 0 (:research-claim-lifecycle/transition-sequence reconstructed)))
    (is (= 1 (count (:research-claim-lifecycle/transition-roots reconstructed))))
    (is (= (:research-claim-lifecycle/root reconstructed)
           (lifecycle/state-root reconstructed)))))

(deftest reconstructed-state-matches-persisted-state
  (let [context (fixture)
        log (first-acceptance-log context)
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle log)
        persisted reconstructed]
    (is (:valid? (lifecycle/validate-persisted-state persisted reconstructed)))))

(deftest duplicate-acceptance-is-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (accept! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :unseen :to :accepted
                             :sequence 1 :supersession? false})]
             :acceptances {0 a0 1 a1}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest accept-to-reject-without-supersession-is-unauthorised
  (let [context (fixture)
        a1 (reject! context 1)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transition is invalid"
                          (lifecycle/build-transition
                           {:acceptance a1 :from :accepted :to :rejected
                            :sequence 1 :supersession? false})))))

(deftest reject-to-accept-without-supersession-is-unauthorised
  (let [context (fixture)
        a1 (accept! context 1)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transition is invalid"
                          (lifecycle/build-transition
                           {:acceptance a1 :from :rejected :to :accepted
                            :sequence 1 :supersession? false})))))

(deftest transition-from-wrong-prior-state-is-rejected
  (let [context (fixture)
        a1 (reject! context 1)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transition is invalid"
                          (lifecycle/build-transition
                           {:acceptance a1 :from :accepted :to :accepted
                            :sequence 1 :supersession? false})))))

(deftest wrong-prior-state-in-log-is-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :unseen :to :rejected
                             :sequence 1 :supersession? false})]
             :acceptances {0 a0 1 a1}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest claim-root-substitution-is-rejected
  (let [context (fixture)
        log (first-acceptance-log context)
        transition (-> log :transitions first)
        forged (assoc transition :research-claim-lifecycle/claim-root (root :other-claim))]
    (let [result (lifecycle/validate-transition forged (get (:acceptances log) 0))]
      (is (not (:valid? result)))
      (is (some #{:claim-root-mismatch} (:errors result))))))

(deftest acceptance-root-substitution-is-rejected
  (let [context (fixture)
        other (accept! context 9)
        log (first-acceptance-log context)
        transition (-> log :transitions first)
        forged (assoc transition
                      :research-claim-lifecycle/acceptance-root
                      (:research-claim-acceptance/root other))]
    (let [result (lifecycle/validate-transition forged (get (:acceptances log) 0))]
      (is (not (:valid? result)))
      (is (some #{:acceptance-root-mismatch} (:errors result))))))

(deftest policy-root-substitution-is-rejected
  (let [context (fixture)
        log (first-acceptance-log context)
        transition (-> log :transitions first)
        forged (assoc transition :research-claim-lifecycle/policy-root (root :other-policy))]
    (let [result (lifecycle/validate-transition forged (get (:acceptances log) 0))]
      (is (not (:valid? result)))
      (is (some #{:policy-root-mismatch} (:errors result))))))

(deftest authority-root-substitution-is-rejected
  (let [context (fixture)
        log (first-acceptance-log context)
        transition (-> log :transitions first)
        forged (assoc transition :research-claim-lifecycle/authority-root (root :other-authority))]
    (let [result (lifecycle/validate-transition forged (get (:acceptances log) 0))]
      (is (not (:valid? result)))
      (is (some #{:authority-root-mismatch} (:errors result))))))

(deftest sequence-replay-is-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 0 :supersession? true})]
             :acceptances {0 a0 1 a1}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest sequence-skipping-is-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 5 :supersession? true})]
             :acceptances {0 a0 1 a1}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest transition-reordering-is-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 1 :supersession? true})
                           (lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})]
             :acceptances {0 a0 1 a1}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest forked-transitions-from-same-prior-state-are-rejected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        a2 (reject! context 2)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 1 :supersession? true})
                           (lifecycle/build-transition
                            {:acceptance a2 :from :accepted :to :superseded
                             :sequence 2 :supersession? true})]
             :acceptances {0 a0 1 a1 2 a2}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle log)))))

(deftest persisted-state-tampering-is-detected
  (let [context (fixture)
        log (first-acceptance-log context)
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle log)
        tampered (assoc reconstructed :research-claim-lifecycle/state :rejected)]
    (let [result (lifecycle/validate-persisted-state tampered reconstructed)]
      (is (not (:valid? result)))
      (is (some #{:state-mismatch} (:errors result))))))

(deftest persisted-state-root-tampering-is-detected
  (let [context (fixture)
        log (first-acceptance-log context)
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle log)
        tampered (assoc reconstructed :research-claim-lifecycle/root (root :other))]
    (let [result (lifecycle/validate-persisted-state tampered reconstructed)]
      (is (not (:valid? result)))
      (is (some #{:state-mismatch :state-root-mismatch} (:errors result))))))

(deftest transition-history-truncation-is-detected
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 1 :supersession? true})]
             :acceptances {0 a0 1 a1}}
        persisted (lifecycle/reconstruct-research-claim-lifecycle log)
        truncated (lifecycle/reconstruct-research-claim-lifecycle
                   (update log :transitions butlast))]
    (is (= :superseded (:research-claim-lifecycle/state persisted)))
    (is (not= persisted truncated))
    (is (not (:valid? (lifecycle/validate-persisted-state truncated persisted))))))

(deftest cross-claim-lifecycle-replay-is-rejected
  (let [context (fixture)
        log (first-acceptance-log context)
        other-claim-root (root :other-claim)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be reconstructed"
                          (lifecycle/reconstruct-research-claim-lifecycle
                           (assoc log :claim-root other-claim-root))))))

(deftest append-only-supersession-preserves-history
  (let [context (fixture)
        a0 (accept! context 0)
        a1 (reject! context 1)
        log {:claim-root (:research-claim/root (:claim context))
             :policy-root (:research-claim-acceptance-policy/root (:policy context))
             :transitions [(lifecycle/build-transition
                            {:acceptance a0 :from :unseen :to :accepted
                             :sequence 0 :supersession? false})
                           (lifecycle/build-transition
                            {:acceptance a1 :from :accepted :to :superseded
                             :sequence 1 :supersession? true})]
             :acceptances {0 a0 1 a1}}
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle log)]
    (is (= :superseded (:research-claim-lifecycle/state reconstructed)))
    (is (= 2 (count (:research-claim-lifecycle/transition-roots reconstructed))))
    (is (not= (:research-claim-lifecycle/root (lifecycle/reconstruct-research-claim-lifecycle
                                               (update log :transitions butlast)))
              (:research-claim-lifecycle/root reconstructed)))))
