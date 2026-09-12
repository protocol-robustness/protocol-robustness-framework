(ns resolver-sim.benchmark.evidence-attestation-test
  "Producer attestation for benchmark final evidence:
   integrity gate → detached Ed25519 binding → anti-transplant verification.
   Unsigned bundles remain first-class (integrity-assured only)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.evidence-attestation :as att]
            [resolver-sim.benchmark.final-evidence-boundary-test :as boundary]
            [resolver-sim.benchmark.integrity :as integrity])
  (:import [java.nio.file Files]
           [java.security KeyPairGenerator]))

(def ^:private tmp-dir
  (str (Files/createTempDirectory "evidence-attestation"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- keypair []
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))

(defn- persisted-bundle! [file-name]
  (let [path (str tmp-dir "/" file-name)
        bundle (#'boundary/finalize (#'boundary/make-bundle {}))]
    (spit path (pr-str bundle))
    {:path path :bundle bundle}))

(deftest unsigned-evidence-is-integrity-assured-only
  (let [{:keys [path bundle]} (persisted-bundle! "unsigned.edn")]
    (testing "no attestation artifact exists"
      (is (false? (.exists (io/file (str path ".attestation.json")))))
      (is (nil? (att/load-attestation (str path ".attestation.json")))))
    (testing "integrity verification stands alone"
      (is (:hash-ok? (integrity/verify-bundle-hash bundle))))))

(deftest attestation-build-requires-integrity
  (let [{:keys [bundle]} (persisted-bundle! "broken.edn")
        broken (assoc-in bundle [:metrics :passed] 999)
        kp (keypair)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integrity"
                          (att/build-attestation broken "k1" (.getPrivate kp))))))

(deftest sign-and-verify-roundtrip
  (let [{:keys [path bundle]} (persisted-bundle! "signed.edn")
        kp (keypair)
        att-path (att/write-attestation! path bundle "producer-key" (.getPrivate kp))
        attestation (att/load-attestation att-path)]
    (testing "artifact written as sibling with expected schema"
      (is (= att/schema-version (:schema-version attestation))))
    (testing "full verification passes"
      (let [result (att/verify-attestation bundle attestation (.getPublic kp))]
        (is (:valid? result))
        (is (:integrity-ok? result))
        (is (:transplant-safe? result))
        (is (= "producer-key" (:key-id result)))))
    (testing "subject binds root, commitment scheme, and benchmark id"
      (is (= (str "sha256:" (:evidence/hash bundle))
             (get-in attestation [:payload :subject :evidence/root])))
      (is (= :demo-pack (get-in attestation [:payload :subject :benchmark/id]))))))

(deftest transplant-onto-different-evidence-fails
  (let [{:keys [path bundle]} (persisted-bundle! "victim.edn")
        other (#'boundary/finalize
               (#'boundary/make-bundle {:commitment-version "bundle-root.v2"}))
        kp (keypair)
        attestation (att/build-attestation bundle "k1" (.getPrivate kp))]
    (testing "different content, same producer key: subject mismatch"
      (let [result (att/verify-attestation other attestation (.getPublic kp))]
        (is (false? (:valid? result)))
        (is (false? (:transplant-safe? result)))
        (is (= :subject-mismatch (:reason result)))))))

(deftest tampered-evidence-after-signing-is-caught
  (let [{:keys [path bundle]} (persisted-bundle! "tampered.edn")
        kp (keypair)
        attestation (att/build-attestation bundle "k1" (.getPrivate kp))
        mutated (assoc-in bundle [:results 0 :outcome] :failed)]
    (let [result (att/verify-attestation mutated attestation (.getPublic kp))]
      (is (false? (:valid? result)))
      ;; integrity gate fires before the signature even matters
      (is (false? (:integrity-ok? result))))))

(deftest wrong-producer-key-fails-crypto-check
  (let [{:keys [bundle]} (persisted-bundle! "wrongkey.edn")
        signer (keypair)
        impostor (keypair)
        attestation (att/build-attestation bundle "k1" (.getPrivate signer))]
    (let [result (att/verify-attestation bundle attestation (.getPublic impostor))]
      (is (false? (:valid? result)))
      (is (:transplant-safe? result))                 ; identity binds correctly…
      (is (= :invalid-signature (:reason result)))))) ; …but the signature lies

(deftest idempotent-rewrite-allowed-distinct-overwrite-refused
  (let [{:keys [path bundle]} (persisted-bundle! "idem.edn")
        kp (keypair)]
    (att/write-attestation! path bundle "k1" (.getPrivate kp))
    (testing "identical re-attestation is a no-op"
      (is (string? (att/write-attestation! path bundle "k1" (.getPrivate kp)))))
    (testing "distinct attestation over the same file is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overwrite"
                            (att/write-attestation!
                             path bundle "k1" (.getPrivate kp)
                             {:note "different notes make a distinct envelope"}))))))

(deftest signing-does-not-perturb-the-content-root
  ;; The conceptual separation, asserted: producing an attestation must not
  ;; change the evidence bytes' committed root in any way.
  (let [{:keys [bundle]} (persisted-bundle! "pure.edn")
        before (:evidence/hash bundle)
        kp (keypair)]
    (att/build-attestation bundle "k1" (.getPrivate kp))
    (is (= before (:evidence/hash bundle) (integrity/bundle-root-hash bundle)))))
