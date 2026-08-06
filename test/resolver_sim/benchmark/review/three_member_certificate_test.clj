(ns resolver-sim.benchmark.review.three-member-certificate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.hash.canonical :as hc]))

(defn- make-report [id outcome-hash & {:keys [mi plan domain sampling params cases eval-policy model-root content-root]
                                       :or {content-root "sha256:cr" model-root "sha256:m"
                                            mi "sha256:mi" plan "sha256:plan"
                                            domain "sha256:domain" sampling "sha256:samp"
                                            params "sha256:p" cases "sha256:c"
                                            eval-policy "sha256:ep"}}]
  (let [report {:schema-version "researcher-run-report.v1"
                :researcher/id id :researcher-run-report/outcome-hash outcome-hash
                :benchmark/content-root content-root :benchmark/model-root model-root
                :benchmark/evaluation-policy-root eval-policy
                :execution/content-root content-root :execution/model-root model-root
                :execution/model-instance-root mi :execution/plan-root plan
                :execution/parameter-domain-root domain :execution/sampling-policy-root sampling
                :execution/realised-parameter-set-root params :execution/generated-case-set-root cases
                :researcher-run-report/hash nil :researcher/signature nil}]
    (assoc report :researcher-run-report/hash
           (str "sha256:" (hc/domain-hash :researcher-run-report report)))))

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
                       :or {authority-status :adequate evidence-status :sufficient
                            publication-status :publish}}]
  (rp/build-position {:benchmark/content-root "sha256:cr" :researcher/id id
                      :outcome-hash "sha256:A"
                      :dimensions (dims :model-authority authority-status
                                        :evidence evidence-status
                                        :publication publication-status)}))

(defn- make-pos-absent [id]
  (rp/build-position {:benchmark/content-root "sha256:cr" :researcher/id id
                      :outcome-hash "sha256:A" :dimensions {}}))

(def ^:private default-round
  (rr/build-review-round
   {:benchmark/content-root "sha256:cr"
    :review-round/purpose :model-admission
    :review-round/members [{:researcher/id "a" :role :model-steward}
                           {:researcher/id "b" :role :independent-reproducer}
                           {:researcher/id "c" :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

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

(deftest per-dimension-qualified-majority-classifies-qualifying
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b")
            (make-pos "c" :publication-status :publish-with-qualification)]
           :publication)]
    (is (= :qualified-majority (:status c))
        "2 publish + 1 publish-with-qualification -> qualified majority, not dissent")
    (is (= ["a" "b"] (:supporting-members c)))
    (is (= ["c"] (:qualifying-members c))
        "qualification is classified separately from dissent")
    (is (empty? (:dissenting-members c)))))

(deftest per-item-qualified-target-is-qualifying-not-dissenting
  (let [consensus (tmc/per-item-consensus
                   :theorem/x :theorem
                   [{:researcher/id "a" :status :reproduced}
                    {:researcher/id "b" :status :reproduced}
                    {:researcher/id "c" :status :qualified}]
                   ["a" "b" "c"])]
    (is (= :qualified-majority (:status consensus)))
    (is (= ["a" "b"] (:supporting-members consensus)))
    (is (= ["c"] (:qualifying-members consensus)))
    (is (empty? (:dissenting-members consensus)))))

(deftest per-dimension-contested-reports-status-groups
  (let [c (tmc/per-dimension-consensus
           [(make-pos "a") (make-pos "b" :authority-status :incomplete)
            (make-pos "c" :authority-status :contested)]
           :model-authority)]
    (is (= :contested (:status c)))
    (is (empty? (:supporting-members c)) "no majority exists in a contested cell")
    (is (empty? (:dissenting-members c)) "no majority exists to dissent against")
    (is (= 3 (count (:contested-statuses c)))
        "each distinct assessed status is preserved in the per-status breakdown")
    (is (= #{["a"] ["b"] ["c"]} (set (map :members (:contested-statuses c)))))))

;; ── Disagreement records ─────────────────────────────────────────────────

(defn- disagreement-positions []
  [(make-pos "a") (make-pos "b")
   (make-pos "c" :publication-status :do-not-publish)])

(defn- disagreement-checks [positions disagreements]
  (tmc/pre-certificate-checks
   {:review-round default-round
    :canonical-indices (ci/build-canonical-indices default-round)
    :reports reports-exact :positions positions
    :disagreements disagreements}))

(deftest disagreement-record-validated-and-linked
  (let [positions (disagreement-positions)
        checks (disagreement-checks
                positions
                [{:researcher/id "c" :dimension :publication
                  :status :do-not-publish
                  :rationale "publishing would overreach the evidence"}])]
    (is (:pre-certificate-valid? checks))
    (let [cert (-> (tmc/build-certificate
                    {:review-round default-round :reports reports-exact :positions positions
                     :disagreements [{:researcher/id "c" :dimension :publication
                                      :status :do-not-publish
                                      :rationale "publishing would overreach the evidence"}]})
                   tmc/finalise-certificate!)]
      (is (= 1 (count (:unresolved-disagreements cert))))
      (is (= :valid (:status (tmc/validate-certificate cert)))))))

(deftest disagreement-record-for-supporting-member-rejected
  (let [checks (disagreement-checks
                (disagreement-positions)
                [{:researcher/id "a" :dimension :publication
                  :status :do-not-publish
                  :rationale "a claims to disagree despite supporting"}])]
    (is (not (:pre-certificate-valid? checks)))
    (is (some #(re-find #"supports the consensus" %) (:errors checks)))))

(deftest disagreement-record-for-non-member-rejected
  (let [checks (disagreement-checks
                (disagreement-positions)
                [{:researcher/id "z" :dimension :publication
                  :status :do-not-publish
                  :rationale "outsider disagreement"}])]
    (is (not (:pre-certificate-valid? checks)))
    (is (some #(re-find #"not a review-round member" %) (:errors checks)))))

(deftest disagreement-record-unknown-dimension-or-status-rejected
  (let [checks (disagreement-checks
                (disagreement-positions)
                [{:researcher/id "c" :dimension :not-a-dimension
                  :status :do-not-publish
                  :rationale "unknown dimension"}
                 {:researcher/id "c" :dimension :publication
                  :status :nonsense
                  :rationale "invalid status"}])]
    (is (not (:pre-certificate-valid? checks)))
    (is (some #(re-find #"not a consensus dimension" %) (:errors checks)))
    (is (some #(re-find #"is invalid for dimension" %) (:errors checks)))))

(deftest disagreement-record-duplicate-rejected
  (let [checks (disagreement-checks
                (disagreement-positions)
                [{:researcher/id "c" :dimension :publication
                  :status :do-not-publish
                  :rationale "first"}
                 {:researcher/id "c" :dimension :publication
                  :status :do-not-publish
                  :rationale "duplicate"}])]
    (is (not (:pre-certificate-valid? checks)))
    (is (some #(re-find #"duplicate disagreement" %) (:errors checks)))))

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

(deftest certificate-v3-includes-new-consensus-dimensions
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])
        all-consensus (merge (:model-consensus cert)
                             (:incentive-consensus cert)
                             (:other-consensus cert))]
    (is (= "three-member-research-certificate.v3" (:schema-version cert)))
    (doseq [dim [:model-invariants :temporal-fidelity :sampling-policy]]
      (is (contains? (:model-consensus cert) dim)
          (str dim " reported under model-consensus"))
      (is (= :not-evaluable (get-in cert [:model-consensus dim :status]))
          (str dim " is not-evaluable when no member assessed it")))
    (doseq [dim [:determinism :provenance]]
      (is (contains? (:other-consensus cert) dim)
          (str dim " reported under other-consensus"))
      (is (= :not-evaluable (get-in cert [:other-consensus dim :status]))
          (str dim " is not-evaluable when no member assessed it")))
    (is (= 18 (count all-consensus))
        "certificate surfaces 18 consensus dimensions (9 model, 3 incentive, 6 other)")))

(deftest certificate-v3-disagreement-on-new-dimension
  (let [pos (fn [id status] (rp/build-position
                             {:benchmark/content-root "sha256:cr" :researcher/id id
                              :outcome-hash "sha256:A"
                              :dimensions {:model-invariants {:status status}}}))
        positions [(pos "a" :adequate) (pos "b" :adequate) (pos "c" :inadequate)]
        checks (tmc/pre-certificate-checks
                {:review-round default-round
                 :canonical-indices (ci/build-canonical-indices default-round)
                 :reports reports-exact
                 :positions positions
                 :disagreements [{:researcher/id "c" :dimension :model-invariants
                                  :status :inadequate :rationale "one invariant gap"}]})]
    (is (:pre-certificate-valid? checks))
    (let [cert (tmc/build-certificate
                {:review-round default-round
                 :canonical-indices (ci/build-canonical-indices default-round)
                 :reports reports-exact :positions positions})
          inv (get-in cert [:model-consensus :model-invariants])]
      (is (= :majority-with-dissent (:status inv)))
      (is (= ["c"] (:dissenting-members inv))))))

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
  (rp/build-position
   {:benchmark/content-root "sha256:cr" :researcher/id id :outcome-hash "sha256:A"
    :dimensions {:model-state {:status :adequate} :model-authority {:status :adequate}
                 :model-transitions {:status :adequate} :incentives-strategies {:status :adequate}
                 :evidence {:status :sufficient} :claims {:status :supported}
                 :publication {:status :publish}}
    :position/targets targets}))

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
    (is (contains? consensus [:theorem/quota-bounded "sha256:th1"]))
    (let [th (get consensus [:theorem/quota-bounded "sha256:th1"])]
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
    (let [th (get consensus [:theorem/incentive-compatibility "sha256:th2"])]
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
    (let [th (get consensus [:conclusion/partial-fill "sha256:c1"])]
      (is (= :unanimous (:status th)))
      (is (= 2 (count (:supporting-members th))))
      (is (= 1 (count (:absent-members th)))))))

(deftest per-item-majority-with-dissent-reports-assessed-and-dissenting
  (let [consensus (tmc/per-item-consensus
                   :theorem/x :theorem
                   [{:researcher/id "a" :status :supported}
                    {:researcher/id "b" :status :supported}
                    {:researcher/id "c" :status :contradicted}]
                   ["a" "b" "c"])]
    (is (= :majority-with-dissent (:status consensus)))
    (is (= ["a" "b"] (:supporting-members consensus)))
    (is (= ["c"] (:dissenting-members consensus))
        "dissenting-members are the minority, not the majority")
    (is (= ["a" "b" "c"] (:assessed-members consensus))
        "assessed-members are the researchers who provided a position status")
    (is (empty? (:absent-members consensus)))))

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
    (let [th-cons (get-in cert [:theorem-consensus [:theorem/quota-bounded "sha256:t"]])]
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

(deftest certificate-v3-does-not-fabricate-new-dimensions
  (testing "unassessed new dimensions are :not-evaluable, never synthesized consensus"
    (let [cert (make-cert reports-exact
                          [(make-pos "a") (make-pos "b") (make-pos "c")])]
      (doseq [dim [:model-invariants :temporal-fidelity :sampling-policy
                   :determinism :provenance]]
        (let [cell (or (get-in cert [:model-consensus dim])
                       (get-in cert [:other-consensus dim]))]
          (is (= :not-evaluable (:status cell)) (str dim " not-evaluable"))
          (is (empty? (:assessed-members cell)) (str dim " has no assessed members")))))
    (let [cert (make-cert reports-exact
                          [(make-pos "a") (make-pos "b") (make-pos "c")])
          final (tmc/finalise-certificate! cert)]
      (is (:valid? (tmc/validate-certificate final))
          "a certificate that never assessed the new dimensions still validates"))))

;; ── Certificate v3 follow-through: re-certification and the v2→v3 transition ──

(def ^:private v2-dimension-set
  (into #{} [:model-state :model-transitions :model-authority :model-adversary
             :model-parameters :model-cases
             :incentives-participants :incentives-strategies :incentives-coalitions
             :reproduction :evidence :claims :publication]))

(defn- as-v2-shaped
  "Project a v3 certificate body to the v2 shape: old schema version, old
   13-dimension consensus set, no :supersedes-certificate-root.  Demonstrates
   the version transition without fabricating a review that did not occur."
  [cert]
  (let [keep (fn [m] (into {} (filter (fn [[k _]] (contains? v2-dimension-set k))) m))]
    (-> cert
        (assoc :schema-version "three-member-research-certificate.v2")
        (update :model-consensus keep)
        (update :incentive-consensus keep)
        (update :other-consensus keep)
        (dissoc :supersedes-certificate-root :certificate/hash
                :review-member-canonical-indices))))

(deftest certificate-version-transition-roots-differ
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])
        v2-body (as-v2-shaped cert)
        v3-body (dissoc cert :certificate/hash :review-member-canonical-indices)
        v2-root (str "sha256:" (hc/domain-hash :three-member-certificate v2-body))
        v3-root (str "sha256:" (hc/domain-hash :three-member-certificate v3-body))]
    (is (not= v2-root v3-root)
        "the same researcher positions produce different certificate roots under
         v2 and v3 — the transition is observable, not silent")
    (is (= "three-member-research-certificate.v2" (:schema-version v2-body)))
    (is (= 13 (count (into {} (concat (:model-consensus v2-body)
                                      (:incentive-consensus v2-body)
                                      (:other-consensus v2-body)))))
        "v2 shape carries only the 13 legacy dimensions")))

(deftest certificate-legacy-status-distinguishes-verifiable
  (let [cert (make-cert reports-exact
                        [(make-pos "a") (make-pos "b") (make-pos "c")])
        v2-body (as-v2-shaped cert)
        v2-root (str "sha256:" (hc/domain-hash :three-member-certificate v2-body))]
    (testing "possession of the original signed bytes permits self-hash verification"
      (let [signed (assoc v2-body :certificate/hash v2-root)
            status (tmc/legacy-certificate-status signed)]
        (is (:valid? status))
        (is (= :legacy-signature-verifiable (:status status)))))
    (testing "a tampered legacy body is :legacy-not-recomputable"
      (let [tampered (assoc v2-body :certificate/hash
                            "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
            status (tmc/legacy-certificate-status tampered)]
        (is (not (:valid? status)))
        (is (= :legacy-not-recomputable (:status status)))))
    (testing "validate-certificate routes legacy schemas through the distinction"
      (let [signed (assoc v2-body :certificate/hash v2-root)]
        (is (= :legacy-signature-verifiable (:status (tmc/validate-certificate signed))))
        (is (not (contains? #{:valid :invalid} (:status (tmc/validate-certificate signed)))))))))

(deftest certificate-supersedes-root-binds-into-hash
  (let [prior (make-cert reports-exact
                         [(make-pos "a") (make-pos "b") (make-pos "c")])
        prior-root (-> prior tmc/finalise-certificate! :certificate/hash)
        cert (tmc/build-certificate
              {:review-round default-round
               :canonical-indices (ci/build-canonical-indices default-round)
               :reports reports-exact
               :positions [(make-pos "a") (make-pos "b") (make-pos "c")]
               :supersedes-certificate-root prior-root})
        final (tmc/finalise-certificate! cert)]
    (is (= prior-root (:supersedes-certificate-root cert)))
    (is (:valid? (tmc/validate-certificate final))
        "supersedes-root is part of the recomputable, hash-bound body")
    (is (not= (:certificate/hash final) prior-root)
        "re-certification is a distinct certificate root")))

(deftest certificate-supersedes-root-rejects-non-reference
  (is (thrown? clojure.lang.ExceptionInfo
               (tmc/build-certificate
                {:review-round default-round
                 :canonical-indices (ci/build-canonical-indices default-round)
                 :reports reports-exact
                 :positions [(make-pos "a") (make-pos "b") (make-pos "c")]
                 :supersedes-certificate-root "not-a-hash"}))))

(deftest fixture-version-transition-recomputes
  (testing "the committed v2→v3 fixture recomputes from current code — if the
            schema or hash projection changes, this test forces a fixture
            regeneration rather than a silent transition"
    (let [fixture (edn/read-string
                   (slurp "test/fixtures/review/version_transition_v2_v3.edn"))
          v2-root (get fixture :certificate-root/v2)
          v3-root (get fixture :certificate-root/v3)
          cert (tmc/build-certificate
                {:review-round (:review-round fixture)
                 :reports (:reports fixture)
                 :positions (:positions fixture)})
          v2-body (as-v2-shaped cert)
          v3-body (dissoc cert :certificate/hash :review-member-canonical-indices)]
      (is (:roots-differ? fixture))
      (is (not= v2-root v3-root)
          "the same positions have different roots under v2 and v3")
      (is (= (:v2-shaped-body fixture) v2-body))
      (is (= (:v3-body fixture) v3-body))
      (is (= v2-root (str "sha256:" (hc/domain-hash :three-member-certificate v2-body))))
      (is (= v3-root (str "sha256:" (hc/domain-hash :three-member-certificate v3-body)))))))

;; ── End-to-end canonical-indices certificate tests ─────────────────────────

(deftest e2e-certificate-with-canonical-indices-and-theorem-consensus
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        positions [(rp/build-position
                    {:benchmark/content-root "sha256:cr"
                     :researcher/id "a"
                     :outcome-hash "sha256:A"
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
                     :outcome-hash "sha256:A"
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
                     :outcome-hash "sha256:A"
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
    (is (contains? (:theorem-consensus cert) [:theorem/quota-bounded "sha256:th1"])
        "quota-bounded theorem consensus present")
    (is (contains? (:theorem-consensus cert) [:theorem/settlement-consistency "sha256:th2"])
        "settlement-consistency theorem consensus present")
    (let [qb (get-in cert [:theorem-consensus [:theorem/quota-bounded "sha256:th1"]])
          sc (get-in cert [:theorem-consensus [:theorem/settlement-consistency "sha256:th2"]])]
      (is (= :unanimous (:status qb)) "quota-bounded unanimous")
      (is (= 3 (count (:supporting-members qb))) "all three support quota-bounded")
      (is (= :majority-with-dissent (:status sc)) "settlement-consistency majority-with-dissent")
      (is (= 2 (count (:supporting-members sc))) "two support")
      (is (= 1 (count (:dissenting-members sc))) "one dissents"))
    ;; Key-enriched consensus vectors are present for keyed rounds
    (let [qb (get-in final [:theorem-consensus [:theorem/quota-bounded "sha256:th1"]])]
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

;; ── WP5 integrity regressions ──────────────────────────────────────────────

(defn- valid-inputs []
  (let [reports reports-exact
        positions [(make-pos "a") (make-pos "b") (make-pos "c")]]
    {:review-round default-round
     :canonical-indices (ci/build-canonical-indices default-round)
     :reports reports :positions positions}))

(deftest certificate-rejects-non-one-to-one-member-joins
  (let [{:keys [review-round canonical-indices reports positions]} (valid-inputs)
        result (tmc/pre-certificate-checks
                {:review-round review-round :canonical-indices canonical-indices
                 :reports [(first reports) (first reports) (nth reports 2)]
                 :positions positions})]
    (is (not (:pre-certificate-valid? result)))
    (is (some #(re-find #"one artifact per distinct member|exactly match" %)
              (:errors result)))))

(deftest certificate-rejects-root-and-outcome-binding-mismatches
  (let [{:keys [review-round canonical-indices reports positions]} (valid-inputs)
        root-result (tmc/pre-certificate-checks
                     {:review-round review-round :canonical-indices canonical-indices
                      :reports (assoc reports 0 (assoc (first reports)
                                                       :benchmark/content-root "sha256:other"))
                      :positions positions})
        outcome-result (tmc/pre-certificate-checks
                        {:review-round review-round :canonical-indices canonical-indices
                         :reports reports
                         :positions (assoc positions 0 (rp/build-position
                                                        {:benchmark/content-root "sha256:cr"
                                                         :researcher/id "a" :outcome-hash "sha256:other"
                                                         :dimensions (dims)}))})]
    (is (not (:pre-certificate-valid? root-result)))
    (is (some #(re-find #"content-root" %) (:errors root-result)))
    (is (not (:pre-certificate-valid? outcome-result)))
    (is (some #(re-find #"outcome-hash mismatch" %) (:errors outcome-result)))))

(deftest realised-parameters-are-part-of-exact-replication
  (is (not= :exact-replication
            (tmc/replication-type
             [(make-report "a" "sha256:A" :params "sha256:p1")
              (make-report "b" "sha256:A" :params "sha256:p2")
              (make-report "c" "sha256:A" :params "sha256:p1")]))))

(deftest theorem-consensus-is-bound-to-content-hash
  (let [positions [(make-pos-with-targets "a" :targets [{:kind :theorem :id :theorem/x :hash "sha256:h1" :status :reproduced}])
                   (make-pos-with-targets "b" :targets [{:kind :theorem :id :theorem/x :hash "sha256:h2" :status :reproduced}])
                   (make-pos-with-targets "c" :targets [{:kind :theorem :id :theorem/x :hash "sha256:h1" :status :reproduced}])]
        consensus (tmc/per-theorem-consensus positions)]
    (is (= #{[:theorem/x "sha256:h1"] [:theorem/x "sha256:h2"]}
           (set (keys consensus))))
    (is (= 2 (count (get-in consensus [[:theorem/x "sha256:h1"] :supporting-members]))))))

(deftest loaded-validation-recomputes-consensus
  (let [{:keys [review-round canonical-indices reports positions]} (valid-inputs)
        cert (tmc/build-certificate {:review-round review-round :canonical-indices canonical-indices
                                     :reports reports :positions positions})
        tampered (assoc-in cert [:other-consensus :publication :status] :contested)
        self-hashed (tmc/finalise-certificate! tampered)]
    (is (not (:valid? (tmc/validate-certificate self-hashed))))))


