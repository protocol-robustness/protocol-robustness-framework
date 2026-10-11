(ns resolver-sim.research.semantic-accounting-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.accounting-semantics :as semantics]
            [resolver-sim.research.coordinate-interaction :as interaction]))

(defn fake-root [ch] (str "sha256:" (apply str (repeat 64 ch))))

(def roots
  {:claim (fake-root \1) :claimant (fake-root \2) :target-a (fake-root \3)
   :target-b (fake-root \4) :cutpoint (fake-root \5) :definition (fake-root \6)
   :policy (fake-root \7) :subject-a (fake-root \8) :subject-b (fake-root \9)
   :dependency (fake-root \a) :environment (fake-root \b)
   :implementation-a (fake-root \c) :implementation-b (fake-root \d)
   :runner-a (fake-root \e) :runner-b (fake-root \f)
   :run-a (fake-root \0) :run-b (fake-root \1) :analysis (fake-root \2)
   :axis-x (fake-root \3) :axis-y (fake-root \4)})

(deftest claim-to-is-directional-and-target-sensitive
  (let [base (semantics/build-claim-to
              {:claim-root (:claim roots) :claimant-root (:claimant roots)
               :target-root (:target-a roots) :target-category :claim-target/account})
        different-target (semantics/build-claim-to
                          {:claim-root (:claim roots) :claimant-root (:claimant roots)
                           :target-root (:target-b roots)
                           :target-category :claim-target/account})
        reversed (semantics/build-claim-to
                  {:claim-root (:claim roots) :claimant-root (:target-a roots)
                   :target-root (:claimant roots) :target-category :claim-target/account})]
    (is (semantics/valid-claim-to? base))
    (is (= :claimant-to-target (:relationship/direction base)))
    (is (not= (:claim-to/root base) (:claim-to/root different-target)))
    (is (not= (:claim-to/root base) (:claim-to/root reversed)))
    (is (= :does-not-establish-acceptance-or-authority
           (:relationship/meaning base)))))

(deftest possession-dimensions-do-not-imply-authority
  (let [position (semantics/build-possession-position
                  {:subject-root (:runner-a roots) :subject-kind :actor
                   :resource-root (:target-a roots) :cutpoint-root (:cutpoint roots)
                   :source-roots [(:claim roots)]
                   :availability true :possession true :readability false
                   :usability false :authority false})]
    (is (true? (get-in position [:possession/states :possession])))
    (is (false? (get-in position [:possession/states :authority])))
    (is (= :observation-not-authority (:position/meaning position)))))

(defn accounting-fixture []
  (let [claim-a (semantics/build-claim-to
                 {:claim-root (:claim roots) :claimant-root (:claimant roots)
                  :target-root (:target-a roots) :target-category :claim-target/account})
        claim-b (semantics/build-claim-to
                 {:claim-root (fake-root \a) :claimant-root (:claimant roots)
                  :target-root (:target-b roots) :target-category :claim-target/account})
        contract (accounting/build-reduction-contract)
        sources [{:source/root (fake-root \6) :accounting/category :accounting/debit
                  :accounting/value 11 :accounting/subject-root (:subject-a roots)
                  :claim-to/root (:claim-to/root claim-a)}
                 {:source/root (fake-root \7) :accounting/category :accounting/debit
                  :accounting/value -3 :accounting/subject-root (:subject-a roots)
                  :claim-to/root (:claim-to/root claim-a)}
                 {:source/root (fake-root \8) :accounting/category :accounting/credit
                  :accounting/value 8 :accounting/subject-root (:subject-b roots)
                  :claim-to/root (:claim-to/root claim-b)}]
        request (accounting/build-request
                 {:accounting-domain :accounting-domain/claim-basis
                  :cutpoint-root (:cutpoint roots)
                  :accounting-definition-root (:definition roots)
                  :accounting-policy-root (:policy roots)
                  :reduction-contract contract :sources sources})
        requirements {:dependency-set-root (:dependency roots)
                      :execution-environment-root (:environment roots)
                      :allowed-implementation-roots
                      [(:implementation-a roots) (:implementation-b roots)
                       (str "sha256:" (format "%064x" 23))]
                      :required-capabilities [:capability/accounting]
                      :privacy-requirement :public}
        plan (accounting/build-partition-plan
              request [[(fake-root \6) (fake-root \7)] [(fake-root \8)]] requirements)]
    {:contract contract :request request :plan plan}))

(defn accounting-partial [request unit implementation runner run-root]
  (accounting/build-partial-result
   {:request request :work-unit unit
    :values (reduce (fn [m source]
                      (if (contains? (set (:partition/source-roots unit))
                                     (:source/root source))
                        (update m (:accounting/category source) (fnil + 0)
                                (:accounting/value source)) m))
                    (sorted-map) (:accounting/sources request))
    :run-assurance-root run-root :implementation-root implementation
    :runner-reference runner :resolved-dependency-set-root (:dependency roots)
    :execution-environment-root (:environment roots)}))

(defn completed-ingestion [request plan implementation-a implementation-b]
  (let [[u0 u1] (:partition/work-units plan)]
    (-> {}
        (accounting/ingest-partial
         (accounting-partial request u0 implementation-a (:runner-a roots) (:run-a roots)))
        (accounting/ingest-partial
         (accounting-partial request u1 implementation-b (:runner-b roots) (:run-b roots))))))

(deftest partitioned-accounting-is-serial-equivalent-and-order-independent
  (let [{:keys [contract request plan]} (accounting-fixture)
        [u0 u1] (:partition/work-units plan)
        p0 (accounting-partial request u0 (:implementation-a roots) (:runner-a roots) (:run-a roots))
        p1 (accounting-partial request u1 (:implementation-b roots) (:runner-b roots) (:run-b roots))
        forward (accounting/reconcile request contract plan
                                      (-> {} (accounting/ingest-partial p0)
                                          (accounting/ingest-partial p1)))
        reverse (accounting/reconcile request contract plan
                                      (-> {} (accounting/ingest-partial p1)
                                          (accounting/ingest-partial p0)))]
    (is (= {:accounting/credit 8 :accounting/debit 8}
           (accounting/serial-values request)))
    (is (= (accounting/serial-values request) (:accounting/values forward)))
    (is (= forward reverse))
    (is (every? true? (vals (:reconciliation/invariants forward))))))

(deftest duplicate-ingestion-is-idempotent-and-conflict-is-hard
  (let [{:keys [request plan]} (accounting-fixture)
        unit (first (:partition/work-units plan))
        p1 (accounting-partial request unit (:implementation-a roots) (:runner-a roots) (:run-a roots))
        p2 (accounting-partial request unit (:implementation-b roots) (:runner-b roots) (:run-b roots))
        once (accounting/ingest-partial {} p1)]
    (is (= once (accounting/ingest-partial once p1)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting"
                          (accounting/ingest-partial once p2)))))

(deftest incomplete-and-invalid-source-coverage-fail-closed
  (let [{:keys [contract request plan]} (accounting-fixture)
        one-unit (first (:partition/work-units plan))
        incomplete (accounting/ingest-partial
                    {} (accounting-partial request one-unit (:implementation-a roots)
                                           (:runner-a roots) (:run-a roots)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete"
                          (accounting/reconcile request contract plan incomplete)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/build-partition-plan
                  request [[(fake-root \6) (fake-root \7)]
                           [(fake-root \7) (fake-root \8)]]
                  {:dependency-set-root (:dependency roots)
                   :execution-environment-root (:environment roots)
                   :allowed-implementation-roots [(:implementation-a roots)]
                   :required-capabilities [:capability/accounting]
                   :privacy-requirement :public})))))

(deftest partial-provenance-must-match-its-exact-partition
  (let [{:keys [contract request plan]} (accounting-fixture)
        unit (first (:partition/work-units plan))
        valid (accounting-partial request unit (:implementation-a roots)
                                  (:runner-a roots) (:run-a roots))
        substitute (fn [partial key value]
                     (let [body (assoc partial key value)]
                       (assoc body :accounting-partial-result/root
                              (accounting/partial-root body))))]
    (doseq [partial [(substitute valid :resolved-research-dependency-set/root (:policy roots))
                     (substitute valid :research-execution-environment/root (:policy roots))
                     (substitute valid :research-implementation-identity/root (:policy roots))]]
      (is (false? (accounting/valid-partial-result? request unit partial)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (accounting/reconcile request contract plan
                                         (accounting/ingest-partial {} partial)))))))

(deftest stale-source-snapshot-and-wrong-parent-cannot-reconcile
  (let [{:keys [contract request plan]} (accounting-fixture)
        [u0 u1] (:partition/work-units plan)
        p0 (accounting-partial request u0 (:implementation-a roots) (:runner-a roots) (:run-a roots))
        p1 (accounting-partial request u1 (:implementation-b roots) (:runner-b roots) (:run-b roots))
        changed-request (accounting/build-request
                         {:accounting-domain (:accounting/domain request)
                          :cutpoint-root (:axis-x roots)
                          :accounting-definition-root (:accounting-definition/root request)
                          :accounting-policy-root (:accounting-policy/root request)
                          :reduction-contract contract :sources (:accounting/sources request)})]
    (is (not= (:accounting-request/root request) (:accounting-request/root changed-request)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/reconcile changed-request contract plan
                                       (-> {} (accounting/ingest-partial p0)
                                           (accounting/ingest-partial p1)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (accounting/build-partition-plan
                  request [[(get-in request [:accounting/sources 0 :source/root])
                            (get-in request [:accounting/sources 1 :source/root])]
                           [(get-in request [:accounting/sources 1 :source/root])
                            (get-in request [:accounting/sources 2 :source/root])]]
                  {:dependency-set-root (:dependency roots)
                   :execution-environment-root (:environment roots)
                   :allowed-implementation-roots [(:implementation-a roots)]
                   :required-capabilities [:capability/accounting]
                   :privacy-requirement :public})))))

(deftest forensic-provenance-varies-with-runner-and-implementation-not-semantics
  (let [{:keys [contract request plan]} (accounting-fixture)
        a (accounting/reconcile
           request contract plan
           (completed-ingestion request plan (:implementation-a roots)
                                (:implementation-a roots)))
        b (accounting/reconcile
           request contract plan
           (completed-ingestion request plan (:implementation-b roots)
                                (:implementation-b roots)))]
    (is (= (:canonical-accounting-result/root a)
           (:canonical-accounting-result/root b)))
    (is (not= (:accounting-reconciliation/root a)
              (:accounting-reconciliation/root b)))
    (is (not= (:accounting-partial-result/roots a)
              (:accounting-partial-result/roots b)))))

(deftest unsupported-algebra-cannot-authorize-reduction
  (let [contract (accounting/build-reduction-contract)
        weakened (assoc-in contract [:algebraic/properties :partition-independent] false)]
    (is (accounting/valid-reduction-contract? contract))
    (is (false? (accounting/valid-reduction-contract? weakened)))))

(defn axis [axis-root kind data-kind opts]
  (interaction/build-axis-semantics
   (merge {:axis-root axis-root :axis-kind kind :data-kind data-kind
           :ordered? false :integer? false :metric? false :additive? false
           :interaction-eligible? true}
          opts)))

(defn observation [root value]
  {:observation/root root :research-analysis/root (:analysis roots)
   :accounting-policy/root (:policy roots)
   :experiment/domain :experiment-domain/accounting
   :measure/id :measure/makespan :observation/status :different
   :observation/value value :observation/reason nil})

(deftest researcher-axis-is-nominal-and-never-interaction-eligible
  (let [nominal (axis (:axis-x roots) :researcher :nominal
                      {:interaction-eligible? false})]
    (is (= :nominal (:axis/data-kind nominal)))
    (is (false? (:axis/integer? nominal)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (axis (:axis-x roots) :researcher :integer
                       {:ordered? true :integer? true
                        :interaction-eligible? true})))))

(deftest integer-coordinate-interaction-is-exact-and-provenance-sensitive
  (let [x (axis (:axis-x roots) :parameter/x :integer
                {:ordered? true :integer? true :additive? true})
        y (axis (:axis-y roots) :parameter/y :integer
                {:ordered? true :integer? true})
        observations {:x1-y1 (observation (fake-root \1) 10)
                      :x1-y2 (observation (fake-root \2) 13)
                      :x2-y1 (observation (fake-root \3) 14)
                      :x2-y2 (observation (fake-root \4) 25)}
        result (interaction/difference-of-differences
                x y {:x1 0 :x2 1 :y1 0 :y2 1} observations)
        substituted (interaction/difference-of-differences
                     x y {:x1 0 :x2 1 :y1 0 :y2 1}
                     (assoc observations :x2-y2
                            (observation (fake-root \5) 25)))]
    (is (= 8 (:interaction/value result)))
    (is (= :different (:interaction/status result)))
    (is (= :exact-integer (:interaction/arithmetic result)))
    (is (not= (:research-coordinate-interaction/root result)
              (:research-coordinate-interaction/root substituted)))))

(deftest non-comparability-and-missing-propagate
  (let [x (axis (:axis-x roots) :parameter/privacy :nominal {})
        y (axis (:axis-y roots) :parameter/workers :integer
                {:ordered? true :integer? true})
        base {:x1-y1 (observation (fake-root \1) 1)
              :x1-y2 (observation (fake-root \2) 2)
              :x2-y1 (observation (fake-root \3) 3)
              :x2-y2 (observation (fake-root \4) 4)}
        missing (assoc base :x2-y2
                       (assoc (observation (fake-root \4) nil)
                              :observation/status :missing
                              :observation/reason :measurement-absent))
        incompatible (update-in base [:x2-y2]
                                assoc :accounting-policy/root (fake-root \9))
        missing-result (interaction/difference-of-differences
                        x y {:x1 :public :x2 :sealed :y1 1 :y2 2} missing)
        incompatible-result (interaction/difference-of-differences
                             x y {:x1 :public :x2 :sealed :y1 1 :y2 2}
                             incompatible)]
    (is (= :missing (:interaction/status missing-result)))
    (is (nil? (:interaction/value missing-result)))
    (is (= :constituent-missing (:interaction/reason missing-result)))
    (is (= :not-comparable (:interaction/status incompatible-result)))
    (is (= :different-accounting-policies
           (:interaction/reason incompatible-result)))))

(deftest accounting-position-is-qualified-and-not-a-transfer
  (let [position (semantics/build-accounting-position
                  {:subject-root (:subject-a roots)
                   :accounting-domain :accounting-domain/claim-basis
                   :cutpoint-root (:cutpoint roots)
                   :source-roots [(:claim roots)]
                   :values {:accounting/debit 8}
                   :accounting-definition-root (:definition roots)
                   :accounting-policy-root (:policy roots)})]
    (is (= :accounting-state-not-fund-movement (:position/meaning position)))
    (is (= {:accounting/debit 8} (:accounting/values position)))))
