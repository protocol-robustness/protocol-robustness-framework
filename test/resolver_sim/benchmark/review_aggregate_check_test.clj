(ns resolver-sim.benchmark.review-aggregate-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-aggregate-check :as rac]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.hash.canonical :as hc]))

(def keyed-members
  [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}])

(defn make-keyed-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members keyed-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(def sample-members
  [{:researcher/id "researcher-a" :role :model-steward}
   {:researcher/id "researcher-b" :role :independent-reproducer}
   {:researcher/id "researcher-c" :role :adversarial-reviewer}])

(defn make-legacy-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members sample-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

;; ── check-aggregate-member-bit-width tests ──────────────────────────────────

(deftest aggregate-member-bit-width-holds-for-keyed-round
  (let [round (make-keyed-round)
        result (rac/check-aggregate-member-bit-width round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-bit-width-holds-for-keyed-round-with-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        result (rac/check-aggregate-member-bit-width round indices)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-bit-width-detects-mismatch
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        tampered (assoc indices :review-member/count 7)
        result (rac/check-aggregate-member-bit-width round tampered)]
    (is (not (:holds? result))
        "tampering the artifact count changes the derived bit-width (round 2 vs artifact 3), so the aggregate check detects the mismatch")
    (is (= :resolver-sim.benchmark.review-aggregate-check/member-bit-width-mismatch
           (:kind (first (:violations result))))
        "violation kind must identify the bit-width mismatch")))

(deftest aggregate-member-bit-width-holds-for-legacy-round
  (let [round (make-legacy-round)
        result (rac/check-aggregate-member-bit-width round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

;; ── check-aggregate-member-key-density tests ────────────────────────────────

(deftest aggregate-member-key-density-holds-for-keyed-round
  (let [round (make-keyed-round)
        result (rac/check-aggregate-member-key-density round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-holds-with-canonical-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        result (rac/check-aggregate-member-key-density round indices)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-holds-for-legacy-round
  (let [round (make-legacy-round)
        result (rac/check-aggregate-member-key-density round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-detects-count-mismatch-with-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        bad-indices (assoc indices :review-member/count 99)
        result (rac/check-aggregate-member-key-density round bad-indices)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/member-count-mismatch (:kind %)) (:violations result)))))

;; ── check-aggregate-three-member-standard tests ─────────────────────────────

(deftest three-member-standard-holds-for-3-distinct-members
  (let [round (make-keyed-round)
        result (rac/check-aggregate-three-member-standard round)]
    (is (:holds? result))
    (is (empty? (:violations result))))
  (let [round (make-legacy-round)
        result (rac/check-aggregate-three-member-standard round)]
    (is (:holds? result))))

(deftest three-member-standard-rejects-two-members
  (let [round {:review-round/members (take 2 sample-members)
               :review-round/id :review-round/two}
        result (rac/check-aggregate-three-member-standard round)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/not-three-members (:kind %)) (:violations result)))))

(deftest three-member-standard-rejects-non-distinct-identities
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members
                [{:researcher/id "researcher-a" :role :model-steward}
                 {:researcher/id "researcher-a" :role :independent-reproducer}
                 {:researcher/id "researcher-c" :role :adversarial-reviewer}]
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})
        result (rac/check-aggregate-three-member-standard round)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/non-distinct-member-identities (:kind %)) (:violations result)))
    (testing "identity separation is reported, never real-world independence"
      (is (not (contains? (:violations result) :real-world-independence?))))))

;; ── check-aggregate-three-member-classifications tests ─────────────────────

(defn- auth-report
  "Evaluate an authority report over the given positions on the sample round.
   :target-outcome nil yields an authorisation without a committed target."
  [& {:keys [positions target-outcome] :as opts}]
  (let [proposed (if (contains? opts :target-outcome)
                   target-outcome
                   (str "sha256:" (apply str (take 64 (cycle "c3")))))
        auth {:authorisation/id :authorisation/test
              :authorisation/request-root (str "sha256:" (apply str (take 64 (cycle "a1"))))
              :authorisation/review-round {:review-round/id :review-round/test
                                           :review-round/hash (str "sha256:" (apply str (take 64 (cycle "b2"))))}
              :authorisation/target {:target/kind :benchmark-branch
                                     :target/proposed-content-root proposed}
              :authorisation/decision-references (vec positions)}]
    (tma/evaluate-three-member-authority
     :authorisation auth
     :review-round {:review-round/members sample-members}
     :signature-valid? (constantly true))))

(defn- v2-pos
  [member decision outcome & {:keys [dissent-reason]}]
  (let [reason (or dissent-reason (when (= :dissent decision) "reason"))
        request-root (str "sha256:" (apply str (take 64 (cycle "a1"))))
        round-hash (str "sha256:" (apply str (take 64 (cycle "b2"))))
        auth-id :authorisation/test
        hash (str "sha256:"
                  (hc/domain-hash :researcher-decision-v2
                                  (cond-> {:researcher/id member
                                           :authorisation/id auth-id
                                           :authorisation/request-root request-root
                                           :review-round/hash round-hash
                                           :outcome/root outcome
                                           :decision decision}
                                    (= :dissent decision) (assoc :dissent/reason reason))))]
    (cond-> {:schema-version "researcher-decision.v2"
             :researcher/id member
             :authorisation/id auth-id
             :authorisation/request-root request-root
             :review-round/hash round-hash
             :outcome/root outcome
             :decision decision
             :decision/hash hash
             :signature {:value (str "sig-" member decision) :signed-at "t0"}}
      (= :dissent decision) (assoc :dissent/reason reason))))

(def outcome-a (str "sha256:" (apply str (take 64 (cycle "c3")))))
(def outcome-b (str "sha256:" (apply str (take 64 (cycle "d4")))))

(deftest classifications-preserved-for-authorised-report
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :dissent outcome-a)])
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (= :authorised (:authority-status report)))
    (is (= :authoritative-target (:outcome-source report)))
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest classifications-reveal-authorised-with-unavailable-outcome
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)]
                :target-outcome nil)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (= :target-outcome-unavailable (:outcome-source report)))
    (is (= :not-authorised (:authority-status report)))
    (is (:holds? result)
        "not-authorised with unavailable outcome is a preserved, consistent classification")))

(deftest classifications-reveal-counted-support-mismatch
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :counted-support 0)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/counted-support-mismatch (:kind %)) (:violations result)))))

(deftest classifications-reveal-position-category-overlap
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        dupe (first (:valid-supporting-positions report))
        report' (update report :valid-qualifying-positions conj dupe)
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (not (:holds? result)))
    (is (some #(= ::rac/position-category-overlap (:kind %)) (:violations result)))))

(deftest classifications-reveal-equivocator-counted-as-supporter
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        sup (first (:valid-supporting-positions report))
        ;; a position that BOTH supports AND is an equivocator's incompatible
        ;; position is double-counted — the aggregate check must reveal it.
        report' (update report :equivocating-members conj
                        {:member/id "researcher-a"
                         :incompatible-positions [sup]})
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (not (:holds? result)))
    (is (some #(= ::rac/equivocator-counted-as-supporter (:kind %)) (:violations result)))))

(deftest classifications-reveal-member-count-mismatch
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :constituted-member-count 2)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/member-count-mismatch (:kind %)) (:violations result)))))

(deftest classifications-hold-for-legitimate-equivocation-exclusion
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-a" :dissent outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (= :authorised (:authority-status report))
        "default :invalid-seat policy excludes the equivocator and two valid
         supporters remain")
    (is (:holds? result)
        "a legitimate equivocation exclusion is preserved, not a violation")))

(deftest run-review-aggregate-checks-composes-all
  (let [round (make-keyed-round)
        result (rac/run-review-aggregate-checks round)]
    (is (:holds? result))
    (is (= #{:member-bit-width :member-key-density :three-member-standard}
           (set (keys (:checks result))))
        "without a report, the three-member-classifications check is not composed")
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          with-report (rac/run-review-aggregate-checks round nil report)]
      (is (:holds? with-report))
      (is (contains? (:checks with-report) :three-member-classifications))
      (is (:holds? (get-in with-report [:checks :three-member-classifications]))))))

(deftest run-review-aggregate-checks-reveals-classification-violation
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        report' (assoc report :counted-support 0)
        result (rac/run-review-aggregate-checks round nil report')]
    (is (not (:holds? result)))
    (is (some #(= ::rac/counted-support-mismatch (:kind %))
              (get-in result [:checks :three-member-classifications :violations])))))

(deftest classifications-reveal-identity-separation-mismatch
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :identity-separate? false)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/identity-separation-mismatch (:kind %)) (:violations result)))))

(deftest classifications-reveal-member-category-overlap
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        ;; a member classified as BOTH absent and supporting is double-classified
        report' (update report :absent-members conj "researcher-a")
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (not (:holds? result)))
    (is (some #(= ::rac/member-category-overlap (:kind %)) (:violations result)))))

(deftest classifications-reveal-member-unaccounted
  (let [round (make-legacy-round)
        ;; only two positions: researcher-c has no position and is absent
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)])
        ;; erase researcher-c's absent classification too -> silently dropped
        report' (update report :absent-members
                        (fn [a] (remove #(= "researcher-c" %) a)))
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (some #(= ::rac/member-unaccounted (:kind %)) (:violations result)))
    (is (contains? (set (:members (first (filter #(= ::rac/member-unaccounted (:kind %))
                                                 (:violations result)))))
                   "researcher-c"))))

(deftest member-classifications-hold-for-real-report
  (testing "a real authority report accounts for every constituted member exactly once"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :dissent outcome-a)
                              (v2-pos "researcher-c" :approve outcome-a)])
          result (rac/check-aggregate-three-member-classifications round report)]
      (is (:holds? result)))))

(deftest member-classifications-hold-for-equivocation
  (testing "an equivocating member is a single accounted member classification"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-a" :dissent outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :approve outcome-a)])
          result (rac/check-aggregate-three-member-classifications round report)]
      (is (= :authorised (:authority-status report)))
      (is (:holds? result))
      (is (empty? (:violations result))))))
