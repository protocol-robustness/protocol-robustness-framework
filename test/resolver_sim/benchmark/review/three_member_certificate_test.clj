(ns resolver-sim.benchmark.review.three-member-certificate-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]))

(defn- make-report [id outcome-hash & {:keys [mi plan domain sampling params cases eval-policy model-root content-root]
                                       :or {content-root "sha256:cr" model-root "sha256:m"
                                            mi "sha256:mi" plan "sha256:plan"
                                            domain "sha256:domain" sampling "sha256:samp"
                                            params "sha256:p" cases "sha256:c"
                                            eval-policy "sha256:ep"}}]
  {:researcher/id id
   :researcher-run-report/outcome-hash outcome-hash
   :benchmark/content-root content-root
   :benchmark/model-root model-root
   :benchmark/evaluation-policy-root eval-policy
   :execution/content-root content-root
   :execution/model-root model-root
   :execution/model-instance-root mi
   :execution/plan-root plan
   :execution/parameter-domain-root domain
   :execution/sampling-policy-root sampling
   :execution/realised-parameter-set-root params
   :execution/generated-case-set-root cases})

(def reports-exact
  [(make-report "a" "sha256:A")
   (make-report "b" "sha256:A")
   (make-report "c" "sha256:A")])

(def reports-two-same
  [(make-report "a" "sha256:A")
   (make-report "b" "sha256:A")
   (make-report "c" "sha256:B")])

(def reports-sampling
  [(make-report "a" "sha256:A" :cases "sha256:c1")
   (make-report "b" "sha256:A" :cases "sha256:c1")
   (make-report "c" "sha256:B" :cases "sha256:c2")])

(defn- dims [& {:keys [model-authority evidence claims publication]
                :or {model-authority :adequate evidence :sufficient
                     claims :supported publication :publish}}]
  {:model-state {:status :adequate}
   :model-authority {:status model-authority}
   :incentives-strategies {:status :adequate}
   :evidence {:status evidence}
   :claims {:status claims}
   :publication {:status publication}})

(defn- make-pos [id & {:keys [authority-status evidence-status publication-status]
                       :or {authority-status :adequate
                            evidence-status :sufficient
                            publication-status :publish}}]
  {:researcher/id id
   :position/hash (str "sha256:pos-" id)
   :position/outcome-hash "sha256:A"
   :position/dimensions (dims :model-authority authority-status
                              :evidence evidence-status
                              :publication publication-status)})

(defn- make-pos-absent [id]
  {:researcher/id id
   :position/hash (str "sha256:pos-" id)
   :position/outcome-hash "sha256:A"
   :position/dimensions {}})

(def ^:private default-round
  {:benchmark/content-root "sha256:cr"
   :review-round/id "review-round:test"
   :review-round/purpose :model-admission})

(defn- make-cert [reports positions]
  (tmc/build-certificate
   {:review-round default-round :reports reports :positions positions}))

;; ── Replication type ──────────────────────────────────────────────────────

(deftest replication-type-exact
  (is (= :exact-replication (tmc/replication-type reports-exact))))

(deftest replication-type-sampling
  (is (= :independent-sampling (tmc/replication-type reports-sampling))))

(deftest replication-type-incompatible
  (is (= :incompatible-scope (tmc/replication-type []))))

;; ── Execution status ──────────────────────────────────────────────────────

(deftest execution-status-three-same
  (is (= :three-member-replicated (tmc/execution-status (tmc/group-outcomes reports-exact)))))

;; ── Per-dimension consensus ───────────────────────────────────────────────

(deftest per-dimension-unanimous
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b") (make-pos "c")]
           :model-state)]
    (is (= :unanimous (:status c)))
    (is (= 3 (count (:supporting-members c))))
    (is (empty? (:dissenting-members c)))))

(deftest per-dimension-majority-with-dissent
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b")
            (make-pos "c" :authority-status :contested)]
           :model-authority)]
    (is (= :majority-with-dissent (:status c)))
    (is (= 2 (count (:supporting-members c))))
    (is (= 1 (count (:dissenting-members c))))))

(deftest per-dimension-absent-member
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b") (make-pos-absent "c")]
           :model-authority)]
    (is (= :unanimous (:status c)))
    (is (= 1 (count (:absent-members c))))))

(deftest per-dimension-not-reviewed-member
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b")
            (make-pos "c" :authority-status :not-reviewed)]
           :model-authority)]
    (is (= :unanimous (:status c)))
    (is (= 1 (count (:not-reviewed-members c))))
    (is (empty? (:dissenting-members c)))))

(deftest per-dimension-not-applicable-member
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b")
            (make-pos "c" :authority-status :not-applicable)]
           :model-authority)]
    (is (= :unanimous (:status c)))
    (is (= 1 (count (:not-applicable-members c))))))

(deftest per-dimension-all-absent-not-evaluable
  (let [c (tmc/per-dimension-consensus
           [(make-pos-absent "a") (make-pos-absent "b") (make-pos-absent "c")]
           :model-authority)]
    (is (= :not-evaluable (:status c)))
    (is (= 3 (count (:absent-members c))))))

(deftest per-dimension-includes-positions
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b") (make-pos "c")]
           :model-authority)]
    (is (= 3 (count (:positions c))))))

;; ── Certificate ───────────────────────────────────────────────────────────

(deftest certificate-valid
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])]
    (is (tmc/certificate-valid? cert))
    (is (= :exact-replication (get-in cert [:execution :replication-type])))))

(deftest certificate-separates-consensus-domains
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])]
    (is (contains? cert :model-consensus))
    (is (contains? cert :incentive-consensus))
    (is (contains? cert :other-consensus))))

(deftest certificate-with-absent-member
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos-absent "c")])
        auth (get-in cert [:model-consensus :model-authority])]
    (is (= 1 (count (:absent-members auth))))
    (is (= :unanimous (:status auth)))))

(deftest certificate-with-not-reviewed-member
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b")
                         (make-pos "c" :authority-status :not-reviewed)])
        auth (get-in cert [:model-consensus :model-authority])]
    (is (= 1 (count (:not-reviewed-members auth))))
    (is (= :unanimous (:status auth)))
    (is (empty? (:dissenting-members auth)))))

(deftest certificate-finalised
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])
        final (tmc/finalise-certificate! cert)]
    (is (tmc/certificate-finalised? final))
    (is (some? (:certificate/hash final)))))

(deftest certificate-no-synthetic-outcome
  (let [groups (tmc/group-outcomes reports-two-same)]
    (is (= 2 (count groups)))))

(deftest compatibility-symmetry
  (let [a (make-report "a" "sha256:A" :cases "sha256:c1")
        b (make-report "b" "sha256:A" :cases "sha256:c1")
        c (make-report "c" "sha256:B" :cases "sha256:c2")]
    (is (= (tmc/replication-type [a b c])
           (tmc/replication-type [c b a])))))

;; ── Theorem/conclusion consensus ──────────────────────────────────────────

(defn- make-pos-with-targets
  [id & {:keys [targets] :or {targets []}}]
  {:researcher/id id
   :position/hash (str "sha256:pos-" id)
   :position/outcome-hash "sha256:A"
   :position/dimensions {:model-state {:status :adequate}
                         :model-authority {:status :adequate}
                         :model-transitions {:status :adequate}
                         :incentives-strategies {:status :adequate}
                         :evidence {:status :sufficient}
                         :claims {:status :supported}
                         :publication {:status :publish}}
   :position/targets targets})

(deftest per-theorem-consensus-unanimous
  (let [posses [(make-pos-with-targets "a"
                                       :targets [{:kind :theorem :id :theorem/quota-bounded
                                                  :hash "sha256:th1" :status :reproduced}])
                (make-pos-with-targets "b"
                                       :targets [{:kind :theorem :id :theorem/quota-bounded
                                                  :hash "sha256:th1" :status :reproduced}])
                (make-pos-with-targets "c"
                                       :targets [{:kind :theorem :id :theorem/quota-bounded
                                                  :hash "sha256:th1" :status :reproduced}])]
        consensus (tmc/per-theorem-consensus posses)]
    (is (contains? consensus :theorem/quota-bounded))
    (let [th (get consensus :theorem/quota-bounded)]
      (is (= :unanimous (:status th)))
      (is (= 3 (count (:supporting-members th))))
      (is (empty? (:dissenting-members th))))))

(deftest per-theorem-consensus-majority-with-dissent
  (let [posses [(make-pos-with-targets "a"
                                       :targets [{:kind :theorem :id :theorem/incentive-compatibility
                                                  :hash "sha256:th2" :status :reproduced}])
                (make-pos-with-targets "b"
                                       :targets [{:kind :theorem :id :theorem/incentive-compatibility
                                                  :hash "sha256:th2" :status :reproduced}])
                (make-pos-with-targets "c"
                                       :targets [{:kind :theorem :id :theorem/incentive-compatibility
                                                  :hash "sha256:th2" :status :challenged}])]
        consensus (tmc/per-theorem-consensus posses)]
    (let [th (get consensus :theorem/incentive-compatibility)]
      (is (= :majority-with-dissent (:status th)))
      (is (= 2 (count (:supporting-members th))))
      (is (= 1 (count (:dissenting-members th)))))))

(deftest per-conclusion-consensus-with-absent-members
  (let [posses [(make-pos-with-targets "a"
                                       :targets [{:kind :conclusion :id :conclusion/partial-fill
                                                  :hash "sha256:c1" :status :supported}])
                (make-pos-with-targets "b"
                                       :targets [{:kind :conclusion :id :conclusion/partial-fill
                                                  :hash "sha256:c1" :status :supported}])
                (make-pos-with-targets "c"
                                       :targets [])]
        consensus (tmc/per-conclusion-consensus posses)]
    (let [th (get consensus :conclusion/partial-fill)]
      (is (= :unanimous (:status th)))
      (is (= 2 (count (:supporting-members th))))
      (is (= 1 (count (:absent-members th)))))))

(deftest per-theorem-consensus-empty-when-no-targets
  (let [consensus (tmc/per-theorem-consensus
                   [(make-pos-with-targets "a")
                    (make-pos-with-targets "b")
                    (make-pos-with-targets "c")])]
    (is (empty? consensus))))

(deftest certificate-includes-theorem-conclusion-consensus
  (let [cert (make-cert reports-exact
                        [(make-pos-with-targets "a"
                                                :targets [{:kind :theorem :id :theorem/quota-bounded
                                                           :hash "sha256:t" :status :reproduced}])
                         (make-pos-with-targets "b"
                                                :targets [{:kind :theorem :id :theorem/quota-bounded
                                                           :hash "sha256:t" :status :reproduced}])
                         (make-pos-with-targets "c"
                                                :targets [{:kind :theorem :id :theorem/quota-bounded
                                                           :hash "sha256:t" :status :reproduced}])])]
    (is (contains? cert :theorem-consensus))
    (is (contains? cert :conclusion-consensus))
    (let [th-cons (get-in cert [:theorem-consensus :theorem/quota-bounded])]
      (is (= :unanimous (:status th-cons)))
      (is (= 3 (count (:supporting-members th-cons)))))))

;; ── Member-key certificate tests ───────────────────────────────────────────

(def keyed-round-members
  [{:review-member/key 0, :researcher/id "a", :role :model-steward}
   {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}])

(defn- make-keyed-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:cr"
    :review-round/purpose :model-admission
    :review-round/members keyed-round-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(defn- build-with-ci
  "Build a certificate for a keyed round, auto-deriving canonical-indices."
  [& {:keys [round reports positions force-authorisations disagreements]
      :or {force-authorisations [] disagreements []}}]
  (let [r (or round (make-keyed-round))
        indices (ci/build-canonical-indices r)]
    (tmc/build-certificate
     {:review-round r
      :canonical-indices indices
      :reports reports
      :positions positions
      :force-authorisations force-authorisations
      :disagreements disagreements})))

(deftest keyed-round-certificate-emits-key-vectors
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        cert (tmc/build-certificate
              {:review-round round
               :canonical-indices indices
               :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]})
        pub-cons (get-in cert [:other-consensus :publication])]
    (is (tmc/certificate-valid? cert))
    (is (= ["a" "b" "c"] (:supporting-members pub-cons)))
    (is (= [0 1 2] (:supporting-member-indices pub-cons)) "should emit index vectors")))

(deftest keyed-round-certificate-dissent-key-vectors
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        dim-pos [(rp/build-position
                  {:benchmark/content-root "sha256:cr"
                   :researcher/id "a"
                   :outcome-hash "sha256:o"
                   :dimensions {:publication {:status :publish}}})
                 (rp/build-position
                  {:benchmark/content-root "sha256:cr"
                   :researcher/id "b"
                   :outcome-hash "sha256:o"
                   :dimensions {:publication {:status :publish}}})
                 (rp/build-position
                  {:benchmark/content-root "sha256:cr"
                   :researcher/id "c"
                   :outcome-hash "sha256:o"
                   :dimensions {:publication {:status :do-not-publish}}})]
        cert (tmc/build-certificate
              {:review-round round
               :canonical-indices indices
               :reports (mapv (fn [id] (make-report id "sha256:o")) ["a" "b" "c"])
               :positions dim-pos})
        pub-cons (get-in cert [:other-consensus :publication])]
    (is (= :majority-with-dissent (:status pub-cons)))
    (is (= ["c"] (:dissenting-members pub-cons)))
    (is (= [2] (:dissenting-member-indices pub-cons)))))

(deftest legacy-round-certificate-omits-key-vectors
  (let [legacy-round (rr/build-review-round
                      {:benchmark/content-root "sha256:cr"
                       :review-round/purpose :model-admission
                       :review-round/members
                       [{:researcher/id "a" :role :model-steward}
                        {:researcher/id "b" :role :independent-reproducer}
                        {:researcher/id "c" :role :adversarial-reviewer}]
                       :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                       :review-round/policy-root "sha256:policy"})
        cert (tmc/build-certificate
              {:review-round legacy-round
               :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]})
        pub-cons (get-in cert [:other-consensus :publication])]
    (is (tmc/certificate-valid? cert))
    (is (contains? pub-cons :supporting-member-indices)
        "legacy round now emits index vectors (canonical indices always built)")))

(deftest keyed-round-member-positions-include-key
  (let [round (make-keyed-round)
        cert (build-with-ci
              :round round
              :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
              :positions [(make-pos "a") (make-pos "b") (make-pos "c")])]
    (is (= [0 1 2] (mapv :review-member/index (:member-positions cert))))))

;; ── (Resolution-quality tests deferred to separate workstream) ─────────────

;; ── Canonical-indices certificate integration tests ─────────────────────────

(deftest certificate-with-canonical-indices-valid
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        cert (build-with-ci
              :round round
              :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
              :positions [(make-pos "a") (make-pos "b") (make-pos "c")])
        final (tmc/finalise-certificate! cert)]
    (is (tmc/certificate-valid? cert))
    (is (:valid? (tmc/validate-certificate final))
        "certificate must validate when canonical-indices are bound")
    (is (some? (:review-member-canonical-indices/hash cert))
        "certificate must reference the canonical-indices hash when supplied")))

(deftest certificate-rejects-tampered-canonical-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        tampered (assoc indices :review-member-canonical-indices/hash
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tmc/build-certificate
                  {:review-round round
                   :canonical-indices tampered
                   :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
                   :positions [(make-pos "a") (make-pos "b") (make-pos "c")]}))
        "must reject tampered canonical-indices artifact")))

(deftest certificate-canonical-indices-hash-binds-to-certificate-hash
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        cert (build-with-ci
              :round round
              :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
              :positions [(make-pos "a") (make-pos "b") (make-pos "c")])
        final (tmc/finalise-certificate! cert)]
    (is (some? (:review-member-canonical-indices/hash final))
        "canonical-indices hash must be present in final certificate")
    (is (not= (:review-member-canonical-indices/hash final) (:certificate/hash final))
        "canonical-indices hash must differ from certificate hash")))

(deftest certificate-member-positions-agree-with-canonical-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        cert (tmc/build-certificate
              {:review-round round
               :canonical-indices indices
               :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]})]
    (doseq [pos (:member-positions cert)]
      (is (= (ci/review-member-index indices (:researcher/id pos))
             (:review-member/index pos))
          (str "member-position index " (:researcher/id pos)
               " must agree with canonical index")))))

;; ── End-to-end canonical-indices certificate tests ─────────────────────────

(deftest e2e-certificate-with-canonical-indices-and-theorem-consensus
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        positions [(rp/build-position
                    {:benchmark/content-root "sha256:cr"
                     :researcher/id "a"
                     :outcome-hash "sha256:o"
                     :dimensions {:model-state {:status :adequate}
                                  :model-authority {:status :adequate}
                                  :model-transitions {:status :adequate}
                                  :incentives-strategies {:status :adequate}
                                  :evidence {:status :sufficient}
                                  :claims {:status :supported}
                                  :publication {:status :publish}}
                     :position/targets [{:kind :theorem :id :theorem/quota-bounded
                                         :hash "sha256:th1" :status :reproduced}
                                        {:kind :theorem :id :theorem/settlement-consistency
                                         :hash "sha256:th2" :status :reproduced}]})
                   (rp/build-position
                    {:benchmark/content-root "sha256:cr"
                     :researcher/id "b"
                     :outcome-hash "sha256:o"
                     :dimensions {:model-state {:status :adequate}
                                  :model-authority {:status :adequate}
                                  :model-transitions {:status :adequate}
                                  :incentives-strategies {:status :adequate}
                                  :evidence {:status :sufficient}
                                  :claims {:status :supported}
                                  :publication {:status :publish}}
                     :position/targets [{:kind :theorem :id :theorem/quota-bounded
                                         :hash "sha256:th1" :status :reproduced}
                                        {:kind :theorem :id :theorem/settlement-consistency
                                         :hash "sha256:th2" :status :challenged}]})
                   (rp/build-position
                    {:benchmark/content-root "sha256:cr"
                     :researcher/id "c"
                     :outcome-hash "sha256:o"
                     :dimensions {:model-state {:status :adequate}
                                  :model-authority {:status :adequate}
                                  :model-transitions {:status :adequate}
                                  :incentives-strategies {:status :adequate}
                                  :evidence {:status :sufficient}
                                  :claims {:status :supported}
                                  :publication {:status :publish}}
                     :position/targets [{:kind :theorem :id :theorem/quota-bounded
                                         :hash "sha256:th1" :status :reproduced}
                                        {:kind :theorem :id :theorem/settlement-consistency
                                         :hash "sha256:th2" :status :reproduced}]})]
        reports [(make-report "a" "sha256:A")
                 (make-report "b" "sha256:A")
                 (make-report "c" "sha256:A")]
        cert (tmc/build-certificate
              {:review-round round
               :canonical-indices indices
               :reports reports
               :positions positions})
        final (tmc/finalise-certificate! cert)]
    ;; Certificate is valid
    (is (tmc/certificate-valid? cert))
    (is (:valid? (tmc/validate-certificate final))
        "e2e certificate must validate")
    ;; Canonical-indices hash is bound
    (is (some? (:review-member-canonical-indices/hash cert))
        "canonical-indices hash bound in certificate")
    ;; Per-theorem consensus is computed
    (is (contains? (:theorem-consensus cert) :theorem/quota-bounded)
        "quota-bounded theorem consensus present")
    (is (contains? (:theorem-consensus cert) :theorem/settlement-consistency)
        "settlement-consistency theorem consensus present")
    (let [qb (get-in cert [:theorem-consensus :theorem/quota-bounded])
          sc (get-in cert [:theorem-consensus :theorem/settlement-consistency])]
      (is (= :unanimous (:status qb)) "quota-bounded unanimous")
      (is (= 3 (count (:supporting-members qb))) "all three support quota-bounded")
      (is (= :majority-with-dissent (:status sc)) "settlement-consistency majority-with-dissent")
      (is (= 2 (count (:supporting-members sc))) "two support")
      (is (= 1 (count (:dissenting-members sc))) "one dissents"))
    ;; Key-enriched consensus vectors are present for keyed rounds
    (let [qb (get-in final [:theorem-consensus :theorem/quota-bounded])]
      (is (some? (:supporting-member-indices qb)) "keyed round emits index vectors"))
    ;; Member positions agree with canonical indices
    (doseq [pos (:member-positions cert)]
      (is (= (ci/review-member-index indices (:researcher/id pos))
             (:review-member/index pos))
          (str "member-position index " (:researcher/id pos)
               " agrees with canonical index")))
    ;; Certificate hash binds canonical-indices hash
    (is (not= (:review-member-canonical-indices/hash cert)
              (:certificate/hash final))
        "canonical-indices hash differs from certificate hash")))

(deftest e2e-certificate-rejects-canonical-indices-round-mismatch
  (let [round-a (make-keyed-round)
        round-b (rr/build-review-round
                 {:benchmark/content-root "sha256:cr"
                  :review-round/purpose :model-admission
                  :review-round/members
                  [{:review-member/key 0 :researcher/id "x" :role :model-steward}
                   {:review-member/key 1 :researcher/id "y" :role :independent-reproducer}
                   {:review-member/key 2 :researcher/id "z" :role :adversarial-reviewer}]
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})
        indices (ci/build-canonical-indices round-a)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tmc/build-certificate
                  {:review-round round-b
                   :canonical-indices indices
                   :reports (mapv (fn [id] (make-report id "sha256:A")) ["x" "y" "z"])
                   :positions [(make-pos "x") (make-pos "y") (make-pos "z")]}))
        "must reject canonical-indices from different round")))

(deftest e2e-legacy-round-accepts-ci
  (let [legacy-round (rr/build-review-round
                      {:benchmark/content-root "sha256:cr"
                       :review-round/purpose :model-admission
                       :review-round/members
                       [{:researcher/id "a" :role :model-steward}
                        {:researcher/id "b" :role :independent-reproducer}
                        {:researcher/id "c" :role :adversarial-reviewer}]
                       :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                       :review-round/policy-root "sha256:policy"})
        indices (ci/build-canonical-indices legacy-round)
        cert (tmc/build-certificate
              {:review-round legacy-round
               :canonical-indices indices
               :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]})]
    (is (some? (:review-member-canonical-indices/hash cert))
        "canonical-indices is now accepted for unkeyed rounds")))

(deftest e2e-legacy-round-works-without-ci
  (let [legacy-round (rr/build-review-round
                      {:benchmark/content-root "sha256:cr"
                       :review-round/purpose :model-admission
                       :review-round/members
                       [{:researcher/id "a" :role :model-steward}
                        {:researcher/id "b" :role :independent-reproducer}
                        {:researcher/id "c" :role :adversarial-reviewer}]
                       :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                       :review-round/policy-root "sha256:policy"})
        cert (tmc/build-certificate
              {:review-round legacy-round
               :reports (mapv (fn [id] (make-report id "sha256:A")) ["a" "b" "c"])
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]})
        final (tmc/finalise-certificate! cert)]
    (is (tmc/certificate-valid? cert))
    (is (:valid? (tmc/validate-certificate final))
        "legacy unkeyed round certificate must validate")
    (is (some? (:review-member-canonical-indices/hash cert))
        "legacy certificate now has canonical-indices hash (auto-produced)")))


