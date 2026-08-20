(ns resolver-sim.benchmark.review-aggregate-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-aggregate-check :as rac]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]))

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

(deftest aggregate-member-bit-width-flags-empty-artifact
  (testing "a keyed round paired with an empty (zero-count) artifact is a bit-width
            mismatch, not a silent skip"
    (let [round (make-keyed-round)
          indices (ci/build-canonical-indices round)
          empty-artifact (assoc indices :review-member/count 0)
          result (rac/check-aggregate-member-bit-width round empty-artifact)]
      (is (nil? (ci/member-bit-width empty-artifact))
          "an empty artifact has no member bit-width")
      (is (not (:holds? result)))
      (is (some #(= ::rac/member-bit-width-mismatch (:kind %)) (:violations result))))))

(deftest aggregate-member-bit-width-requires-count-derived-width
  (testing "sparse keys must not widen the round representation beyond its member count"
    (let [round (assoc-in (make-keyed-round)
                          [:review-round/members 2 :review-member/key] 7)
          result (rac/check-aggregate-member-bit-width round)]
      (is (= 3 (rr/member-bit-width round)))
      (is (not (:holds? result)))
      (is (some #(and (= ::rac/member-bit-width-mismatch (:kind %))
                      (= 2 (:expected-bit-width %)))
                (:violations result))))))

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

(deftest aggregate-member-key-density-holds-for-reordered-dense-keyed-round
  (testing "density is a SET property: a keyed round whose members are stored in
            non-key-sorted order is still dense 0..n-1 and must not be flagged"
    (let [round (rr/build-review-round
                 {:benchmark/content-root "sha256:abc"
                  :review-round/purpose :model-admission
                  :review-round/members
                  [{:review-member/key 2, :researcher/id "researcher-c" :role :adversarial-reviewer}
                   {:review-member/key 0, :researcher/id "researcher-a" :role :model-steward}
                   {:review-member/key 1, :researcher/id "researcher-b" :role :independent-reproducer}]
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})
          indices (ci/build-canonical-indices round)
          result (rac/check-aggregate-member-key-density round indices)]
      (is (rr/dense-member-key-set? (:review-round/members round))
          "the key SET is dense even though the vector is not key-sorted")
      (is (:holds? result))
      (is (empty? (:violations result)))
      (is (:holds? (rac/run-review-aggregate-checks round indices))
          "the aggregate runner must not reject a valid reordered keyed round"))))

(deftest aggregate-member-key-density-still-detects-truly-non-dense-keys
  (testing "a key set missing 0..n-1 (a gap) is still rejected"
    (let [round (rr/build-review-round
                 {:benchmark/content-root "sha256:abc"
                  :review-round/purpose :model-admission
                  :review-round/members
                  [{:review-member/key 0, :researcher/id "researcher-a" :role :model-steward}
                   {:review-member/key 1, :researcher/id "researcher-b" :role :independent-reproducer}
                   {:review-member/key 3, :researcher/id "researcher-c" :role :adversarial-reviewer}]
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})
          result (rac/check-aggregate-member-key-density round)]
      (is (not (:holds? result)))
      (is (some #(= ::rac/non-dense-member-keys (:kind %)) (:violations result))))))

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

(deftest three-member-standard-rejects-unknown-member-role
  (testing "a member role outside rr/member-roles is an ::unknown-member-role violation"
    (let [round {:review-round/members
                 [{:researcher/id "researcher-a" :role :model-steward}
                  {:researcher/id "researcher-b" :role :independent-reproducer}
                  {:researcher/id "researcher-c" :role :ghost-reviewer}]}
          result (rac/check-aggregate-three-member-standard round)]
      (is (not (:holds? result)))
      (let [violation (first (filter #(= ::rac/unknown-member-role (:kind %))
                                     (:violations result)))]
        (is (some? violation) "the unknown role must be flagged")
        (is (= [:model-steward :independent-reproducer :ghost-reviewer] (:roles violation))
            "the round's full roles vector is reported (the check reports all roles,
             not only the offending one)")))))

(deftest three-member-standard-rejects-duplicate-member-keys
  (testing "a keyed round whose members share a :review-member/key is a
            ::duplicate-member-keys violation (distinct from identity separation)"
    (let [round {:review-round/members
                 [{:review-member/key 0 :researcher/id "researcher-a" :role :model-steward}
                  {:review-member/key 0 :researcher/id "researcher-b" :role :independent-reproducer}
                  {:review-member/key 1 :researcher/id "researcher-c" :role :adversarial-reviewer}]}
          result (rac/check-aggregate-three-member-standard round)]
      (is (not (:holds? result)))
      (is (some #(= ::rac/duplicate-member-keys (:kind %)) (:violations result)))
      (is (not-any? #(= ::rac/non-distinct-member-identities (:kind %)) (:violations result))
          "duplicate keys is a key-space collision, not a researcher-identity collision"))))

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
      (is (:holds? (get-in with-report [:checks :three-member-classifications])))
      (testing "the canonical fixed-point check is threaded into the aggregate"
        (is (contains? (:checks with-report) :classifications-fixed-point))
        (is (:holds? (get-in with-report [:checks :classifications-fixed-point]))))
      (testing "the semantic check is re-run on the decoded fixed-point report"
        (is (contains? (:checks with-report) :three-member-classifications-on-fixed-point))
        (is (:holds? (get-in with-report [:checks :three-member-classifications-on-fixed-point])))))))

;; ── three-classifications preserved through the canonical fixed-point ───────

(deftest classifications-fixed-point-holds-for-authorised-report
  (testing "an authorised report survives the canonical round-trip with all
            three classification dimensions intact"
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          result (rac/check-classifications-fixed-point report)]
      (is (:holds? result))
      (is (empty? (:violations result)))))

  (testing "classifications-preserved still holds on the decoded report"
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          ba (hc/canonical-bytes report)
          decoded (:value (fv/decode-one ba 0))
          round (make-legacy-round)
          check (rac/check-aggregate-three-member-classifications round decoded)]
      (is (:holds? check))
      (is (= :authorised (:authority-status decoded))))))

(deftest classifications-fixed-point-preserves-not-authorised
  (let [report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :dissent outcome-a)
                            (v2-pos "researcher-c" :dissent outcome-a)])
        result (rac/check-classifications-fixed-point report)]
    (is (= :not-authorised (:authority-status report)))
    (is (:holds? result))))

(deftest classifications-fixed-point-reveals-tampered-category
  (testing "removing a supporting position from the stored report breaks the
            canonical fixed-point identity — the classification no longer
            recomputes to the same bytes"
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          tampered (assoc-in report [:valid-supporting-positions 1]
                             (v2-pos "researcher-b" :approve outcome-b))
          result (rac/check-classifications-fixed-point report)]
      ;; the honest report itself is a fixed point of its own bytes
      (is (:holds? result))
      (testing "a changed category content changes the canonical bytes"
        (is (not= (hc/canonical-bytes report) (hc/canonical-bytes tampered)))))))

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

(deftest classifications-hold-for-legitimate-duplicate-seat
  (testing "a legitimate identical duplicate submission is preserved (never
            double-counted) and must NOT be flagged as a category overlap,
            member overlap, or excluded-position-counted"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          result (rac/check-aggregate-three-member-classifications round report)
          aggregate (rac/run-review-aggregate-checks round nil report)]
      (is (= :authorised (:authority-status report))
          "two valid supporters (one duplicate copy) reach authority")
      (is (= 2 (:counted-support report))
          "the duplicate counts once, never as an extra vote")
      (is (= 1 (count (:duplicate-seat-positions report)))
          "the duplicate copy is preserved in the report")
      (is (:holds? result))
      (is (empty? (:violations result))
          "the aggregate classification check must not reject a preserved duplicate")
      (is (:holds? aggregate)
          "the aggregate runner must hold on a legitimate duplicate-seat report")
      (is (not-any? #(contains? #{::rac/position-category-overlap
                                  ::rac/member-category-overlap
                                  ::rac/excluded-position-counted}
                                (:kind %))
                    (get-in aggregate [:checks :three-member-classifications :violations]))))))

(deftest classifications-reveal-invalid-authority-status
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :authority-status :semi-authorised)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/invalid-authority-status (:kind %)) (:violations result)))
    (is (= :semi-authorised
           (:status (first (filter #(= ::rac/invalid-authority-status (:kind %))
                                   (:violations result))))))))

(deftest classifications-reveal-invalid-outcome-source
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :outcome-source :elsewhere)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/invalid-outcome-source (:kind %)) (:violations result)))
    (is (some #(= ::rac/authorised-without-authoritative-outcome (:kind %)) (:violations result))
        "an authorised status with a malformed source also surfaces the general
         non-authoritative-outcome finding")))

(deftest classifications-reveal-authorised-below-threshold
  (let [round (make-legacy-round)
        ;; 2 approvers + 1 dissent -> counted-support 2; raise the threshold above
        ;; the support so only ::authorised-below-threshold fires, not the
        ;; counted-support/supporting-count mismatch.
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :dissent outcome-a)])
                      :required-threshold 3)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (= :authorised (:authority-status report)))
    (is (= 2 (:counted-support report)))
    (is (not (:holds? result)))
    (is (some #(and (= ::rac/authorised-below-threshold (:kind %))
                    (= 2 (:counted-support %)) (= 3 (:required %)))
              (:violations result)))
    (is (not-any? #(= ::rac/counted-support-mismatch (:kind %)) (:violations result)))))

(deftest classifications-reveal-authorised-not-identity-separate
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :identity-separate? false)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/authorised-not-identity-separate (:kind %)) (:violations result)))))

(deftest classifications-reveal-authorised-not-three-members
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :constituted-member-count 4)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(and (= ::rac/authorised-not-three-members (:kind %)) (= 4 (:count %)))
              (:violations result)))
    (is (some #(= ::rac/member-count-mismatch (:kind %)) (:violations result))
        "the report/round member-count mismatch is reported independently")))

(deftest classifications-reveal-authorised-policy-not-conforming
  (let [round (make-legacy-round)
        report (assoc (auth-report
                       :positions [(v2-pos "researcher-a" :approve outcome-a)
                                   (v2-pos "researcher-b" :approve outcome-a)
                                   (v2-pos "researcher-c" :approve outcome-a)])
                      :policy-conforming? false)
        result (rac/check-aggregate-three-member-classifications round report)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/authorised-policy-not-conforming (:kind %)) (:violations result)))))

(deftest classifications-reveal-equivocator-counted-as-dissenter
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :dissent outcome-a)])
        dissent-pos (first (:valid-dissenting-positions report))
        ;; the dissenter's own position is re-committed as an equivocator's
        ;; incompatible position -> double-counted in the dissenting category
        report' (update report :equivocating-members conj
                        {:member/id "researcher-c"
                         :incompatible-positions [dissent-pos]})
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (seq (:valid-dissenting-positions report)))
    (is (not (:holds? result)))
    (is (some #(= ::rac/equivocator-counted-as-dissenter (:kind %)) (:violations result)))))

(deftest classifications-reveal-excluded-position-counted
  (let [round (make-legacy-round)
        report (auth-report
                :positions [(v2-pos "researcher-a" :approve outcome-a)
                            (v2-pos "researcher-b" :approve outcome-a)
                            (v2-pos "researcher-c" :approve outcome-a)])
        support-pos (first (:valid-supporting-positions report))
        ;; an excluded (invalid) position is wrongly also counted as a supporter
        report' (update report :invalid-positions conj support-pos)
        result (rac/check-aggregate-three-member-classifications round report')]
    (is (not (:holds? result)))
    (is (some #(and (= ::rac/excluded-position-counted (:kind %)) (= :invalid (:category %)))
              (:violations result)))))

(deftest classifications-authorised-with-unavailable-outcome-surfaces-general-finding
  (testing "an authorised report whose outcome-source is :target-outcome-unavailable
            (contradicting rule 2) is surfaced by ::authorised-without-authoritative-outcome,
            the general superset finding.  The former ::authorised-with-unavailable-outcome
            kind always co-fired with it and carried strictly less information, so it was
            merged away rather than kept as a redundant branch."
    (let [round (make-legacy-round)
          report (assoc (auth-report
                         :positions [(v2-pos "researcher-a" :approve outcome-a)
                                     (v2-pos "researcher-b" :approve outcome-a)
                                     (v2-pos "researcher-c" :approve outcome-a)])
                        :outcome-source :target-outcome-unavailable)
          result (rac/check-aggregate-three-member-classifications round report)]
      (is (not (:holds? result)))
      (is (some #(= ::rac/authorised-without-authoritative-outcome (:kind %)) (:violations result))
          "the contradiction is surfaced by the single general finding")
      (is (not-any? #(= ::rac/authorised-with-unavailable-outcome (:kind %)) (:violations result))
          "the merged redundant finding is no longer emitted"))))

(deftest aggregate-runner-authorised-with-unavailable-outcome-fails-closed
  (testing "the merge keeps the aggregate fail-closed: an authorised report with an
            unavailable outcome still fails the aggregate"
    (let [round (make-legacy-round)
          report (assoc (auth-report
                         :positions [(v2-pos "researcher-a" :approve outcome-a)
                                     (v2-pos "researcher-b" :approve outcome-a)
                                     (v2-pos "researcher-c" :approve outcome-a)])
                        :outcome-source :target-outcome-unavailable)
          result (rac/run-review-aggregate-checks round nil report)]
      (is (not (:holds? result)))
      (is (some #(= ::rac/authorised-without-authoritative-outcome (:kind %))
                (get-in result [:checks :three-member-classifications :violations]))))))

;; ── Threading: on-fixed-point consumes the decoded artifact ─────────────────
;;
;; The semantic check on the fixed-point result must consume the ACTUAL decoded
;; report produced by the fixed-point stage, never an independent re-decode or
;; a re-check of the stored input.  These tests substitute the fixed-point
;; stage with a decoded representation whose classification dimensions are
;; invalid, while the STORED report is valid — so the aggregate can only fail
;; if the runner threaded the decoded artifact downstream.

(deftest fixed-point-threading-rejects-invalid-decoded-classifications
  (testing "a valid stored report whose decoded fixed-point classification is
            invalid fails :three-member-classifications-on-fixed-point"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          ;; stored report is intrinsically valid
          stored-check (rac/check-aggregate-three-member-classifications round report)
          ;; decoded representation that double-counts an equivocator as a
          ;; supporter — invalid classification dimensions
          support-hash (first (:valid-supporting-positions report))
          decoded-bad (update report :equivocating-members conj
                              {:member/id "researcher-a"
                               :incompatible-positions [support-hash]})
          result (with-redefs [rac/classifications-fixed-point
                               (constantly {:holds? true :violations []
                                            :report decoded-bad})]
                   (rac/run-review-aggregate-checks round nil report))]
      ;; the stored report itself passes — the failure must come from the
      ;; decoded artifact being threaded, not the stored one
      (is (:holds? stored-check))
      (is (:holds? (get-in result [:checks :three-member-classifications]))
          "stored report check must pass")
      (is (:holds? (get-in result [:checks :classifications-fixed-point]))
          "the substituted fixed-point stage itself reports valid")
      (is (not (:holds? (get-in result [:checks :three-member-classifications-on-fixed-point])))
          "the decoded report's invalid classification must fail the on-fixed-point check")
      (is (some #(= ::rac/equivocator-counted-as-supporter (:kind %))
                (:violations (get-in result [:checks :three-member-classifications-on-fixed-point])))
          "the violation must come from the decoded report's double-counted equivocator")
      (is (not (:holds? result))
          "the aggregate must fail when the fixed-point artifact is semantically invalid"))))

(deftest fixed-point-threading-passes-decoded-artifact-not-stored
  (testing "the on-fixed-point check reflects the DECODED report, not the stored
            one — proven by substituting a decoded report whose outcome-source
            contradicts its authority-status"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          ;; decoded report: still authorised but with a non-authoritative
          ;; outcome-source — a semantic contradiction only the fixed-point
          ;; artifact carries
          decoded-bad (assoc report :outcome-source :target-outcome-unavailable)
          result (with-redefs [rac/classifications-fixed-point
                               (constantly {:holds? true :violations []
                                            :report decoded-bad})]
                   (rac/run-review-aggregate-checks round nil report))]
      (is (:holds? (get-in result [:checks :three-member-classifications]))
          "stored report is consistent (authorised + authoritative-target)")
      (is (not (:holds? (get-in result [:checks :three-member-classifications-on-fixed-point])))
          "the decoded report contradicts (authorised + target-outcome-unavailable)")
      (is (some #(= ::rac/authorised-without-authoritative-outcome (:kind %))
                (:violations (get-in result [:checks :three-member-classifications-on-fixed-point])))))))

(deftest fixed-point-threading-exposes-decoded-report
  (testing "classifications-fixed-point returns the decoded report as first-class
            data for downstream composition"
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          fp (rac/classifications-fixed-point report)]
      (is (true? (:holds? fp)))
      (is (map? (:report fp)))
      (is (= :authorised (:authority-status (:report fp))))
      (is (= (count (:valid-supporting-positions report))
             (count (:valid-supporting-positions (:report fp))))))))

(deftest fixed-point-threading-decode-failure-is-loud
  (testing "when the fixed-point stage cannot decode, :three-member-classifications-on-fixed-point
            is present and FAILING with ::fixed-point-unavailable — never silently omitted"
    (let [round (make-legacy-round)
          report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          result (with-redefs [rac/classifications-fixed-point
                               (constantly {:holds? false
                                            :report nil
                                            :violations [{:kind ::rac/classifications-fixed-point-invalid
                                                          :issues []}]})]
                   (rac/run-review-aggregate-checks round nil report))
          on-fixed-point (get-in result [:checks :three-member-classifications-on-fixed-point])]
      (is (contains? (:checks result) :three-member-classifications-on-fixed-point)
          "the on-fixed-point check must be present even when decode fails")
      (is (not (:holds? on-fixed-point)))
      (is (some #(= ::rac/fixed-point-unavailable (:kind %)) (:violations on-fixed-point))
          "the failure must carry ::fixed-point-unavailable")
      (is (not (:holds? result))
          "the aggregate must fail when the fixed-point artifact is unavailable"))))

(deftest check-classifications-fixed-point-keeps-thin-wrapper-shape
  (testing "the wrapper still returns the standard {:holds? :violations} shape"
    (let [report (auth-report
                  :positions [(v2-pos "researcher-a" :approve outcome-a)
                              (v2-pos "researcher-b" :approve outcome-a)
                              (v2-pos "researcher-c" :dissent outcome-a)])
          result (rac/check-classifications-fixed-point report)]
      (is (true? (:holds? result)))
      (is (empty? (:violations result))))))

;; ── Degenerate error-fallback report handling ───────────────────────────────
;;
;; When evaluate-three-member-authority throws, the consumer records a
;; degenerate report carrying only :authority-status / :outcome-source /
;; :authority/error — it has none of the classification fields.  The aggregate
;; classification check must surface the authority-evaluation failure clearly,
;; NOT emit phantom field-level mismatches (counted-support, member-count,
;; identity-separation) that falsely imply a real inconsistency.

(deftest degenerate-error-report-surfaces-evaluation-failure-not-phantom-mismatches
  (let [round (make-legacy-round)
        fallback {:authority-status :not-authorised
                  :outcome-source :target-outcome-unavailable
                  :authority/error "boom"}
        result (rac/check-aggregate-three-member-classifications round fallback)]
    (is (not (:holds? result))
        "authority evaluation failed, so the aggregate does not hold")
    (is (some #(= ::rac/authority-evaluation-failed (:kind %)) (:violations result))
        "the failure is surfaced as an explicit authority-evaluation failure")
    (is (some #(= "boom" (:error %)) (:violations result))
        "the underlying error message is preserved")
    (is (not-any? #(contains? #{::rac/counted-support-mismatch
                                ::rac/member-count-mismatch
                                ::rac/identity-separation-mismatch
                                ::rac/member-unaccounted}
                              (:kind %))
                  (:violations result))
        "no phantom field-level mismatches are reported for a degenerate report")))

(deftest aggregate-runner-threads-error-report-not-phantom-mismatches
  (let [round (make-legacy-round)
        fallback {:authority-status :not-authorised
                  :outcome-source :target-outcome-unavailable
                  :authority/error "boom"}
        result (rac/run-review-aggregate-checks round nil fallback)
        cls (get-in result [:checks :three-member-classifications])]
    (is (not (:holds? result)))
    (is (contains? (:checks result) :three-member-classifications))
    (is (some #(= ::rac/authority-evaluation-failed (:kind %)) (:violations cls))
        "the error report fails the classification check with the clear signal")
    (is (not-any? #(contains? #{::rac/counted-support-mismatch
                                ::rac/member-count-mismatch
                                ::rac/identity-separation-mismatch
                                ::rac/member-unaccounted}
                              (:kind %))
                  (:violations cls))
        "no phantom field-level mismatches reach the aggregate result")))
