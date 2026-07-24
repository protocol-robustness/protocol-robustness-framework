(ns resolver-sim.benchmark.review.three-member-certificate-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]))

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
