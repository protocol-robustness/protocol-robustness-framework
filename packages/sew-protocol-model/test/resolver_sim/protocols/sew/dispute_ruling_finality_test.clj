(ns resolver-sim.protocols.sew.dispute-ruling-finality-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.adjudication-authentication-verification :as authentication]
            [resolver-sim.protocols.sew.adjudication-case :as adjudication-case]
            [resolver-sim.protocols.sew.adjudication-instance :as adjudication-instance]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]
            [resolver-sim.protocols.sew.dispute-ruling-finality :as finality]
            [resolver-sim.protocols.sew.lifecycle :as lifecycle]
            [resolver-sim.protocols.sew.resolution-basis :as resolution-basis]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- external-roots []
  {:dispute/question-root (root :question)
   :dispute/evidence-set-root (root :evidence-set)
   :authority/resolution-basis-root (root :authority-basis)
   :authority/context-root (root :authority)
   :configuration/root (root :policy)})

(defn- world []
  (let [snapshot (snap-fix/escrow-snapshot {:appeal-window-duration 3600})
        created (lifecycle/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" 1000
                                         (t/make-escrow-settings {}) snapshot)
        base (-> (:world created)
                 time-ctx/ensure-temporal-context
                 (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                 (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                 (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xResolver")
                 (assoc-in [:dispute-timestamps 0] 1000)
                 (assoc-in [:params :max-dispute-level] 0))
        evidence (root :evidence)
        prepared (-> base
                     (assoc-in [:evidence-hashes 0] [evidence])
                     (assoc-in [:evidence-provenance-verified 0 evidence] :verified)
                     (assoc-in [:dispute-evidence-admissions 0 0]
                               {:status :closed :resolver "0xResolver" :parent-round nil
                                :parent-evidence-set/root nil
                                :members [{:evidence/root evidence :evidence/role :claimant}]}))
        fence (adjudication-case/adjudication-round-fence-root prepared 0 0)
        published (adjudication-case/publish-adjudication-case! prepared 0 0 fence)]
    (:world (adjudication-instance/freeze-instance! (:world published) 0))))

(defn- inputs [outcome]
  (let [world (world)
        basis (:basis (resolution-basis/reconstruct-live-resolution-basis
                       world 0 (external-roots) {:mode :legacy-non-authoritative}))
        generic (resolution-basis/project-adjudication-basis basis)
        instance-root (get-in world [:adjudication-current-instance-root 0])
        case-root (get-in world [:adjudication-published-cases 0 0 :case/root])
        ruling (ruling/build-dispute-ruling
                {:ruling/basis-root (:adjudication-basis/root generic)
                 :ruling/dispute-root (root :dispute)
                 :ruling/authority-root (:adjudication/authority-root generic)
                 :ruling/adjudication-instance-root instance-root
                 :ruling/case-root case-root
                 :ruling/provider :kleros
                 :ruling/external-dispute-id "42"
                 :ruling/external-ruling-id "7"
                 :ruling/round 0
                 :ruling/outcome outcome
                 :ruling/status :appealable
                 :ruling/evidence-root (root :evidence)
                 :ruling/policy-root (:adjudication/policy-root generic)
                 :ruling/issued-at 1000
                 :ruling/authentication-root (root :authentication)})
        verification (authentication/build-authentication-verification
                      {:authentication-verification/ruling-root (:ruling/root ruling)
                       :authentication-verification/authentication-root (:ruling/authentication-root ruling)
                       :authentication-verification/adjudication-instance-root instance-root
                       :authentication-verification/issuer "0xResolver"
                       :authentication-verification/method :ethereum-signature
                       :authentication-verification/credential-material-root (root :credential)
                       :authentication-verification/external-subject "0xResolver"
                       :authentication-verification/provider-evidence-root (root :provider-evidence)
                       :authentication-verification/observation-root (root :observation)
                       :authentication-verification/observed-at 1000
                       :authentication-verification/result :authenticated})]
    {:world world :ruling ruling :verification verification
     :finality (finality/derive-finality world 0 ruling verification)}))

(deftest finality-derives-from-authoritative-final-round-not-ruling-status
  (let [{:keys [world ruling verification] derived-finality :finality} (inputs :release)]
    (is (= :appealable (:ruling/status ruling)))
    (is (= :authoritative-final-round (:finality/reason derived-finality)))
    (is (:valid? (finality/validate-finality derived-finality)))
    (is (:valid? (finality/validate-finality-against-context derived-finality world 0 ruling verification)))))

(deftest finality-rejects-nonfinal-round-and-refusal
  (let [{:keys [world ruling verification]} (inputs :release)
        nonfinal (assoc-in world [:params :max-dispute-level] 1)
        refusal (inputs :refuse)]
    (is (nil? (finality/derive-finality nonfinal 0 ruling verification)))
    (is (contains? (set (:errors (finality/validate-finality-against-context
                                  (finality/derive-finality world 0 ruling verification)
                                  nonfinal 0 ruling verification)))
                   :authoritative-final-round-not-satisfied))
    (is (nil? (:finality refusal)))
    (is (contains? (set (:errors (finality/validate-finality-against-context
                                  nil (:world refusal) 0 (:ruling refusal) (:verification refusal))))
                   :unsupported-finality-outcome))))

(deftest finality-reconstruction-rejects-canonical-substitutions
  (let [{:keys [world ruling verification] derived-finality :finality} (inputs :release)
        rebuild #(assoc % :finality/root
                        (str "sha256:" (hc/domain-hash finality/domain
                                                       (hc/project-canonical-safe
                                                        (dissoc % :finality/root)))))
        substituted (for [[k value] [[:finality/ruling-root (root :other-ruling)]
                                     [:finality/authentication-verification-root (root :other-auth)]
                                     [:finality/case-root (root :other-case)]
                                     [:finality/adjudication-instance-root (root :other-instance)]
                                     [:finality/workflow-id 1]
                                     [:finality/round-id 1]
                                     [:finality/reason :other]]]
                      (rebuild (assoc derived-finality k value)))]
    (is (every? #(not (:valid? (finality/validate-finality-against-context
                                % world 0 ruling verification)))
                substituted))
    (is (contains? (set (:errors (finality/validate-finality
                                  (assoc derived-finality :finality/root (root :corrupt)))))
                   :finality-root-mismatch))))

(deftest finality-cannot-authorize-an-alternate-ruling-or-authentication
  (let [{:keys [world] finality-artifact :finality
         original-ruling :ruling original-verification :verification} (inputs :release)
        {alternate-ruling :ruling} (inputs :refund)
        replacement-verification
        (authentication/build-authentication-verification
         (assoc (dissoc original-verification :authentication-verification/root)
                :authentication-verification/issuer "0xReplacement"
                :authentication-verification/external-subject "0xReplacement"))
        alternate-ruling-result
        (finality/validate-finality-against-context
         finality-artifact world 0 alternate-ruling original-verification)
        replacement-authentication-result
        (finality/validate-finality-against-context
         finality-artifact world 0 original-ruling replacement-verification)
        rebuild #(assoc % :finality/root
                        (str "sha256:" (hc/domain-hash finality/domain
                                                       (hc/project-canonical-safe
                                                        (dissoc % :finality/root)))))
        unsupported-reason-result
        (finality/validate-finality
         (rebuild (assoc finality-artifact :finality/reason :appeals-exhausted)))]
    (is (contains? (set (:errors alternate-ruling-result))
                   :finality-ruling-binding-mismatch))
    (is (contains? (set (:errors replacement-authentication-result))
                   :finality-authentication-binding-mismatch))
    (is (contains? (set (:errors unsupported-reason-result))
                   :invalid-finality-reason))))
