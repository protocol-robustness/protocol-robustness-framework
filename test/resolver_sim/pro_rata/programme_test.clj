(ns resolver-sim.pro-rata.programme-test
  "SP-C: programme plan identity, exact-set reconciliation, receipt verifier,
   and the SP-A + SP-B invariant that execution settings cannot change any
   semantic programme field."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.evaluation :as pro-rata-evaluation]
            [resolver-sim.pro-rata.programme :as prog]
            [resolver-sim.pro-rata.exact-verifier :as exact-verifier]
            [resolver-sim.pro-rata.progress :as progress]))

(deftest protected-lineage-api-fails-closed
  (testing "lineage validity requires all four independently verified witnesses"
    (is (false? (application/protected-lineage-valid? {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/protected-lineage {})))))

(def a-plan
  {:programme/id :prog-1
   :request {:schema-version 1 :amount 10 :unit :u
             :participants [{:id :a :weight 5 :cap 2} {:id :b :weight 5}]
             :policy {:rounding :floor-with-largest-remainder
                      :cap-treatment :redistribute
                      :tie-break :input-order}}})

(defn- receipt-artifacts
  [plan result]
  {:plan plan
   :request (:request plan)
   :evaluation (:evaluation result)
   :evidence-artifact (:evidence-artifact result)})

(defn- rehash-receipt
  [receipt]
  (assoc receipt :receipt-hash
         (hc/domain-hash :programme-receipt-v1 (dissoc receipt :receipt-hash))))

(defn- substitute-projection-source
  "Create a self-consistently rehashed evaluation whose projection claims a
   different source while preserving the original request and allocation."
  [evaluation source-id]
  (let [projection (get-in evaluation [:projection :artifact/value])
        source (assoc (:source projection) :forensic/source-id source-id)
        source (assoc source :source-hash
                      (hc/hash-with-intent
                       {:hash/intent :projection-artifact}
                       {:intent-id (get-in projection [:intent :id])
                        :projection-definition-id (:projection-definition-id projection)
                        :source (dissoc source :source-hash)
                        :projection (:projection projection)}))
        projection (assoc projection :source source)
        projection (assoc projection :projection-hash
                          (hc/hash-with-intent {:hash/intent :projection-artifact}
                                               (dissoc projection :projection-hash)))
        evaluation (-> evaluation
                       (assoc-in [:projection :artifact/value] projection)
                       (assoc-in [:projection :artifact/hash] (:projection-hash projection))
                       (assoc-in [:result :artifact/value :projection-hash]
                                 (:projection-hash projection)))]
    (assoc-in evaluation [:result :artifact/hash]
              (hc/domain-hash :pro-rata-evaluation-v1
                              (get-in evaluation [:result :artifact/value])))))

(defn- rebuilt-receipt
  "Rebuild every hash-linked wrapper around an evaluation, as an attacker
   could, so verifier tests do not rely on stale evidence or receipt hashes."
  [plan evaluation]
  (let [validation (prog/programme-validation-result evaluation)
        evidence (prog/programme-evidence-artifact
                  {:programme/id (:programme/id plan)
                   :request (:request plan)
                   :evaluation evaluation
                   :validation-status (:status validation)
                   :validation-details (:details validation)})
        request-root (prog/allocation-request-root (:request plan))
        stage-ids {:allocation request-root
                   :validation request-root
                   :evidence (:evidence/id evidence)}
        stages {:allocation :completed
                :validation (:status validation)
                :evidence :completed
                :statement :not-requested
                :proof :not-requested
                :verification :not-requested
                :admission :not-requested}
        reconciliation (prog/reconcile-programme-ids
                        {:planned stage-ids
                         :executed stage-ids
                         :recorded stage-ids})]
    {:artifacts {:plan plan
                 :request (:request plan)
                 :evaluation evaluation
                 :evidence-artifact evidence}
     :receipt (prog/build-programme-receipt
               {:programme/id (:programme/id plan)
                :programme-plan-root
                (prog/programme-plan-root (prog/canonical-programme-plan plan))
                :request (:request plan)
                :evaluation evaluation
                :stages stages
                :stage-ids stage-ids
                :validation-status (:status validation)
                :validation-details (:details validation)
                :evidence-artifact evidence
                :reconciliation reconciliation})}))

;; ---------------------------------------------------------------------------
;; SP-C.1 — plan identity
;; ---------------------------------------------------------------------------

(deftest canonical-plan-freezes-only-the-identity-fields
  (let [canonical (prog/canonical-programme-plan a-plan)]
    (is (= "programme-plan.v1" (:schema-version canonical)))
    (is (= :prog-1 (:programme/id canonical)))
    (is (= [:a :b] (:semantic-ids canonical)) "semantic set/order frozen in request order")
    (is (= :requested (:allocation (:stages canonical))))
    (is (= :requested (:validation (:stages canonical))))
    (is (= :requested (:evidence (:stages canonical))))
    (is (= :not-requested (:proof (:stages canonical))) "optional stages default not-requested")))

(deftest canonical-plan-excludes-operational-state
  (testing "parallelism / progress / worker pools / host / paths / cancel atom are dropped"
    (let [op (assoc a-plan :parallelism 8 :worker-pools {:claimants 16}
                    :host "host-1" :paths ["/tmp/x"] :timestamps 12345
                    :cancel-atom (atom false) :on-progress (fn [_]))
          plain (prog/canonical-programme-plan a-plan)
          with-op (prog/canonical-programme-plan op)]
      (is (= plain with-op) "operational keys do not enter the canonical plan")
      (is (not (contains? plain :parallelism)))
      (is (not (contains? plain :paths))))))

(deftest plan-root-is-stable-across-operational-variants
  (let [roots (for [op [{}
                        {:on-progress (fn [_])}
                        {:progress-atom (atom nil)}
                        {:budget-permits 3}
                        {:parallelism 8 :worker-pools {:n 64} :paths ["/a" "/b"]}]]
                (prog/programme-plan-root
                 (prog/canonical-programme-plan (merge a-plan op))))]
    (is (apply = roots) "programme plan root is independent of execution settings")
    (is (string? (first roots)))
    (is (= 64 (count (first roots))))))

(deftest verify-programme-plan
  (is (= :passed (:status (prog/verify-programme-plan a-plan))))
  (is (string? (:programme-plan-root (prog/verify-programme-plan a-plan))))
  (let [no-id (dissoc a-plan :programme/id)]
    (is (= :invalid (:status (prog/verify-programme-plan no-id)))))
  (let [bad-stage (assoc a-plan :stages {:not-a-stage :requested})]
    (is (= :invalid (:status (prog/verify-programme-plan bad-stage)))))
  (testing "canonicalization is idempotent over the canonical plan"
    (is (= :passed (:status (prog/verify-programme-plan
                             (prog/canonical-programme-plan a-plan)))))))

;; ---------------------------------------------------------------------------
;; SP-C.2 — stage vocabulary + exact-set reconciliation
;; ---------------------------------------------------------------------------

(deftest stage-vocabulary-legitimacy-matrix
  (let [legal {:allocation #{:completed :failed :cancelled :error}
               :validation #{:passed :failed :unsupported :error}
               :evidence #{:completed :failed :cancelled :error}
               :statement #{:completed :not-requested :failed :cancelled :error}
               :proof #{:completed :not-requested :failed :cancelled :error}
               :verification #{:passed :not-requested :failed :unsupported :error}
               :admission #{:completed :not-requested :failed :cancelled :error}}]
    (doseq [[stage statuses] legal
            status statuses]
      (is (prog/valid-stage-status? stage status) (str stage " " status))
      (when (contains? #{:passed :completed} status)
        (is (not (prog/valid-stage-status? stage (case status :passed :completed :completed :passed)))
            (str stage " cross-terminal rejected"))))
    (is (not (prog/valid-stage-status? :validation :completed))
        "validation cannot be :completed (it is a verdict)")
    (is (not (prog/valid-stage-status? :allocation :passed))
        "allocation cannot be :passed (it is a completion)")))

(deftest reconcile-exact-sets
  (let [ok (prog/reconcile-programme-stages
            {:planned [:allocation :validation :evidence]
             :executed [:allocation :validation :evidence]
             :recorded [:allocation :validation :evidence]})]
    (is (= :passed (:status ok))))
  (let [missing (prog/reconcile-programme-stages
                 {:planned [:allocation :validation :evidence]
                  :executed [:allocation :evidence]
                  :recorded [:allocation :validation :evidence]})]
    (is (= :failed (:status missing)))
    (is (= [:validation] (get-in missing [:missing :from-execution]))))
  (let [unexpected (prog/reconcile-programme-stages
                    {:planned [:allocation :evidence]
                     :executed [:allocation :validation :evidence]
                     :recorded [:allocation :evidence]})]
    (is (= :failed (:status unexpected)))
    (is (= [:validation] (get-in unexpected [:unexpected :in-execution]))))
  (let [duplicate (prog/reconcile-programme-stages
                   {:planned [:allocation :evidence]
                    :executed [:allocation :allocation :evidence]
                    :recorded [:allocation :evidence]})]
    (is (= :failed (:status duplicate)))
    (is (= [:allocation] (:duplicates duplicate)))))

(deftest reconcile-programme-ids
  (let [ok (prog/reconcile-programme-ids
            {:planned {:allocation :r1 :validation :r1 :evidence :e1}
             :executed {:allocation :r1 :validation :r1 :evidence :e1}
             :recorded {:allocation :r1 :validation :r1 :evidence :e1}})]
    (is (= :passed (:status ok))))
  (let [deviated (prog/reconcile-programme-ids
                  {:planned {:allocation :r1 :evidence :e1}
                   :executed {:allocation :r1 :evidence :e2}
                   :recorded {:allocation :r1 :evidence :e1}})]
    (is (= :failed (:status deviated)))
    (is (seq (:stage-id-mismatches deviated)))))

;; ---------------------------------------------------------------------------
;; SP-C.3 + SP-C.4 — runner receipts are independently verifiable
;; ---------------------------------------------------------------------------

(defn- semantic-signature
  "Stable, execution-setting-independent programme fields."
  [result]
  (let [r (:receipt result)]
    (select-keys r [:request-root :result-root :validation-status
                    :validation-details :evidence-root :evidence-id
                    :programme-plan-root])))

(deftest run-programme-produces-a-verifiable-receipt
  (let [result (prog/run-programme a-plan)]
    (is (= :passed (:status (:verification result)))
        "the receipt verifier independently reconstructs the runner's claim")
    (is (= :passed (:status (:reconciliation result)))
        "planned = executed = recorded ids")
    (is (= :completed (:allocation (:stages result))))
    (is (= :passed (:validation (:stages result))))
    (is (= :completed (:evidence (:stages result))))
    (is (= :not-requested (:proof (:stages result))))))

(deftest tampered-receipt-fails-verification
  (let [result (prog/run-programme a-plan)
        receipt (:receipt result)
        artifacts (receipt-artifacts a-plan result)]
    (is (= :passed (:status (prog/verify-programme-receipt artifacts receipt))))
    (doseq [field [:result-root :validation-status :evidence-root]]
      (let [tampered (assoc receipt field
                            (if (= field :validation-status) :failed (str "x" field)))
            v (prog/verify-programme-receipt artifacts tampered)]
        (is (= :failed (:status v)) (str field))
        (is (some #(= field (:field %)) (:mismatches v)) (str field " mismatch reported"))))))

(deftest receipt-verification-binds-plan-stage-identities-and-hash
  (let [result (prog/run-programme a-plan)
        receipt (:receipt result)
        artifacts (receipt-artifacts a-plan result)
        bad-hash (assoc receipt :receipt-hash (apply str (repeat 64 "0")))
        changed-plan-root (rehash-receipt
                           (assoc receipt :programme-plan-root (apply str (repeat 64 "1"))))
        changed-stage-ids (rehash-receipt
                           (assoc-in receipt [:stage-ids :allocation] "substituted"))
        claimed-proof (rehash-receipt
                       (-> receipt
                           (assoc-in [:stages :proof] :completed)
                           (assoc-in [:stages :admission] :completed)))]
    (is (= :passed (:status (prog/verify-programme-receipt artifacts receipt))))
    (doseq [[candidate expected-field]
            [[bad-hash :receipt-hash]
             [changed-plan-root :programme-plan-root]
             [changed-stage-ids :stage-ids]
             [claimed-proof :stages]]]
      (let [verification (prog/verify-programme-receipt artifacts candidate)]
        (is (= :failed (:status verification)))
        (is (some #(= expected-field (:field %)) (:mismatches verification)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (prog/run-programme (assoc-in a-plan [:stages :proof] :requested)))
        "the current runner rejects unimplemented optional stages before execution")))

(deftest receipt-verification-requires-plan-request-to-match-execution-request
  (let [result (prog/run-programme a-plan)
        receipt (:receipt result)
        artifacts (assoc (receipt-artifacts a-plan result)
                         :plan (assoc-in a-plan [:request :amount] 11))
        verification (prog/verify-programme-receipt artifacts receipt)]
    (is (= :failed (:status verification)))
    (is (some #(= :plan/request-root (:field %)) (:mismatches verification)))))

(deftest receipt-verification-binds-evaluation-to-plan-request
  (let [result (prog/run-programme a-plan)
        receipt (:receipt result)
        changed-request (assoc (:request a-plan) :amount 11)
        changed-evaluation (pro-rata-evaluation/evaluate-pro-rata-allocation changed-request)
        changed-verdict (prog/programme-validation-result changed-evaluation)
        changed-evidence (prog/programme-evidence-artifact
                          {:programme/id (:programme/id a-plan)
                           :request (:request a-plan)
                           :evaluation changed-evaluation
                           :validation-status (:status changed-verdict)
                           :validation-details (:details changed-verdict)})
        artifacts (assoc (receipt-artifacts a-plan result)
                         :evaluation changed-evaluation
                         :evidence-artifact changed-evidence)
        verification (prog/verify-programme-receipt artifacts receipt)]
    (is (= :failed (:status verification)))
    (is (some #(= :evaluation/request-root (:field %)) (:mismatches verification)))))

(deftest receipt-verification-reconstructs-projection-from-original-request
  (let [result (prog/run-programme a-plan)
        original-evaluation (:evaluation result)
        substituted-evaluation (substitute-projection-source
                                original-evaluation "attacker-selected-source")
        rebuilt (rebuilt-receipt a-plan substituted-evaluation)
        verification (prog/verify-programme-receipt
                      (:artifacts rebuilt) (:receipt rebuilt))
        altered-allocation (assoc (:allocation original-evaluation)
                                  :forensic/forged true)
        altered-result-evaluation
        (-> original-evaluation
            (assoc :allocation altered-allocation)
            (assoc-in [:result :artifact/value :allocation] altered-allocation))
        altered-result-evaluation
        (assoc-in altered-result-evaluation [:result :artifact/hash]
                  (hc/domain-hash :pro-rata-evaluation-v1
                                  (get-in altered-result-evaluation
                                          [:result :artifact/value])))
        altered-result (rebuilt-receipt a-plan altered-result-evaluation)
        altered-result-verification
        (prog/verify-programme-receipt (:artifacts altered-result)
                                       (:receipt altered-result))]
    (is (= :passed (:status (:verification result))) "original receipt remains valid")
    (is (= (:allocation original-evaluation) (:allocation substituted-evaluation))
        "the substitution does not change the independently checked allocation")
    (is (not= (get-in original-evaluation [:projection :artifact/hash])
              (get-in substituted-evaluation [:projection :artifact/hash]))
        "the attacker supplies a different, internally rehashed projection")
    (is (= :failed (:status verification))
        "rehashed evaluation, evidence, and receipt cannot authenticate a substituted projection")
    (is (some #(= :evaluation/projection (:field %)) (:mismatches verification)))
    (is (= :failed (:status altered-result-verification))
        "a rehashed result-body/allocation substitution also fails")
    (is (some #(= :evaluation/result (:field %))
              (:mismatches altered-result-verification)))))

(deftest each-derived-aggregate-verdict-field-is-the-verifier-derives
  "The receipt's aggregate verdict is independently re-derived, so a tampered
   aggregate cannot pass even when the constituent roots are unchanged."
  (let [result (prog/run-programme a-plan)
        receipt (:receipt result)
        artifacts (receipt-artifacts a-plan result)]
    (is (= :passed (:status (prog/verify-programme-receipt artifacts receipt)))
        "baseline receipt verifies, including all derived aggregate fields")
    (let [deltas [{:field :semantic/status :value :fail}
                  {:field :programme/status :value :failed}
                  {:field :stages :value (assoc (:stages receipt) :validation :failed)}
                  {:field :summary :value (assoc (:summary receipt) :failed 1)}
                  {:field :exact-set-complete :value false}]]
      (doseq [{:keys [field value]} deltas]
        (let [tampered (assoc receipt field value)
              v (prog/verify-programme-receipt artifacts tampered)]
          (is (= :failed (:status v)) (str field " tamper rejected"))
          (is (some #(= field (:field %)) (:mismatches v))
              (str field " mismatch reported")))))))

(deftest unsupported-validation-propagates-into-programme-semantics
  "An exact-verifier :unsupported verdict must survive as :unsupported in the
   aggregate verdict — never flattened to :failed nor treated as :pass."
  (let [stage-statuses {:allocation :completed :validation :unsupported
                        :evidence :completed :statement :not-requested
                        :proof :not-requested :verification :not-requested
                        :admission :not-requested}
        reconciliation (prog/reconcile-programme-ids
                        {:planned {:allocation :r1 :validation :r1 :evidence :e1}
                         :executed {:allocation :r1 :validation :r1 :evidence :e1}
                         :recorded {:allocation :r1 :validation :r1 :evidence :e1}})
        verdict (prog/derive-programme-verdict
                 {:stage-statuses stage-statuses :reconciliation reconciliation})]
    (is (= :unsupported (:semantic/status verdict)) "semantic/status preserves :unsupported")
    (is (= 1 (:unsupported (:summary verdict))))
    (is (= :passed (:status (:reconciliation reconciliation))))
    (is (not= :pass (:semantic/status verdict)) "an unsupported verdict is not a pass")))

(deftest unexecuted-optional-stages-prevent-programme-completion
  (let [base {:allocation :completed
              :validation :passed
              :evidence :completed
              :statement :not-requested
              :proof :not-requested
              :verification :not-requested
              :admission :not-requested}
        reconciliation {:status :passed}
        requested (prog/derive-programme-verdict
                   {:stage-statuses (assoc base :proof :requested)
                    :reconciliation reconciliation})
        unsubstantiated-claim (prog/derive-programme-verdict
                               {:stage-statuses (assoc base :proof :completed)
                                :reconciliation reconciliation})]
    (doseq [verdict [requested unsubstantiated-claim]]
      (is (= :incomplete (:semantic/status verdict)))
      (is (= :executing (:programme/status verdict))))))

;; ---------------------------------------------------------------------------
;; The SP-C invariant: programme validation = exact verifier result, and
;; execution settings cannot change any semantic programme field.
;; ---------------------------------------------------------------------------

(deftest programme-validation-equals-exact-verifier-result
  (let [evaluation (:evaluation (prog/run-programme a-plan))
        verdict (prog/programme-validation-result evaluation)
        n (get-in evaluation [:result :artifact/value :canonical-request])
        policy (:policy n)
        independent (exact-verifier/verify-weighted-proportionality
                     {:amount (:amount n)
                      :items (:participants n)
                      :rounding (:rounding policy)
                      :cap-treatment (:cap-treatment policy)
                      :ordering-policy (:tie-break policy)}
                     (:allocation evaluation))]
    (is (= (:status independent) (:status verdict)))
    (is (= (:details independent) (:details verdict))
        "programme validation result equals the exact verifier result")))

(deftest execution-settings-cannot-change-programme-semantics
  (let [variants
        (for [opts [{}
                    {:on-progress (fn [_])}
                    {:progress-atom (atom (progress/initial-progress))}
                    {:on-progress (fn [_]) :progress-atom (atom (progress/initial-progress))}
                    {:budget-permits 2}
                    {:budget-permits 2 :on-progress (fn [_])
                     :progress-atom (atom (progress/initial-progress))}]]
          (apply prog/run-programme a-plan (mapcat identity opts)))
        sigs (map semantic-signature variants)]
    (is (apply = sigs)
        "request-root/result-root/validation status+details/evidence-root/receipt fields identical across settings")
    (doseq [s sigs]
      (is (= :passed (:validation-status s))))))

(deftest progress-events-and-atom-are-operational
  (let [events (atom [])
        atom-progress (atom (progress/initial-progress {:programme/id :prog-1}))]
    (prog/run-programme a-plan :on-progress (fn [e] (swap! events conj e))
                        :progress-atom atom-progress)
    (is (some #(= :allocation-completed (:event %)) @events)
        "typed progress events are emitted")
    (is (= :completed (:status @atom-progress))
        "the caller-owned progress atom converges to a terminal state")
    (is (= :completed (:phase @atom-progress)))))
