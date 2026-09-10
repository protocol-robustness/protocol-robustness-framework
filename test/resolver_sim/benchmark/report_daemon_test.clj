(ns resolver-sim.benchmark.report-daemon-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.public-result-admission :as pra]
            [resolver-sim.benchmark.public-results-epoch :as epoch]
            [resolver-sim.benchmark.report-daemon :as sut]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.allocation :as allocation]))

(def ^:private test-priv-key "test-keys/test-researcher-signing-key")
(def ^:private test-pub-key "test-keys/test-researcher-signing-key.pub")

(defn- root [c]
  (str "sha256:" (apply str (repeat 64 (first c)))))

(defn- manifest [& {:keys [results generated-case-set-root]
                    :or {results {:conservation :pass}
                         generated-case-set-root (root "6")}}]
  (outcome/build-manifest
   {:benchmark/content-root (root "c")
    :benchmark/model-root (root "1")
    :benchmark/evaluation-policy-root (root "2")
    :execution/model-instance-root (root "3")
    :execution/plan-root (root "4")
    :execution/parameter-domain-root (root "5")
    :execution/sampling-policy-root (root "7")
    :execution/generated-case-set-root generated-case-set-root
    :results/operational results}))

(defn- signed-report [m researcher run]
  (:report
   (rrr/sign-report!
    (rrr/build-report
     {:outcome-manifest m
      :researcher-id researcher
      :runner-info {:runner/id (str "runner-" researcher)
                    :source-tree-hash (root "8")
                    :distribution-hash (root "9")
                    :environment-hash (root "a")}
      :evidence-refs {:evidence-dag-root (root "b")
                      :event-evidence-root (root "c")
                      :execution-log-root (root "d")}
      :run-id run})
    test-priv-key)))

(def ^:private anonymous-ids #{"anonymous" "anonymous-lab" "anonymous-visitor"})

(defn- participation-for [researcher]
  (if (contains? anonymous-ids researcher)
    {:presentation :anonymous :identity-assurance :unresolved}
    {:presentation :declared :identity-assurance :unresolved}))

;; ── Pro-rata allocation fixtures ────────────────────────────────────────────

(defn- allocation-request []
  {:schema-version "pro-rata-allocation-request.v1"
   :mechanism/version 1
   :allocation/id [:lab/pro-rata 100 40 30 30]
   :available 100
   :rows [{:row/id :alice :obligation/id :claim :requested 40 :weight 40}
          {:row/id :bob :obligation/id :claim :requested 30 :weight 30}
          {:row/id :carol :obligation/id :claim :requested 30 :weight 30}]
   :rounding-policy :largest-remainder
   :tie-break-policy :canonical-row-id
   :redistribution-policy :unallocated})

(defn- pro-rata-content [m report & {:keys [request result]}]
  (let [request (or request (allocation-request))
        result (or result (allocation/allocate request))]
    {:report report
     :manifest m
     :public-key (slurp test-pub-key)
     :allocation/request request
     :allocation/result result}))

(defn- pro-rata-interaction
  ([id researcher run] (pro-rata-interaction id researcher run {}))
  ([id researcher run {:keys [request result]}]
   (let [m (manifest)
         content (pro-rata-content m (signed-report m researcher run)
                                   :request request :result result)]
     {:interaction/id id
      :interaction/kind :pro-rata/allocation
      :interaction/payload-root (sut/interaction-content-root content)
      :interaction/participation (participation-for researcher)
      :interaction/content content})))

;; ── Research observation fixtures ───────────────────────────────────────────

(defn- research-definition []
  (research/freeze-research
   {:artifact/schema "research-source.v1"
    :research/id :study/liquidity
    :research/title "Liquidity coverage"
    :research/question "Does observed coverage meet the requirement?"
    :research/vary {:study/available [100 200]}
    :research/measures
    [{:measure/id :measure/coverage
      :measure/kind :requirement-margin.v1
      :measure/domain {:kind :integer :unit :unit/token}
      :measure/requirement {:value 50 :unit :unit/token}
      :measure/satisfying-direction :at-least
      :measure/observation {:observation/id :obs/coverage
                            :observation/domain {:kind :integer
                                                 :unit :unit/token}}}]}))

(defn- research-observations []
  {:generated/case-0 {:measure/coverage 60}
   :generated/case-1 {:measure/coverage 200}})

(defn- research-margins []
  {[:generated/case-0 :measure/coverage] 10
   [:generated/case-1 :measure/coverage] 150})

(defn- research-content [m report & {:keys [observations margins]}]
  {:report report
   :manifest m
   :public-key (slurp test-pub-key)
   :definition (research-definition)
   :observations (or observations (research-observations))
   :requirement-margins (or margins (research-margins))})

(defn- research-interaction
  ([id researcher run] (research-interaction id researcher run {}))
  ([id researcher run {:keys [observations margins]}]
   (let [m (manifest)
         content (research-content m (signed-report m researcher run)
                                   :observations observations :margins margins)]
     {:interaction/id id
      :interaction/kind :research/observation
      :interaction/payload-root (sut/interaction-content-root content)
      :interaction/participation (participation-for researcher)
      :interaction/content content})))

(defn- with-content [interaction content]
  (assoc interaction
         :interaction/content content
         :interaction/payload-root (sut/interaction-content-root content)))

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. Valid anonymous admission
;; ═══════════════════════════════════════════════════════════════════════════

(deftest anonymous-pro-rata-interaction-admits
  (let [store (sut/new-report-interaction-store)
        interaction (pro-rata-interaction "i1" "anonymous" "run1")
        result (sut/process-one! store interaction)]
    (is (= :admitted (:status result)))
    (let [record (:processing result)
          admission (:processing/admission record)]
      (is (hash-ref/valid-sha256-ref? (:processing/admission-root record)))
      (is (hash-ref/valid-sha256-ref? (:processing/root record)))
      (is (= {:presentation :anonymous :identity-assurance :unresolved}
             (:admission/participation admission)))
      (is (:valid? (pra/verify admission
                               (:report (:interaction/content interaction))
                               (:manifest (:interaction/content interaction))
                               (:public-key (:interaction/content interaction)))))
      (is (= 2 (count (sut/head-chain* store)))
          "one admission appended to the genesis head"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. Declared-but-unresolved admission
;; ═══════════════════════════════════════════════════════════════════════════

(deftest declared-research-interaction-admits-but-not-authenticated
  (let [store (sut/new-report-interaction-store)
        interaction (research-interaction "i2" "r1" "run2")
        result (sut/process-one! store interaction)]
    (is (= :admitted (:status result)))
    (let [admission (:processing/admission (:processing result))]
      (is (= {:presentation :declared :identity-assurance :unresolved}
             (:admission/participation admission))
          "a declared name is never recorded as identity assurance"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3. Every fractional injection point is rejected (never coerced)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest pro-rata-fractional-injection-rejected
  (let [base-request (allocation-request)
        base-result (allocation/allocate base-request)
        injections
        [["request/available" (assoc base-request :available 99.5) base-result]
         ["request/row-requested" (assoc-in base-request [:rows 0 :requested] 40.5) base-result]
         ["request/row-weight" (assoc-in base-request [:rows 0 :weight] 40.5) base-result]
         ["request/row-cap" (assoc-in base-request [:rows 0 :cap] 40.5) base-result]
         ["result/available" base-request (assoc base-result :available 99.5)]
         ["result/allocated-total" base-request (assoc base-result :allocated-total 99.5)]
         ["result/unallocated-residual" base-request (assoc base-result :unallocated-residual 0.5)]
         ["result/row-requested" base-request (assoc-in base-result [:rows 0 :requested] 40.5)]
         ["result/row-weight" base-request (assoc-in base-result [:rows 0 :weight] 40.5)]
         ["result/row-effective-cap" base-request (assoc-in base-result [:rows 0 :effective-cap] 40.5)]
         ["result/row-allocated" base-request (assoc-in base-result [:rows 0 :allocated] 40.5)]
         ["result/row-unmet" base-request (assoc-in base-result [:rows 0 :unmet] 0.5)]]]
    (doseq [[label request result] injections]
      (let [store (sut/new-report-interaction-store)
            interaction (pro-rata-interaction (str "i-" label) "anonymous"
                                              (str "run-" label)
                                              {:request request :result result})
            outcome (sut/process-one! store interaction)]
        (is (= :rejected (:status outcome)) label)
        (is (= :integer-domain (get-in outcome [:processing :processing/stage])) label)
        (is (= :pro-rata/fractional-field
               (get-in outcome [:processing :processing/reason])) label)
        (is (some? (get-in outcome [:processing :processing/root]))
            "a fractional injection still yields a rooted rejection")))))

(deftest research-fractional-observation-rejected
  (let [store (sut/new-report-interaction-store)
        observations {:generated/case-0 {:measure/coverage 60.5}
                      :generated/case-1 {:measure/coverage 200}}
        interaction (research-interaction "i3" "anonymous" "run3"
                                          {:observations observations})
        outcome (sut/process-one! store interaction)]
    (is (= :rejected (:status outcome)))
    (is (= :research/invalid-observation
           (get-in outcome [:processing :processing/reason])))
    (is (= :integer-domain (get-in outcome [:processing :processing/stage])))))

(deftest research-missing-observation-rejected
  (let [store (sut/new-report-interaction-store)
        observations {:generated/case-0 {:measure/coverage 60}}
        interaction (research-interaction "i4" "anonymous" "run4"
                                          {:observations observations})
        outcome (sut/process-one! store interaction)]
    (is (= :rejected (:status outcome)))
    (is (= :research/invalid-observation
           (get-in outcome [:processing :processing/reason])))))

(deftest research-margin-mismatch-rejected
  (let [store (sut/new-report-interaction-store)
        interaction (research-interaction "i5" "anonymous" "run5"
                                          {:margins {[:generated/case-0 :measure/coverage] 9
                                                     [:generated/case-1 :measure/coverage] 150}})
        outcome (sut/process-one! store interaction)]
    (is (= :rejected (:status outcome)))
    (is (= :research/margin-mismatch
           (get-in outcome [:processing :processing/reason])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 4. Report / hash / signature / manifest / outcome mismatches
;; ═══════════════════════════════════════════════════════════════════════════

(deftest report-and-manifest-mismatches-reject
  (let [m (manifest)
        report (signed-report m "r1" "run1")
        base (pro-rata-content m report)]
    (doseq [[label content expected]
            [["tampered-researcher-id"
              (assoc-in base [:report :researcher/id] "evil")
              :invalid-report-signature]
             ["tampered-report-hash"
              (assoc-in base [:report :researcher-run-report/hash] (root "f"))
              :invalid-report-signature]
             ["tampered-signature"
              (assoc-in base [:report :researcher/signature :value] "tampered")
              :invalid-report-signature]
             ["missing-report-hash"
              (update base :report dissoc :researcher-run-report/hash)
              :missing-report-hash]
             ["different-manifest"
              (pro-rata-content (manifest :generated-case-set-root (root "f")) report)
              :report-manifest-mismatch]
             ["wrong-supplied-key"
              (assoc base :public-key "ssh-ed25519 AAAA")
              :invalid-report-signature]]]
      (let [store (sut/new-report-interaction-store)
            interaction (with-content
                          (pro-rata-interaction (str "i-" label) "r1" (str "run-" label))
                          content)
            outcome (sut/process-one! store interaction)]
        (is (= :rejected (:status outcome)) label)
        (is (= :researcher-run (get-in outcome [:processing :processing/stage])) label)
        (is (= expected (get-in outcome [:processing :processing/reason])) label)))))

(deftest outcome-hash-mismatch-rejects
  (let [m (manifest)
        other (manifest :results {:conservation :pass :quota :fail})
        report (signed-report m "r1" "run1")
        content (pro-rata-content other report)
        store (sut/new-report-interaction-store)
        interaction (with-content
                      (pro-rata-interaction "i-outcome" "r1" "run-outcome")
                      content)]
    (with-redefs [rrr/verify-against-manifest (fn [_ _] {:valid? true :mismatches []})]
      (let [outcome (sut/process-one! store interaction)]
        (is (= :rejected (:status outcome)))
        (is (= :outcome-hash-mismatch
               (get-in outcome [:processing :processing/reason])))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 5–6. Duplicate submission and crash/retry idempotency
;; ═══════════════════════════════════════════════════════════════════════════

(deftest duplicate-submission-is-idempotent
  (let [store (sut/new-report-interaction-store)
        interaction (pro-rata-interaction "i1" "anonymous" "run1")
        first (sut/process-one! store interaction)
        second (sut/process-one! store interaction)]
    (is (= :admitted (:status first)))
    (is (= :idempotent (:status second)))
    (is (= (:processing/admission-root (:processing first))
           (:processing/admission-root (:processing second))))
    (is (= (:processing/root (:processing first))
           (:processing/root (:processing second)))
        "one interaction has exactly one deterministic processing result")))

(deftest retry-after-admission-creation-does-not-duplicate
  (let [store (sut/new-report-interaction-store)
        interaction (pro-rata-interaction "i1" "anonymous" "run1")
        first (sut/process-one! store interaction)
        ;; a crashed-then-restarted daemon reprocesses the same pending
        ;; interaction with identical content
        retried (sut/process-one! store interaction)]
    (is (= :admitted (:status first)))
    (is (= :idempotent (:status retried)))
    (is (= 2 (count (sut/head-chain* store)))
        "the admission is appended exactly once")
    (is (= 1 (count (:epoch/members (sut/current-epoch store)))))))

(deftest store-record-is-idempotent-under-retry
  (let [store (sut/new-report-interaction-store)
        interaction (pro-rata-interaction "i1" "anonymous" "run1")
        result (sut/process-interaction interaction)
        envelope (:envelope (:ok? (sut/build-envelope interaction)))
        record (sut/build-processing-record envelope result)
        a (sut/record-processing!* store "i1" record)
        b (sut/record-processing!* store "i1" record)]
    (is (= :recorded (:status a)))
    (is (= :recorded (:status b)))
    (is (= 2 (count (sut/head-chain* store)))
        "repeated recording of the same admission never double-appends")
    (is (= 1 (count (:epoch/members (sut/current-epoch store)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 7. Same interaction id with changed payload fails closed
;; ═══════════════════════════════════════════════════════════════════════════

(deftest same-interaction-id-with-changed-payload-fails-closed
  (let [store (sut/new-report-interaction-store)
        a (pro-rata-interaction "i1" "anonymous" "run1")
        b (pro-rata-interaction "i1" "anonymous" "run2")
        first (sut/process-one! store a)]
    (is (= :admitted (:status first)))
    (is (hash-ref/valid-sha256-ref?
         (get-in first [:processing :processing/admission-root])))
    (let [outcome (sut/process-one! store b)]
      (is (= :payload-conflict (:status outcome))
          "a different payload under the same logical interaction cannot
          produce a second admission"))))

(deftest declared-payload-root-mismatch-fails-closed
  (let [store (sut/new-report-interaction-store)
        interaction (pro-rata-interaction "i1" "anonymous" "run1")
        tampered (assoc interaction :interaction/payload-root (root "f"))
        outcome (sut/process-one! store interaction)]
    (is (= :admitted (:status outcome)))
    (is (= :malformed (:status (sut/process-one! store tampered))))
    (is (= :payload-root-mismatch (:reason (sut/process-one! store tampered))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 8. Epoch membership is admission-root based and deduplicated
;; ═══════════════════════════════════════════════════════════════════════════

(deftest epoch-collector-deduplicates-admissions
  (let [store (sut/new-report-interaction-store)
        a (pro-rata-interaction "i1" "anonymous" "run1")
        b (research-interaction "i2" "r1" "run2")]
    (sut/process-one! store a)
    (sut/process-one! store b)
    (sut/process-one! store a)   ;; retry — must not double-admit
    (let [e (sut/current-epoch store)]
      (is (= 2 (count (:epoch/members e))))
      (is (= {:anonymous 1 :declared-unresolved 1 :authenticated 0}
             (:epoch/participation e)))
      (is (:valid? (epoch/verify-epoch e
                                       (sut/head-chain* store)
                                       (sut/admissions* store)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 9. Attempted identity-assurance escalation fails closed
;; ═══════════════════════════════════════════════════════════════════════════

(deftest identity-assurance-escalation-fails-closed
  (let [store (sut/new-report-interaction-store)
        interaction (assoc (pro-rata-interaction "i1" "r1" "run1")
                           :interaction/participation
                           {:presentation :declared
                            :identity-assurance :authenticated
                            :identity-binding-root (root "f")})
        outcome (sut/process-one! store interaction)]
    (is (= :rejected (:status outcome)))
    (is (= :participation (get-in outcome [:processing :processing/stage])))
    (is (= :identity-assurance-escalation
           (get-in outcome [:processing :processing/reason])))
    (is (some? (get-in outcome [:processing :processing/root]))
        "escalation is a rooted rejection, never an admission")))

(deftest misdeclared-participation-rejected
  (let [store (sut/new-report-interaction-store)
        interaction (assoc (pro-rata-interaction "i1" "r1" "run1")
                           :interaction/participation
                           {:presentation :anonymous
                            :identity-assurance :unresolved})
        outcome (sut/process-one! store interaction)]
    (is (= :rejected (:status outcome)))
    (is (= :participation-mismatch
           (get-in outcome [:processing :processing/reason])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Lifecycle: bounded pull polling with fair cursor
;; ═══════════════════════════════════════════════════════════════════════════

(deftest run-once-is-bounded-and-processes
  (let [store (sut/new-report-interaction-store)]
    (sut/enqueue! store (pro-rata-interaction "i1" "anonymous" "run1"))
    (sut/enqueue! store (research-interaction "i2" "r1" "run2"))
    (let [first (sut/run-once! store {:batch-size 1})
          second (sut/run-once! store {:batch-size 1 :after-id (:next-after-id first)})]
      (is (= 1 (:attempted first)))
      (is (= {:success 1} (:counts first)))
      (is (= 1 (:attempted second)))
      (is (= {:success 1} (:counts second)))
      (is (= 2 (count (:epoch/members (sut/current-epoch store))))))))

(deftest enqueue-requires-stable-id
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/enqueue! (sut/new-report-interaction-store) {:interaction/kind :pro-rata/allocation}))))

(deftest settings-must-be-positive-and-bounded
  (doseq [value [0 -1 nil 1.5 (inc (bigint Long/MAX_VALUE))]]
    (is (thrown? IllegalArgumentException (sut/run-once! nil {:batch-size value})))
    (doseq [setting [:batch-size :poll-interval-ms :stop-timeout-ms]]
      (is (thrown? IllegalArgumentException (sut/start! nil {setting value}))))))