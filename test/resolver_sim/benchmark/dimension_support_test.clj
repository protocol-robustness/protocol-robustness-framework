(ns resolver-sim.benchmark.dimension-support-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.dimension-support :as ds]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]))

(def base-manifest
  {:benchmark/content-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :benchmark/model-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
   :benchmark/evaluation-policy-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"})

(def ^:private manifest-with-roots
  (om/build-manifest
   (assoc base-manifest
          :execution/status :completed
          :execution/parameter-domain-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          :execution/sampling-policy-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          :execution/generated-case-set-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
          :execution/command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          :outcomes/operational-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"
          :outcomes/incentive-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"
          :outcomes/incentive-compatibility-root "sha256:4444444444444444444444444444444444444444444444444444444444444444")))

(deftest dimension-support-valid-execution-source
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})]
    (is (= ds/schema-version (:schema-version support)))
    (is (some? (:dimension-support/hash support)))
    (is (= 1 (count (:dimensions support))))
    (is (:valid? (ds/verify-dimension-support support)))))

(deftest dimension-support-rejects-unknown-dimension
  (is (thrown? clojure.lang.ExceptionInfo
               (ds/build-dimension-support
                {:dimensions [{:dimension :not-a-real-dimension
                               :source {:kind :execution
                                        :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                               :evidence-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}))
      "unknown dimension → rejected"))

(deftest dimension-support-rejects-invalid-evidence-root
  (is (thrown? clojure.lang.ExceptionInfo
               (ds/build-dimension-support
                {:dimensions [{:dimension :reproduction
                               :source {:kind :execution
                                        :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                               :evidence-root "not-a-hash"}]}))
      "non-sha256 evidence root → rejected"))

(deftest dimension-support-valid-derivation-source
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :model-authority
                                :source {:kind :derivation
                                         :basis-roots ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                                       "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}
                                :evidence-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]})]
    (is (:valid? (ds/verify-dimension-support support)))))

(deftest dimension-support-rejects-invalid-source-kind
  (is (thrown? clojure.lang.ExceptionInfo
               (ds/build-dimension-support
                {:dimensions [{:dimension :evidence
                               :source {:kind :manual}
                               :evidence-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}))
      "unknown source kind → rejected"))

(deftest dimension-support-roundtrips
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}
                               {:dimension :model-state
                                :source {:kind :derivation
                                         :basis-roots ["sha256:b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"]}
                                :evidence-root "sha256:e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1"}]})]
    (is (:valid? (ds/verify-dimension-support support)))))

(deftest dimension-support-rejects-tampered-hash
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :publication
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
        bad (assoc support :dimension-support/hash "sha256:faked")]
    (is (not (:valid? (ds/verify-dimension-support bad))))))

(deftest dimension-support-rejects-empty-dimensions
  (is (thrown? clojure.lang.ExceptionInfo
               (ds/build-dimension-support
                {:dimensions []}))
      "empty dimensions vector → rejected"))

(deftest dimension-support-reconciliation-execution-source
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
        result (ds/reconcile-against-manifest support manifest-with-roots)]
    (is (:reconciled? result))
    (is (every? :reconciled? (:entry-results result)))))

(deftest dimension-support-reconciliation-evidence-not-in-manifest
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                :evidence-root "sha256:9999999999999999999999999999999999999999999999999999999999999999"}]})
        result (ds/reconcile-against-manifest support manifest-with-roots)]
    (is (not (:reconciled? result)))
    (is (some (comp #(= :support/evidence-root-not-in-manifest %) :code)
              (mapcat :violations (:entry-results result))))))

(deftest dimension-support-reconciliation-command-root-mismatch
  (let [support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
        result (ds/reconcile-against-manifest support manifest-with-roots)]
    (is (not (:reconciled? result)))
    (is (some (comp #(= :support/command-root-mismatch %) :code)
              (mapcat :violations (:entry-results result))))))

(deftest dimension-support-hash-stable
  (let [dims [{:dimension :incentives-participants
               :source {:kind :execution
                        :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
               :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]
        s1 (ds/build-dimension-support {:dimensions dims})
        s2 (ds/build-dimension-support {:dimensions dims})]
    (is (= (:dimension-support/hash s1) (:dimension-support/hash s2))
        "same dimensions → same hash")))

(deftest dimension-support-all-18-consensus-dimensions-valid
  (let [all-dim-entries (mapv (fn [dim]
                                {:dimension dim
                                 :source {:kind :derivation
                                          :basis-roots ["sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}
                                 :evidence-root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"})
                              (sort (vec tmc/consensus-dimensions)))]
    (testing "all 18 consensus dimensions accepted"
      (doseq [entry all-dim-entries]
        (is (some? (ds/build-dimension-support {:dimensions [entry]})))))))

;; ── P2: outcome-manifest/root binding ───────────────────────────────────────

(deftest dimension-support-binds-manifest-root
  (testing "build-dimension-support binds the manifest root into execution entries
            and reconciliation verifies it against the manifest identity"
    (let [manifest-root (:benchmark-outcome/hash manifest-with-roots)
          _ (is (some? manifest-root) "manifest carries a committed hash")
          support (ds/build-dimension-support
                   {:outcome-manifest/root manifest-root
                    :dimensions [{:dimension :incentives-participants
                                  :source {:kind :execution
                                           :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                  :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
          entry (first (:dimensions support))]
      (is (= manifest-root (get-in entry [:source :outcome-manifest/root]))
          "manifest root is auto-bound into the execution source")
      (is (:reconciled? (ds/reconcile-against-manifest support manifest-with-roots))))))

(deftest dimension-support-manifest-root-mismatch-fails-reconciliation
  (let [actual-root (:benchmark-outcome/hash manifest-with-roots)
        _ (is (some? actual-root))
        support (ds/build-dimension-support
                 {:dimensions [{:dimension :incentives-participants
                                :source {:kind :execution
                                         :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                         :outcome-manifest/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
        result (ds/reconcile-against-manifest support manifest-with-roots)]
    (is (not (:reconciled? result)))
    (is (some (comp #(= :support/manifest-root-mismatch %) :code)
              (mapcat :violations (:entry-results result))))))

(deftest dimension-support-invalid-manifest-root-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (ds/build-dimension-support
                {:dimensions [{:dimension :reproduction
                               :source {:kind :execution
                                        :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                        :outcome-manifest/root "not-a-hash"}
                               :evidence-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}))
      "invalid manifest root → rejected"))

(deftest dimension-support-transplant-across-manifests-detected
  (testing "a dimension-support bound to one manifest fails reconciliation
            when evaluated against a different manifest (transplant attack)"
    (let [other-manifest (om/build-manifest
                          (assoc base-manifest
                                 :execution/status :completed
                                 :execution/parameter-domain-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                 :execution/sampling-policy-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                 :execution/generated-case-set-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                                 :execution/command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                 :outcomes/operational-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                                 :outcomes/incentive-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"))
          support (ds/build-dimension-support
                   {:outcome-manifest/root (:benchmark-outcome/hash manifest-with-roots)
                    :dimensions [{:dimension :incentives-participants
                                  :source {:kind :execution
                                           :command-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                                  :evidence-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}]})
          result (ds/reconcile-against-manifest support other-manifest)]
      (is (not (:reconciled? result))
          "manifest-root mismatch is detected when transplanting across manifests")
      (is (some (comp #(= :support/manifest-root-mismatch %) :code)
                (mapcat :violations (:entry-results result)))))))
