(ns resolver-sim.assurance.cancellation-gates-test
  "Tests for the four-predicate cancellation split (window / authority /
   executability / committability) and the full matrix
   (THREE_MEMBER_RESEARCHER_APPLICATION §7; three-member task Phase 3)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

(def ^:private conforming-opts {:profile-id "cfa/2-3"})

(defn- valid-certificate []
  (cfa/declare-profile {:member-count 3 :threshold 2 :profile-id "cert/2-3"}))

(defn- gates
  "Run classify-cancellation-gates with explicit gate inputs."
  [& {:keys [opts target-state certificate binding race window]
      :or {opts conforming-opts}}]
  (cfa/classify-cancellation-gates
   {:decision-opts opts
    :target-state target-state
    :certificate certificate
    :snapshot-binding-valid? binding
    :transition-won? race
    :window window}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Full matrix
;; ═══════════════════════════════════════════════════════════════════════════

(deftest matrix-open-valid-current-won-committable
  (let [r (gates :target-state :proposed
                 :certificate (valid-certificate)
                 :binding true :race true)]
    (is (true? (:cancellation/window-possible? r)))
    (is (true? (:cancellation/certificate-profile-conforming? r)))
    (is (true? (:cancellation/executable? r)))
    (is (true? (:cancellation/committable? r)))
    (is (empty? (:cancellation/blocking-reasons r)))))

(deftest matrix-open-valid-current-lost-executable-not-committable
  (let [r (gates :target-state :proposed
                 :certificate (valid-certificate)
                 :binding true :race false)]
    (is (true? (:cancellation/executable? r)))
    (is (false? (:cancellation/committable? r)))
    (is (contains? (set (:cancellation/blocking-reasons r))
                   :transition-race-lost))))

(deftest matrix-open-valid-stale-not-executable
  (let [r (gates :target-state :proposed
                 :certificate (valid-certificate)
                 :binding false :race true)]
    (is (true? (:cancellation/window-possible? r)))
    (is (true? (:cancellation/certificate-profile-conforming? r)))
    (is (false? (:cancellation/executable? r)))
    (is (false? (:cancellation/committable? r)))
    (is (contains? (set (:cancellation/blocking-reasons r))
                   :snapshot-binding-stale))))

(deftest matrix-open-missing-certificate-possible-not-authorised
  (let [r (gates :target-state :proposed
                 :certificate nil
                 :binding true :race true)]
    (is (true? (:cancellation/window-possible? r)))
    (is (false? (:cancellation/certificate-profile-conforming? r)))
    (is (false? (:cancellation/executable? r)))
    (is (false? (:cancellation/committable? r)))
    (is (contains? (set (:cancellation/blocking-reasons r))
                   :certificate-profile-not-conforming))))

(deftest matrix-closed-valid-certificate-authorised-not-executable
  (let [r (gates :target-state :consumed
                 :certificate (valid-certificate)
                 :binding true :race true)]
    (is (false? (:cancellation/window-possible? r)))
    (is (true? (:cancellation/certificate-profile-conforming? r))
        "certificate profile conforms but the window is closed")
    (is (false? (:cancellation/executable? r)))
    (is (false? (:cancellation/committable? r)))
    (is (contains? (set (:cancellation/blocking-reasons r)) :consumed))))

(deftest matrix-closed-invalid-certificate-neither
  (let [r (gates :target-state :consumed
                 :certificate nil
                 :binding true :race true)]
    (is (false? (:cancellation/window-possible? r)))
    (is (false? (:cancellation/certificate-profile-conforming? r)))
    (is (false? (:cancellation/executable? r)))
    (is (false? (:cancellation/committable? r)))))

(deftest matrix-malformed-profile-not-possible
  (let [r (gates :target-state :proposed
                 :opts {:member-count 3 :threshold 1 :profile-id "bad/1-3"}
                 :certificate (valid-certificate)
                 :binding true :race true)]
    (is (false? (:cancellation/window-possible? r)))
    (is (false? (:cancellation/executable? r))
        "a conforming certificate cannot rescue a non-conforming decision profile")
    (is (contains? (set (:cancellation/blocking-reasons r))
                   :non-conforming-decision-profile))))

(deftest matrix-closed-to-open-monotonicity-violation
  (let [bad (cfa/lifecycle-window-profile
             {:profile/id :t/bad :profile/version 1
              :valid-states #{:a :done}
              :open-states #{:a}
              :irreversible-states #{:done}
              :blocking-reason-by-state {:done :done}
              :transitions {:done #{:a}}})]
    (is (not (:valid? (cfa/validate-lifecycle-monotonicity bad)))
        "a closed->open transition is a monotonicity violation")
    (is (= :closed (:window/state
                    (cfa/classify-lifecycle-window bad :done)))
        "the cutpoint state never classifies :open")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Snapshot binding (contract 7)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private snapshot
  {:target/id "alloc/9" :target/hash "sha256:0"
   :lifecycle/profile-id :prf.lifecycle-window/probabilistic-allocation
   :lifecycle/profile-version 1
   :target/state-evidence-root "sha256:1"
   :cancellation/action :cancel :cancellation/effects :prevent
   :cancellation/reason :mistake :decision/profile-id "2-3"
   :policy/instance "pol/1" :decision/validity-window "t0..t1"
   :conflict/consumption-key "ck"})

(deftest current-snapshot-binding-matches
  (is (true? (cfa/current-snapshot-binding-valid? snapshot snapshot)))
  (is (false? (cfa/current-snapshot-binding-valid?
               (assoc snapshot :lifecycle/profile-version 2) snapshot)))
  (is (false? (cfa/current-snapshot-binding-valid?
               (dissoc snapshot :target/hash) snapshot))))

(deftest certificate-does-not-routable-deterministic-ops
  (testing "deterministic operations are never canonical cancellation decisions,
            even when a valid certificate exists"
    (doseq [op [:submit-cancel-request :expire-at-deadline
                :deterministic-invalidation :reject-post-cutpoint-cancellation
                :execute-certified-cancellation :apply-deterministic-fallback]]
      (is (false? (cfa/cancellation-decision-required? op))
          (str op " must stay deterministic")))
    (is (true? (cfa/cancellation-decision-required?
                :decide-cancel-valid-authorisation))))
  (testing "a certificate does not open a closed window"
    (let [r (gates :target-state :consumption-receipt-terminal
                   :certificate (valid-certificate)
                   :binding true :race true)]
      (is (false? (:cancellation/executable? r))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Deprecation: window-possible? vs legacy combined possible?
;; ═══════════════════════════════════════════════════════════════════════════

(deftest legacy-possible-deprecated-derived-view
  (let [r (cfa/classify-cancellation conforming-opts :proposed)]
    (is (= (:cancellation/window-possible? r)
           (:cancellation/possible? r))
        "legacy :cancellation/possible? is a derived view of window-possible?")
    (is (true? (:cancellation/window-possible? r)))
    (is (not (contains? r :cancellation/certificate-profile-conforming?))
        "classify-cancellation never claims certificate authority")))

(deftest predicate-ownership
  (testing "window gate contains no researcher decision semantics"
    (let [w (cfa/classify-lifecycle-window cfa/force-authorisation-window :proposed)]
      (is (not (contains? w :member-count)))
      (is (not (contains? w :threshold)))))
  (testing "authority gate never reopens lifecycle state"
    (is (true? (cfa/cancellation-certificate-profile-conforming? (valid-certificate))))
    (is (false? (cfa/cancellation-certificate-profile-conforming? nil)))
    (is (false? (cfa/cancellation-certificate-profile-conforming?
                 (cfa/declare-profile {:member-count 3 :threshold 1
                                       :profile-id "bad"}))))))
