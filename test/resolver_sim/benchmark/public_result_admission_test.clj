(ns resolver-sim.benchmark.public-result-admission-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.public-result-admission :as sut]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private test-priv-key "test-keys/test-researcher-signing-key")
(def ^:private test-pub-key "test-keys/test-researcher-signing-key.pub")

(defn- root [c]
  (str "sha256:" (apply str (repeat 64 (first c)))))

(defn- manifest [& {:keys [results generated-case-set-root]
                    :or {results {:conservation :pass}
                         generated-case-set-root (root "6")}}]
  (outcome/build-manifest
   {:benchmark/content-root (root "c")
    :benchmark/model-root (root "1")
    :benchmark/evaluation-policy-root (root "2")
    :execution/model-instance-root (root "3")
    :execution/plan-root (root "4")
    :execution/parameter-domain-root (root "5")
    :execution/sampling-policy-root (root "7")
    :execution/generated-case-set-root generated-case-set-root
    :results/operational results}))

(defn- signed-report
  "A genuinely signed report (real Ed25519 signing against the test key)."
  [m researcher run]
  (let [report (rrr/build-report
                {:outcome-manifest m
                 :researcher-id researcher
                 :runner-info {:runner/id (str "runner-" researcher)
                               :source-tree-hash (root "8")
                               :distribution-hash (root "9")
                               :environment-hash (root "a")}
                 :evidence-refs {:evidence-dag-root (root "b")
                                 :event-evidence-root (root "c")
                                 :execution-log-root (root "d")}
                 :run-id run})]
    (:report (rrr/sign-report! report test-priv-key))))

(defn- pub-key []
  (slurp test-pub-key))

;; ═══════════════════════════════════════════════════════════════════════════
;; Construction & derivation
;; ═══════════════════════════════════════════════════════════════════════════

(deftest builds-valid-admission-and-derives-fields
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (= "public-result-admission.v1" (:schema-version adm)))
    (is (= (:researcher-run-report/hash sr) (:report/root adm)))
    (is (= (:researcher-run-report/outcome-manifest-hash sr) (:manifest/root adm)))
    (is (= (:researcher-run-report/outcome-hash sr) (:outcome/root adm)))
    (is (= (:benchmark-outcome/hash m) (:manifest/root adm)))
    (is (= (outcome/outcome-hash m) (:outcome/root adm)))
    (is (string? (:admission/root adm)))))

(deftest verifies-exactly
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (:valid? (sut/verify adm sr m (pub-key))))))

(deftest admission-root-recomputes-from-body
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))
        body (dissoc adm :admission/root)
        expected (str "sha256:" (hc/domain-hash
                                 "public-result-admission.v1" body))]
    (is (= expected (:admission/root adm)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Conservative vocabulary (Task: valid admission, not researcher auth)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest uses-conservative-authenticity-vocabulary
  (let [m (manifest)
        adm (sut/build (signed-report m "r1" "run1") m (pub-key))]
    (is (= {:signature-verification :verified-against-supplied-key
            :verifying-key (sut/canonical-public-key (pub-key))
            :researcher-to-key-binding :unresolved}
           (:admission/authenticity adm)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Participation: presentation is never identity assurance
;; ═══════════════════════════════════════════════════════════════════════════

(deftest declared-presentation-is-derived-from-signed-report
  (let [m (manifest)
        adm (sut/build (signed-report m "r1" "run1") m (pub-key))]
    (is (= {:presentation :declared
            :identity-assurance :unresolved}
           (:admission/participation adm)))))

(deftest anonymous-presentation-is-derived-from-anonymous-id
  (doseq [anonymous-id ["anonymous" "anonymous-lab" "anonymous-visitor"]]
    (let [m (manifest)
          adm (sut/build (signed-report m anonymous-id "run1") m (pub-key))]
      (is (= {:presentation :anonymous
              :identity-assurance :unresolved}
             (:admission/participation adm))
          (str "id " anonymous-id " must derive :anonymous presentation")))))

(deftest participation-is-rooted-in-admission
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        a1 (sut/build sr m (pub-key))
        anon (sut/build (signed-report m "anonymous" "run1") m (pub-key))]
    (is (string? (:admission/root a1)))
    (is (not= (:admission/root a1) (:admission/root anon))
        "presentation changes the admission root")))

(deftest identity-assurance-escalation-fails-closed
  (let [escalated {:presentation :declared :identity-assurance :authenticated
                   :identity-binding-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}]
    (is (false? (sut/valid-participation? escalated))
        ":authenticated is not a legal V1 projection")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"identity-assurance escalation"
         (sut/validate-participation! escalated)))
    (is (= :identity-assurance-escalation
           (:reason (ex-data (try (sut/validate-participation! escalated)
                                  (catch clojure.lang.ExceptionInfo e e))))))
    (is (false? (sut/valid-participation? {:presentation :declared
                                           :identity-assurance :unresolved
                                           :extra :field}))
        "closed shape: exactly presentation + identity-assurance")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Canonical key — machine independent (Task: not a filesystem path)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest canonical-public-key-strips-comment
  (let [content (str/trim (pub-key))
        [algo b64] (str/split content #"\s+")
        with-comment (str algo " " b64 " user@host example")]
    (is (= (str algo " " b64) (sut/canonical-public-key with-comment)))
    (is (= (sut/canonical-public-key content) (sut/canonical-public-key with-comment)))
    (is (not (str/includes? (sut/canonical-public-key with-comment) "user@host")))))

(deftest admission-root-is-independent-of-comment
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        content (str/trim (pub-key))
        [algo b64] (str/split content #"\s+")
        base (sut/build sr m content)
        commented (sut/build sr m (str algo " " b64 " machine-1 researcher@host"))]
    (is (= base commented))
    (is (= (:admission/root base) (:admission/root commented)))))

(deftest admission-root-is-independent-of-path
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (string? (:verifying-key (get-in adm [:admission/authenticity]))))
    (is (not (str/includes? (:verifying-key (get-in adm [:admission/authenticity]))
                            "test-keys")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tamper resistance
;; ═══════════════════════════════════════════════════════════════════════════

(deftest tampered-report-hash-signature-rejects
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (:valid? (sut/verify adm sr m (pub-key))))
    (is (= :invalid-report-signature
           (:reason (sut/verify adm (assoc sr :researcher/id "evil") m (pub-key)))))
    (is (= :invalid-report-signature
           (:reason (sut/verify adm (assoc-in sr [:researcher/signature :value] "tampered") m (pub-key)))))
    (is (= :invalid-report-signature
           (:reason (sut/verify adm (update sr :researcher/signature dissoc :value) m (pub-key)))))
    (is (= :invalid-report-signature
           (:reason (sut/verify adm (assoc sr :researcher-run-report/hash (root "f")) m (pub-key)))))))

(deftest report-manifest-tampering-rejects
  (let [m (manifest)
        other (manifest :generated-case-set-root (root "f"))
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (= :report-manifest-mismatch
           (:reason (sut/verify adm sr other (pub-key)))))))

(deftest wrong-supplied-key-rejects
  (let [m (manifest)
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (= :invalid-report-signature
           (:reason (sut/verify adm sr m "ssh-ed25519 AAAA")))
        "a key that did not sign the report must not admit it")))

(deftest outcome-hash-mismatch-rejects
  (let [m (manifest)
        other (manifest :results {:conservation :pass :quota :fail})
        sr (signed-report m "r1" "run1")
        adm (sut/build sr m (pub-key))]
    (is (not= (outcome/outcome-hash m) (outcome/outcome-hash other)))
    (with-redefs [rrr/verify-against-manifest (fn [_ _] {:valid? true :mismatches []})]
      (is (= :outcome-hash-mismatch
             (:reason (sut/verify adm sr other (pub-key))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Agreement irrelevance (admission ≠ agreement)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest divergent-outcome-is-as-admissible-as-agreeing
  (let [agreeing (manifest)
        divergent (manifest :results {:conservation :pass :quota :fail})
        adm-a (sut/build (signed-report agreeing "r1" "run1") agreeing (pub-key))
        adm-d (sut/build (signed-report divergent "r2" "run2") divergent (pub-key))]
    (is (some? adm-a))
    (is (some? adm-d))
    (is (not= (:outcome/root adm-a) (:outcome/root adm-d))
        "admission preserves divergent outcomes; nothing is collapsed or rejected")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Per-report determinism & key sensitivity
;; ═══════════════════════════════════════════════════════════════════════════

(deftest admission-is-deterministic-for-same-report-and-key
  (let [m (manifest)
        sr (signed-report m "r1" "run1")]
    (is (= (sut/build sr m (pub-key))
           (sut/build sr m (pub-key))))))

(deftest different-reports-give-different-admissions
  (let [m (manifest)
        a (sut/build (signed-report m "r1" "run1") m (pub-key))
        b (sut/build (signed-report m "r2" "run2") m (pub-key))]
    (is (not= (:admission/root a) (:admission/root b)))))
