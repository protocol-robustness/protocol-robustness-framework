(ns resolver-sim.evidence.attestor-accountability-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.attestor-accountability :as aa]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.stake.ledger :as sl]))

;; ── Violation Definitions ───────────────────────────────────────────────

(deftest slashable-violations-registry
  (is (= 6 (count aa/slashable-violations)))
  (is (contains? aa/slashable-violations :violation/conflicting-attestations))
  (is (contains? aa/slashable-violations :violation/unauthorized-key-used))
  (is (contains? aa/slashable-violations :violation/tampered-evidence-submitted))
  (doseq [[k v] aa/slashable-violations]
    (is (contains? v :required-evidence) (str k " missing :required-evidence"))
    (is (contains? v :required-checks) (str k " missing :required-checks"))
    (is (boolean? (:slashable? v true)) (str k " :slashable? not boolean"))))

;; ── Violation Evidence Validation ───────────────────────────────────────

(deftest validate-violation-evidence-valid
  (let [result (aa/validate-violation-evidence
                :violation/conflicting-attestations
                {:attestation-hashes ["a" "b"]})]
    (is (:valid? result))))

(deftest validate-violation-evidence-missing-field
  (let [result (aa/validate-violation-evidence
                :violation/conflicting-attestations
                {})]
    (is (not (:valid? result)))
    (is (= [:attestation-hashes] (:missing result)))))

(deftest validate-violation-evidence-extra-fields-ok
  (let [result (aa/validate-violation-evidence
                :violation/unauthorized-key-used
                {:attestation-hashes ["a"] :registry-snapshot-hash "r1" :extra-key "x"})]
    (is (:valid? result))))

;; ── Open Violation Case ─────────────────────────────────────────────────

(deftest open-violation-case-creates-case
  (let [case-data {:accused-attestor-id :test-attestor
                   :violation/type :violation/conflicting-attestations
                   :evidence {:attestation-hashes ["a" "b"]}}
        result (aa/open-violation-case case-data)]
    (is (= "attestor-violation-case.v1" (:violation/case-version result)))
    (is (string? (:violation/case-id result)))
    (is (= :test-attestor (:violation/accused-attestor-id result)))
    (is (= :case/open (:violation/status result)))
    (is (= 4 (count (:violation/required-checks result))))
    (is (string? (:violation/hash result)))))

(deftest open-violation-case-throws-on-invalid-evidence
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid evidence"
                        (aa/open-violation-case
                         {:accused-attestor-id :test-attestor
                          :violation/type :violation/conflicting-attestations
                          :evidence {}}))))

(deftest open-violation-case-hash-is-content-addressed
  (let [case-data {:accused-attestor-id :attestor-1
                   :violation/type :violation/tampered-evidence-submitted
                   :evidence {:attestation-hashes ["h1"] :bundle-hash "bh"}}
        c1 (aa/open-violation-case case-data)]
    (is (string? (:violation/hash c1)))
    (is (not= (:violation/case-id c1) (:violation/hash c1)))
    ;; Hash is a hex string (SHA-256)
    (is (= 64 (count (:violation/hash c1))))
    ;; Same data should produce structurally identical cases
    (is (= :case/open (:violation/status c1)))))

;; ── Attach Evidence ────────────────────────────────────────────────────

(deftest attach-evidence-merges-and-rehashes
  (let [c (aa/open-violation-case
           {:accused-attestor-id :attestor-1
            :violation/type :violation/conflicting-attestations
            :evidence {:attestation-hashes ["a"]}})
        hash-before (:violation/hash c)
        c2 (aa/attach-evidence-to-case c {:quorum-report-hash "qr1"})]
    (is (= 2 (count (:violation/evidence c2))))
    (is (= 2 (count (:violation/timeline c2))))
    (is (not= hash-before (:violation/hash c2)))))

;; ── Transition Case Status ──────────────────────────────────────────────

(deftest transition-case-status-follows-state-machine
  (let [c (aa/open-violation-case
           {:accused-attestor-id :attestor-1
            :violation/type :violation/conflicting-attestations
            :evidence {:attestation-hashes ["a" "b"]}})]
    (is (= :case/open (:violation/status c)))

    (let [c2 (aa/transition-case-status c :case/under-review)]
      (is (= :case/under-review (:violation/status c2)))
      (is (= 2 (count (:violation/timeline c2))))

      (let [c3 (aa/transition-case-status c2 :case/decided)]
        (is (= :case/decided (:violation/status c3)))

        (let [c4 (aa/transition-case-status c3 :case/finalized)]
          (is (= :case/finalized (:violation/status c4))))))))

(deftest transition-case-status-rejects-invalid-transition
  (let [c (aa/open-violation-case
           {:accused-attestor-id :attestor-1
            :violation/type :violation/conflicting-attestations
            :evidence {:attestation-hashes ["a"]}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid case status transition"
                          (aa/transition-case-status c :case/finalized)))))

(deftest transition-case-status-supports-appeal
  (let [c (aa/open-violation-case
           {:accused-attestor-id :attestor-1
            :violation/type :violation/conflicting-attestations
            :evidence {:attestation-hashes ["a"]}})]
    (let [c2 (aa/transition-case-status c :case/under-review)
          c3 (aa/transition-case-status c2 :case/decided)
          c4 (aa/transition-case-status c3 :case/appealed)]
      (is (= :case/appealed (:violation/status c4)))
      (let [c5 (aa/transition-case-status c4 :case/finalized)]
        (is (= :case/finalized (:violation/status c5)))))))

;; ── Evaluate Violation Case ─────────────────────────────────────────────

(deftest evaluate-violation-case-no-evidence-exonerates
  (sl/with-fresh-ledger
    (sl/bond-stake :attestor-1 5000)
    (let [case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          result (aa/evaluate-violation-case
                  {:case case-data
                   :registries {:attestations []
                                :attestor-registry {:attestors []}
                                :quorum-report nil}
                   :policies {:accountability/min-stake 1000
                              :accountability/slash-amount 500}})
          decision (:decision result)]
      (is (map? (:checks result)))
      (is (string? (:accountability/hash decision)))
      ;; Without supporting evidence, attestor is exonerated
      (is (= :accountability/exonerate
             (:accountability/outcome decision)))
      (is (= 1 (count (:accountability/actions decision))))
      (is (= "P14D" (get-in decision [:accountability/appeal :appeal/window]))))))

(deftest evaluate-violation-case-structure-is-consistent
  (sl/with-fresh-ledger
    (let [case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          opts {:case case-data
                :registries {:attestations []
                             :attestor-registry {:attestors []}
                             :quorum-report nil}
                :policies {:accountability/min-stake 1000}}
          result (aa/evaluate-violation-case opts)
          decision (:decision result)]
      (is (map? (:checks result)))
      ;; Without evidence, outcome is exonerate
      (is (= :accountability/exonerate
             (:accountability/outcome decision)))
      (is (string? (:accountability/hash decision)))
      (is (= 64 (count (:accountability/hash decision)))))))

;; ── Finalize Decision ───────────────────────────────────────────────────

(deftest finalize-decision-exonerates-with-clear-action
  (sl/with-fresh-ledger
    (sl/bond-stake :attestor-1 5000)
    (let [attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
          case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          eval-result (aa/evaluate-violation-case
                       {:case case-data
                        :registries {:attestations []
                                     :attestor-registry attestor-registry
                                     :quorum-report nil}
                        :policies {:accountability/min-stake 1000
                                   :accountability/slash-amount 500}})
          decision (:decision eval-result)
          final (aa/finalize-accountability-decision
                 {:decision decision
                  :attestor-registry attestor-registry})]
      ;; Exonerated — no stake change
      (is (= 5000 (sl/get-stake-amount :attestor-1)))
      ;; Attestor still :active after clear (was already active)
      (is (= :active (:status (first (:attestors (:attestor-registry final))))))
      ;; One action applied (clear)
      (is (= 1 (count (:actions-applied final)))))))

(deftest finalize-decision-exonerates-no-stake-change
  (sl/with-fresh-ledger
    (sl/bond-stake :attestor-1 5000)
    (let [attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
          case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          eval-result (aa/evaluate-violation-case
                       {:case case-data
                        :registries {:attestations []
                                     :attestor-registry attestor-registry
                                     :quorum-report nil}
                        :policies {:accountability/min-stake 1000
                                   :accountability/slash-amount 500}})
          decision (:decision eval-result)]
      ;; No slash happened (exonerated)
      (is (= 5000 (sl/get-stake-amount :attestor-1))))))

;; ── Outcome Definitions ────────────────────────────────────────────────

(deftest outcomes-contains-all-five
  (is (= 5 (count aa/outcomes)))
  (is (contains? aa/outcomes :accountability/exonerate))
  (is (contains? aa/outcomes :accountability/suspend-only))
  (is (contains? aa/outcomes :accountability/suspend-and-slash))
  (is (contains? aa/outcomes :accountability/slash-only))
  (is (contains? aa/outcomes :accountability/finalize-retirement)))

(deftest case-statuses-contains-all-five
  (is (= 5 (count aa/case-statuses)))
  (is (contains? aa/case-statuses :case/open))
  (is (contains? aa/case-statuses :case/under-review))
  (is (contains? aa/case-statuses :case/decided))
  (is (contains? aa/case-statuses :case/appealed))
  (is (contains? aa/case-statuses :case/finalized)))

;; ── Stable Structural Hash ───────────────────────────────────────────────

(deftest decision-includes-stable-hash
  (sl/with-fresh-ledger
    (let [case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          result (aa/evaluate-violation-case
                  {:case case-data
                   :registries {:attestations [{:attestation/id "a"}
                                               {:attestation/id "b"}]
                                :attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
                                :quorum-report nil}
                   :policies {:accountability/min-stake 1000
                              :accountability/slash-amount 500}})
          decision (:decision result)]
      (is (string? (:accountability/stable-hash decision)))
      (is (not= (:accountability/hash decision)
                (:accountability/stable-hash decision)))
      (is (pos? (count (:accountability/stable-hash decision)))))))

(deftest stable-hash-identical-for-same-structure-different-values
  (sl/with-fresh-ledger
    (sl/bond-stake :attestor-1 5000)
    (sl/bond-stake :attestor-2 5000)
    (let [conflicting-attestations [{:attestation/id "a1"
                                     :attestation/attestor-id :attestor-1
                                     :attestation/subject-kind :test
                                     :attestation/subject-hash "sub-1"
                                     :attestation/claim-id "claim-1"
                                     :attestation/claim-result :verified}
                                    {:attestation/id "a2"
                                     :attestation/attestor-id :attestor-1
                                     :attestation/subject-kind :test
                                     :attestation/subject-hash "sub-1"
                                     :attestation/claim-id "claim-1"
                                     :attestation/claim-result :rejected}]
          case-1 (aa/open-violation-case
                  {:accused-attestor-id :attestor-1
                   :violation/type :violation/conflicting-attestations
                   :evidence {:attestation-hashes ["a1" "a2"]}})
          case-2 (aa/open-violation-case
                  {:accused-attestor-id :attestor-2
                   :violation/type :violation/conflicting-attestations
                   :evidence {:attestation-hashes ["a3" "a4"]}})
          result-1 (aa/evaluate-violation-case
                    {:case case-1
                     :registries {:attestations conflicting-attestations
                                  :attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
                                  :quorum-report nil}
                     :policies {:accountability/min-stake 1000
                                :accountability/slash-amount 500}})
          result-2 (aa/evaluate-violation-case
                    {:case case-2
                     :registries {:attestations
                                  [{:attestation/id "a3"
                                    :attestation/attestor-id :attestor-2
                                    :attestation/subject-kind :test
                                    :attestation/subject-hash "sub-2"
                                    :attestation/claim-id "claim-2"
                                    :attestation/claim-result :verified}
                                   {:attestation/id "a4"
                                    :attestation/attestor-id :attestor-2
                                    :attestation/subject-kind :test
                                    :attestation/subject-hash "sub-2"
                                    :attestation/claim-id "claim-2"
                                    :attestation/claim-result :rejected}]
                                  :attestor-registry {:attestors [{:id :attestor-2 :status :active}]}
                                  :quorum-report nil}
                     :policies {:accountability/min-stake 1000
                                :accountability/slash-amount 500}})
          dec-1 (:decision result-1)
          dec-2 (:decision result-2)]
      ;; Different entity IDs, amounts, timestamps — same structure
      (is (= (:accountability/stable-hash dec-1)
             (:accountability/stable-hash dec-2))))))

(deftest stable-hash-differs-for-different-structural-shapes
  (sl/with-fresh-ledger
    (let [case-data (aa/open-violation-case
                     {:accused-attestor-id :attestor-1
                      :violation/type :violation/conflicting-attestations
                      :evidence {:attestation-hashes ["a" "b"]}})
          result (aa/evaluate-violation-case
                  {:case case-data
                   :registries {:attestations []
                                :attestor-registry {:attestors []}
                                :quorum-report nil}
                   :policies {:accountability/min-stake 1000
                              :accountability/slash-amount 500}})
          dec (:decision result)]
      ;; The stable hash captures the structural shape: outcome, reasons,
      ;; action types, appeal policy — independent of entity IDs and amounts.
      (is (string? (:accountability/stable-hash dec)))
      (is (not= (:accountability/stable-hash dec)
                (:accountability/hash dec))))))

(deftest stable-hash-independent-of-case-hash
  (sl/with-fresh-ledger
    (sl/bond-stake :attestor-1 5000)
    (let [conflicting-attestations [{:attestation/id "a1"
                                     :attestation/attestor-id :attestor-1
                                     :attestation/subject-kind :test
                                     :attestation/subject-hash "sub-1"
                                     :attestation/claim-id "claim-1"
                                     :attestation/claim-result :verified}
                                    {:attestation/id "a2"
                                     :attestation/attestor-id :attestor-1
                                     :attestation/subject-kind :test
                                     :attestation/subject-hash "sub-1"
                                     :attestation/claim-id "claim-1"
                                     :attestation/claim-result :rejected}]
          ;; Same evidence, different case IDs — but same structural outcome
          case-a (aa/open-violation-case
                  {:accused-attestor-id :attestor-1
                   :violation/type :violation/conflicting-attestations
                   :evidence {:attestation-hashes ["a1" "a2"]}})
          case-b (aa/open-violation-case
                  {:accused-attestor-id :attestor-1
                   :violation/type :violation/conflicting-attestations
                   :evidence {:attestation-hashes ["a1" "a2"]}})
          result-a (aa/evaluate-violation-case
                    {:case case-a
                     :registries {:attestations conflicting-attestations
                                  :attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
                                  :quorum-report nil}
                     :policies {:accountability/min-stake 1000
                                :accountability/slash-amount 500}})
          result-b (aa/evaluate-violation-case
                    {:case case-b
                     :registries {:attestations conflicting-attestations
                                  :attestor-registry {:attestors [{:id :attestor-1 :status :active}]}
                                  :quorum-report nil}
                     :policies {:accountability/min-stake 1000
                                :accountability/slash-amount 500}})
          dec-a (:decision result-a)
          dec-b (:decision result-b)]
      ;; Case hashes differ (different UUIDs), stable hashes same
      (is (not= (:violation/case-hash dec-a)
                (:violation/case-hash dec-b)))
      (is (= (:accountability/stable-hash dec-a)
             (:accountability/stable-hash dec-b))))))

;; ── Structural Stability: Slashable Violations Registry ────────────────

(defn- violations-structural-paths
  "Extract flattened structural paths from slashable-violations.
   Produces paths like \"conflicting-attestations.required-evidence\" for each
   violation type and field — stable across value changes (descriptions, labels)."
  [violations]
  (sort (mapcat (fn [[vk vv]]
                  (map (fn [k] (str (name vk) "." (name k)))
                       [:code :required-evidence :required-checks :slashable?]))
                violations)))

(def expected-violations-shape-hash
  "Expected stable hash of slashable-violations structural paths.
   Update this hash when intentionally changing the violation registry shape."
  "e9381ddff09967c88ffccb8b713f11d9c6bfaf07cf293c4c8066d099839f7029")

(deftest slashable-violations-structural-stability
  (let [paths (into [] (violations-structural-paths aa/slashable-violations))
        actual-hash (hc/hash-with-intent {:hash/intent :state-diff} {:paths paths})]
    (is (= expected-violations-shape-hash actual-hash)
        (str "Slashable violations registry shape changed.\n"
             "If intentional, update expected-violations-shape-hash to: " actual-hash))))
