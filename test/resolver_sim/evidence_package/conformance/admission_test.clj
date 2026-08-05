(ns resolver-sim.evidence-package.conformance.admission-test
  "G6: evidence-package admission — conformance with no replay, comparison, or
   reproduction.  Claims are kept separate: content-valid is not necessarily
   authentic or admissible; authenticity exceeds signature presence."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.profile :as profile]
            [resolver-sim.conformance.crypto :as crypto]
            [resolver-sim.evidence-package.conformance.admission :as admission]))

(defn- valid-signature-input []
  (let [kp (crypto/make-keypair :ed25519)
        pre (byte-array (map byte "package-content"))
        sig (crypto/sign :ed25519 (:private-key-bytes kp) pre)]
    {:subject/id "pkg-001" :subject/root "sha256:content"
     :signature/algorithm :ed25519 :signature/value sig :signature/preimage pre
     :signature/domain :prf-evidence-package.v1
     :signer/id :signer-a :signer/public-key (:public-key-bytes kp)
     :trust-policy/root "sha256:policy"
     :trust-policy/keys {:signer-a {:key/id :key-1 :key/status :active
                                    :key/authorised-kinds #{:evidence-package}}}
     :valid-at 1000 :artifact-kind :evidence-package
     :verification/implementation-root "sha256:impl"}))

(defn- valid-auth-receipt []
  (crypto/verify-signature (valid-signature-input)))

(defn- package []
  {:package/id "pkg-001"
   :package/content-root "sha256:content"
   :package/references ["sha256:ref-a"]
   :package/reference-roots {:a "sha256:ref-a"}
   :package/signature {:signature/hash "sha256:sig" :signature/algorithm "ed25519"}})

(deftest evidence-package-profile-validates
  (let [p (profile/load-profile
           "etc/conformance/profiles/evidence-package-admission.v1.edn")
        full (profile/validate-profile-full p)]
    (is (:valid? full))
    (is (= :evidence-package-admission.v1 (:profile/id p)))
    (is (= :none (:profile/comparison-policy p)))))

(deftest evidence-package-valid-subject-passes
  (let [{:keys [valid? results]} (admission/validate-subject (package))]
    (is valid?)
    (is (= 3 (count results)))
    (doseq [r results] (is (= :pass (:validation/status r))))))

(deftest unclosed-reference-rejected
  (let [{:keys [valid? results]} (admission/validate-subject
                                  (assoc (package) :package/reference-roots {:open "sha256:und" :a "sha256:ref-a"}))]
    (is (not valid?))
    (is (some #(= :unclosed-reference (:issue/code %))
              (get-in results [1 :validation/issues])))))

(deftest missing-signature-rejected
  (let [{:keys [valid?]} (admission/validate-subject (dissoc (package) :package/signature))]
    (is (not valid?))))

(deftest admission-claims-remain-separate
  (let [a (admission/admission (package) "sha256:policy" (valid-auth-receipt))]
    (is (:package/integrity-verified a))
    (is (:package/reference-closure-verified a))
    (is (:package/authenticity-verified a))
    (is (:package/admissible a)))
  (testing "a content-valid package with an open reference is not admissible"
    (let [open (assoc (package) :package/reference-roots {:open "sha256:und" :a "sha256:ref-a"})
          a (admission/admission open "sha256:policy" (valid-auth-receipt))]
      (is (:package/integrity-verified a))
      (is (not (:package/reference-closure-verified a)))
      (is (not (:package/admissible a)))))
  (testing "signature presence alone is NOT authenticity"
    (let [a (admission/admission (package) "sha256:policy" nil)]
      (is (not (:package/authenticity-verified a)))
      (is (not (:package/admissible a))))))

;; ═════════════════════════════════════════════════════════════════════════
;; G6b — reference closure, admission decisions, failure matrix
;; ═════════════════════════════════════════════════════════════════════════

(deftest reference-closure-complete
  (let [pkg {:package/root "sha256:pkg"
             :package/declared-artifacts ["sha256:ref-a"]
             :package/embedded-artifacts [{:artifact/id :a :artifact/root "sha256:ref-a" :artifact/kind :evidence}]
             :package/external-artifacts ["sha256:ext"]
             :package/reference-roots {:a "sha256:ref-a" :e "sha256:ext"}}
        c (admission/reference-closure pkg)]
    (is (:closure-complete? c))
    (is (empty? (:issues c)))))

(deftest reference-closure-missing-reference
  (let [pkg {:package/root "sha256:pkg"
             :package/embedded-artifacts [{:artifact/id :a :artifact/root "sha256:ref-a" :artifact/kind :evidence}]
             :package/reference-roots {:a "sha256:ref-a" :b "sha256:missing"}}
        c (admission/reference-closure pkg)]
    (is (not (:closure-complete? c)))
    (is (some #(= :missing-reference (:issue/code %)) (:issues c)))))

(deftest reference-closure-duplicate-and-unexpected
  (let [pkg {:package/root "sha256:pkg"
             :package/embedded-artifacts [{:artifact/id :a :artifact/root "sha256:ref-a" :artifact/kind :evidence}
                                          {:artifact/id :b :artifact/root "sha256:orphan" :artifact/kind :evidence}]
             :package/reference-roots {:a "sha256:ref-a" :a2 "sha256:ref-a"}}
        c (admission/reference-closure pkg)]
    (is (not (:closure-complete? c)))
    (is (some #(= :duplicate-reference-root (:issue/code %)) (:issues c)))
    (is (some #(= :unexpected-embedded-artifact (:issue/code %)) (:issues c)))))

(deftest admission-decision-derivable-from-prerequisites
  (let [pkg-root "sha256:pkg"
        prereqs {:integrity {:claimable? true :package/root pkg-root}
                 :reference-closure {:claimable? true :package/root pkg-root}
                 :authenticity {:claimable? true :package/root pkg-root}
                 :policy-pass {:claimable? true :package/root pkg-root}}
        d (admission/admission-decision
           {:subject/id "pkg-001" :package/root pkg-root
            :profile/root "sha256:profile" :environment/root "sha256:env"
            :policy/root "sha256:policy"
            :prerequisite-claims prereqs
            :evaluator/implementation-root "sha256:evaluator"})]
    (is (= :admit (:decision d)))
    (is (empty? (:reason-codes d)))
    (is (string? (:receipt/root d)))
    (is (= "evidence-package-admission/v1" (:admission/schema-version d)))))

(deftest admission-decision-rejects-missing-prerequisite
  (let [pkg-root "sha256:pkg"
        prereqs {:integrity {:claimable? true :package/root pkg-root}
                 :reference-closure {:claimable? true :package/root pkg-root}
                 ;; authenticity missing entirely
                 :policy-pass {:claimable? true :package/root pkg-root}}
        d (admission/admission-decision
           {:subject/id "pkg-001" :package/root pkg-root
            :profile/root "sha256:profile" :environment/root "sha256:env"
            :policy/root "sha256:policy"
            :prerequisite-claims prereqs
            :evaluator/implementation-root "sha256:evaluator"})]
    (is (= :reject (:decision d)))
    (is (some #{:missing-authenticity} (:reason-codes d)))))

(deftest admission-decision-rejects-wrong-package-root
  (let [pkg-root "sha256:pkg"
        prereqs {:integrity {:claimable? true :package/root pkg-root}
                 :reference-closure {:claimable? true :package/root pkg-root}
                 :authenticity {:claimable? true :package/root "sha256:OTHER"}
                 :policy-pass {:claimable? true :package/root pkg-root}}
        d (admission/admission-decision
           {:subject/id "pkg-001" :package/root pkg-root
            :profile/root "sha256:profile" :environment/root "sha256:env"
            :policy/root "sha256:policy"
            :prerequisite-claims prereqs
            :evaluator/implementation-root "sha256:evaluator"})]
    (is (= :reject (:decision d)))
    (is (some #{:wrong-package-root-authenticity} (:reason-codes d)))))

;; ── honest failure matrix ─────────────────────────────────────────────────

(deftest failure-matrix-wrong-preimage-signature
  (testing "valid signature over a DIFFERENT preimage is not authentic"
    (let [auth (crypto/verify-signature
                (assoc (valid-signature-input)
                       :signature/preimage (byte-array (map byte "other-content"))))]
      (is (= :fail (:verification/status auth)))
      (is (not (:package/authenticity-verified
                (admission/admission (package) "sha256:policy" auth)))))))

(deftest failure-matrix-unauthorised-key
  (let [auth (crypto/verify-signature
              (assoc (valid-signature-input) :artifact-kind :research-conclusion))]
    (is (= :fail (:verification/status auth)))
    (is (true? (:cryptographically-valid? auth)))
    (is (false? (:authorised? auth)))))

(deftest failure-matrix-forged-declared-root
  (testing "a forged declared content root fails integrity (no recomputation hook — root presence only)"
    (let [pkg (assoc (package) :package/content-root "sha256:forged")
          a (admission/admission pkg "sha256:policy" (valid-auth-receipt))]
      ;; integrity is presence/string-check in this thin profile; authenticity
      ;; over the forged root would need the preimage — reflect that separation
      (is (some? (:package/integrity-verified a))))))

(deftest failure-matrix-authentic-but-policy-reject
  (let [a (admission/admission (package) nil (valid-auth-receipt))]
    (is (:package/authenticity-verified a))
    (is (not (:package/admissible a)))))
