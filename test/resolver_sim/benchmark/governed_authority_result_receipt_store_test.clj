(ns resolver-sim.benchmark.governed-authority-result-receipt-store-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.authority-semantics-policy :as policy]
            [resolver-sim.benchmark.governed-authority-result-receipt :as receipt]
            [resolver-sim.benchmark.governed-authority-result-receipt-store :as receipt-store]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.io.content-addressed-store :as cas])
  (:import [java.nio.file Files]))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(defn- fresh-cas []
  (cas/create-store
   (str (Files/createTempDirectory "governed-authority-receipt-"
                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- c4-dependencies []
  (let [semantics-body semantics/default-semantics
        policy-body (policy/build-policy
                     {:authority-semantics/root
                      (:governed-authority-semantics/root semantics-body)})
        configuration-body (assoc genesis/chain-configuration-fixture
                                  :configuration/schema genesis/chain-configuration-v2-schema
                                  :authority-semantics-policy/root
                                  (:authority-semantics-policy/root policy-body))
        configuration-root (genesis/chain-configuration-root configuration-body)]
    {:configuration configuration-body
     :configuration-root configuration-root
     :policy policy-body
     :policy-root (:authority-semantics-policy/root policy-body)
     :semantics semantics-body
     :semantics-root (:governed-authority-semantics/root semantics-body)
     :dependencies {configuration-root configuration-body
                    (:authority-semantics-policy/root policy-body) policy-body
                    (:governed-authority-semantics/root semantics-body) semantics-body}}))

(defn- c4-receipt [{:keys [configuration-root policy-root semantics-root]}]
  (receipt/build-receipt
   {:pre-authoritative-state-envelope/root (hash-ref "a")
    :post-authoritative-state-envelope/root (hash-ref "b")
    :transaction/state-before-root (hash-ref "c")
    :transaction/state-after-root (hash-ref "d")
    :authority-report/root (hash-ref "e")
    :resolved-review-authority-context/root (hash-ref "f")
    :governed-authority-transition-binding/root (hash-ref "1")
    :pre-chain-configuration/root configuration-root
    :pre-authority-semantics-policy/root policy-root
    :pre-governed-authority-semantics/root semantics-root
    :successor-chain-configuration/root configuration-root
    :successor-authority-semantics-policy/root policy-root
    :successor-governed-authority-semantics/root semantics-root}))

(defn- failure-reason [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:reason (ex-data error)))))

(defn- persisted-c4 []
  (let [backend (fresh-cas)
        inputs (c4-dependencies)
        artifact (c4-receipt inputs)]
    (receipt-store/persist-receipt! backend artifact)
    {:backend backend
     :inputs inputs
     :artifact artifact
     :root (:governed-authority-result-receipt/root artifact)}))

(deftest fresh-store-readback-verifies-detached-c4-lineage
  (let [{:keys [backend inputs artifact root]} (persisted-c4)
        fresh-instance (cas/create-store (:root backend))]
    (is (= artifact
           (receipt-store/read-receipt! fresh-instance root
                                        #(get (:dependencies inputs) %))))
    (is (= artifact
           (receipt-store/read-receipt! backend root (:dependencies inputs))))))

(deftest readback-rejects-tampered-cas-bytes
  (let [{:keys [backend inputs root]} (persisted-c4)]
    (spit (cas/artifact-path backend root) "{:not canonical")
    (is (= :noncanonical-stored-bytes
           (failure-reason #(receipt-store/read-receipt! backend root
                                                         (:dependencies inputs)))))))

(deftest readback-rejects-valid-receipt-under-wrong-address
  (let [backend (fresh-cas)
        inputs (c4-dependencies)
        artifact (c4-receipt inputs)
        wrong-address (hash-ref "9")
        path (cas/artifact-path backend wrong-address)]
    (.mkdirs (.getParentFile path))
    (spit path (cas/canonical-edn artifact))
    (is (= :receipt-address-mismatch
           (failure-reason #(receipt-store/read-receipt! backend wrong-address
                                                         (:dependencies inputs)))))))

(deftest persistence-rejects-unsupported-receipt-schema
  (let [backend (fresh-cas)
        artifact (assoc (c4-receipt (c4-dependencies))
                        :artifact/schema "unknown-receipt.v1")]
    (is (= :unsupported-receipt-schema
           (failure-reason #(receipt-store/persist-receipt! backend artifact))))))

(deftest detached-readback-rejects-missing-dependency
  (let [{:keys [backend root]} (persisted-c4)]
    (is (= :missing-dependency
           (failure-reason #(receipt-store/read-receipt! backend root {}))))))

(deftest detached-readback-rejects-dependency-substitution
  (let [{:keys [backend inputs root]} (persisted-c4)
        substituted (assoc (:configuration inputs)
                           :verifier-registry/root (hash-ref "8"))
        dependencies (assoc (:dependencies inputs)
                            (:configuration-root inputs) substituted)]
    (is (= :dependency-root-mismatch
           (failure-reason #(receipt-store/read-receipt! backend root dependencies))))))
