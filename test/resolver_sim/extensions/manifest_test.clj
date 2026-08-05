(ns resolver-sim.extensions.manifest-test
  "Phase 1: package/capability manifest validation, identity projections, and
   sealed classification."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.core :as core]
            [resolver-sim.extensions.fixtures :as fx]
            [resolver-sim.extensions.manifest :as em]))

;; ── capability validation ─────────────────────────────────────────────────

(deftest valid-capability-passes
  (is (:valid? (em/validate-capability fx/rate-with-cap-cap)))
  (is (= 0 (count (:violations (em/validate-capability fx/rate-with-cap-cap))))))

(deftest capability-rejects-unqualified-kind-and-id
  (is (some #(= :violation/unqualified-capability-kind (:violation/id %))
            (:violations (em/validate-capability (assoc fx/rate-with-cap-cap :capability/kind :award-amount)))))
  (is (some #(= :violation/unqualified-capability-id (:violation/id %))
            (:violations (em/validate-capability (assoc fx/rate-with-cap-cap :capability/id :rate-with-cap))))))

(deftest capability-rejects-missing-fields
  (is (some #(= :violation/missing-entrypoint (:violation/id %))
            (:violations (em/validate-capability (dissoc fx/rate-with-cap-cap :entrypoint)))))
  (is (some #(= :violation/invalid-capability-version (:violation/id %))
            (:violations (em/validate-capability (assoc fx/rate-with-cap-cap :capability/version 0)))))
  (is (some #(= :violation/invalid-contract-version (:violation/id %))
            (:violations (em/validate-capability (assoc fx/rate-with-cap-cap :capability/contract-version 0)))))
  (is (some #(= :violation/missing-capability-kind (:violation/id %))
            (:violations (em/validate-capability (dissoc fx/rate-with-cap-cap :capability/kind))))))

(deftest capability-rejects-non-map-dependency
  (is (some #(= :violation/invalid-declared-dependency (:violation/id %))
            (:violations (em/validate-capability
                          (assoc fx/rate-with-cap-cap :declared-dependencies [42]))))))

;; ── descriptor root ───────────────────────────────────────────────────────

(deftest descriptor-root-deterministic
  (is (= (em/capability-descriptor-root fx/rate-with-cap-cap)
         (em/capability-descriptor-root fx/rate-with-cap-cap))))

(deftest descriptor-root-changes-with-projection-fields
  (doseq [[desc mutate] [["version" #(assoc % :capability/version 99)]
                         ["contract-version" #(assoc % :capability/contract-version 99)]
                         ["entrypoint" #(assoc % :entrypoint 'fixture.other/calculate)]
                         ["input-schema" #(assoc % :input-schema :prf/other.v1)]
                         ["composition" #(assoc % :composition-contract {:mode :dag})]
                         ["dependency" #(update % :declared-dependencies
                                                conj {:capability/kind :economics/allocation
                                                      :capability/id :fixture/extra})]]]
    (testing (str "changing " desc " changes the descriptor root")
      (is (not= (em/capability-descriptor-root fx/rate-with-cap-cap)
                (em/capability-descriptor-root (mutate fx/rate-with-cap-cap)))))))

(deftest descriptor-root-normalises-symbol-entrypoint
  (testing "a symbol and its string form hash identically"
    (is (= (em/capability-descriptor-root (assoc fx/rate-with-cap-cap :entrypoint 'x/y))
           (em/capability-descriptor-root (assoc fx/rate-with-cap-cap :entrypoint "x/y"))))))

(deftest descriptor-root-ignores-non-projection-fields
  (testing "fields outside the projection do not affect the root"
    (is (= (em/capability-descriptor-root fx/rate-with-cap-cap)
           (em/capability-descriptor-root (assoc fx/rate-with-cap-cap :note "unrelated"))))))

;; ── package validation ────────────────────────────────────────────────────

(deftest valid-package-passes
  (is (:valid? (em/validate-package fx/scaled-share-pack)))
  (is (:valid? (em/validate-package core/core-economics-package)))
  (is (= 0 (count (:violations (em/validate-package fx/scaled-share-pack))))))

(deftest package-rejects-invalid-shape
  (is (some #(= :violation/missing-package-id (:violation/id %))
            (:violations (em/validate-package (dissoc fx/scaled-share-pack :extension/id)))))
  (is (some #(= :violation/unqualified-package-id (:violation/id %))
            (:violations (em/validate-package (assoc fx/scaled-share-pack :extension/id :scaled-share)))))
  (is (some #(= :violation/invalid-manifest-version (:violation/id %))
            (:violations (em/validate-package (assoc fx/scaled-share-pack :extension/manifest-version 2)))))
  (is (some #(= :violation/invalid-package-version (:violation/id %))
            (:violations (em/validate-package (assoc fx/scaled-share-pack :extension/version nil))))))

(deftest package-rejects-duplicate-capability-keys
  (let [pkg (assoc fx/scaled-share-pack
                   :extension/capabilities [fx/scaled-share-cap fx/scaled-share-cap])]
    (is (some #(= :violation/duplicate-capability-key (:violation/id %))
              (:violations (em/validate-package pkg))))))

(deftest package-propagates-capability-violations
  (let [pkg (assoc fx/scaled-share-pack
                   :extension/capabilities [(dissoc fx/scaled-share-cap :entrypoint)])]
    (is (some #(= :violation/missing-entrypoint (:violation/id %))
              (:violations (em/validate-package pkg))))))

(deftest conflicting-legacy-capability-fields-rejected
  (testing "both a legacy alias and the canonical field present with different values
            is rejected; a legacy alias alone is normalised"
    (let [cap (assoc fx/rate-with-cap-cap
                     :input-schema-ref :prf/OTHER.v1)]
      (is (some #(= :violation/conflicting-capability-fields (:violation/id %))
                (:violations (em/validate-capability cap)))))
    (let [cap (-> (dissoc fx/rate-with-cap-cap :input-schema)
                  (assoc :input-schema-ref :prf/award-amount-context.v1))]
      (is (:valid? (em/validate-capability cap)))
      (is (= :prf/award-amount-context.v1
             (:input-schema (:normalized (em/normalize-capability-descriptor cap))))))))

;; ── package root ──────────────────────────────────────────────────────────

(deftest package-root-excludes-hash-fields
  (let [with-hash-fields (assoc fx/scaled-share-pack
                                :extension/manifest-root "sha256:manifest"
                                :extension/package-root "sha256:package")]
    (is (= (em/package-root fx/scaled-share-pack)
           (em/package-root with-hash-fields)))))

(deftest package-root-changes-with-capabilities-and-sealing
  (is (not= (em/package-root fx/scaled-share-pack)
            (em/package-root (update-in fx/scaled-share-pack
                                        [:extension/capabilities 0 :capability/version] inc))))
  (is (not= (em/package-root fx/scaled-share-pack)
            (em/package-root (assoc-in fx/scaled-share-pack [:extension/artifact :artifact-root] "sha256:other")))))

;; ── sealed classification ─────────────────────────────────────────────────

(deftest sealed-classification-transitions
  (is (= :artifact-replayable (em/sealed-classification fx/scaled-share-pack)))
  (is (= :unsealed (em/sealed-classification fx/unsealed-pack)))
  (is (= :source-pinned (em/sealed-classification
                         (dissoc fx/scaled-share-pack :extension/artifact
                                 :extension/dependencies :extension/runtime))))
  (is (em/sealed? fx/scaled-share-pack))
  (is (not (em/sealed? fx/unsealed-pack))))
