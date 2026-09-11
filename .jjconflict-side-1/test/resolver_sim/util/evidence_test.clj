(ns resolver-sim.util.evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.util.evidence :as ev]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.hash.canonical :as hc]))

;; Sample data for tests
(def ^:private sample-world
  {:escrows {"0xabc" {:balance 1000 :owner "0x1"}}
   :resolvers {"0xres" {:stake 500 :status :active}}})

(def ^:private sample-action
  {:type :release
   :escrow-id "0xabc"
   :actor "0xres"})

(def ^:private sample-result
  {:success? true
   :released 1000
   :fees 15})

(def ^:private sample-attribution
  {:ctx/run-id "test-run-1"
   :ctx/scenario-id "test-scenario"
   :ctx/step 5
   :ctx/event-id "evt-001"})

(def ^:private partial-attribution
  {:ctx/run-id "test-run-1"})

;; ── Internal Context Embedding ───────────────────────────────────────────────

(deftest test-attributed-state-wrapping
  (let [state {:balance 100}
        attribution {:ctx/run-id "run-1"}
        attributed (attr/wrap-state state attribution)]
    (is (= state (attr/unwrap-state attributed)))
    (is (= attribution (:attribution attributed)))))

(deftest test-get-attribution-from-attributed-state
  (let [state {:balance 100}
        attribution {:ctx/run-id "run-1"}
        attributed (attr/wrap-state state attribution)]
    (is (= attribution (attr/get-attribution attributed)))
    (binding [attr/*attribution* {:ctx/run-id "dynamic"}]
      (is (= {:ctx/run-id "dynamic"} (attr/get-attribution nil))))))

(deftest attribution-map-detects-marker-keys
  (is (true? (attr/attribution-map? {:ctx/run-id "run-1"})))
  (is (true? (attr/attribution-map? {:subject/type :escrow})))
  (is (false? (attr/attribution-map? {:balance 100 :status :active})))
  (is (false? (attr/attribution-map? nil))))

(deftest resolve-attribution-ignores-arbitrary-maps
  (binding [attr/*attribution* {:ctx/run-id "dynamic"}]
    (is (= {:ctx/run-id "dynamic"}
           (attr/resolve-attribution {:balance 100 :status :active})))))

(deftest resolve-attribution-prefers-explicit-override
  (binding [attr/*attribution* {:ctx/run-id "dynamic"}]
    (is (= {:ctx/run-id "explicit"}
           (attr/resolve-attribution {:ctx/run-id "input"}
                                     {:ctx/run-id "explicit"})))))

(deftest resolve-attribution-extracts-attributed-state
  (let [attribution {:ctx/run-id "wrapped"}
        attributed (attr/wrap-state {:balance 100} attribution)]
    (is (= attribution (attr/explicit-attribution attributed)))
    (is (= attribution (attr/resolve-attribution attributed)))))

(deftest nested-attribution-finds-direct-nested-map
  (is (= {:ctx/run-id "nested"}
         (attr/nested-attribution {:result {:metadata {:ctx/run-id "nested"}}}))))

(deftest nested-attribution-finds-attribution-envelope
  (is (= {:ctx/run-id "enveloped"}
         (attr/nested-attribution {:evidence {:attribution {:attribution/version 1
                                                            :attribution/context {:ctx/run-id "enveloped"}}}}))))

(deftest nested-attribution-finds-nested-attributed-state
  (let [attributed (attr/wrap-state {:balance 100} {:ctx/run-id "wrapped-nested"})]
    (is (= {:ctx/run-id "wrapped-nested"}
           (attr/nested-attribution {:items [{:state attributed}]})))))

(deftest nested-attribution-ignores-arbitrary-maps
  (is (nil? (attr/nested-attribution {:result {:balance 100
                                               :status :active
                                               :config {:threshold 3}}}))))

(deftest nested-attribution-prefers-shallow-explicit-attribution
  (is (= {:ctx/run-id "outer"}
         (attr/nested-attribution {:ctx/run-id "outer"
                                   :result {:metadata {:ctx/run-id "inner"}}}))))

(deftest sanitize-attribution-is-pure-filter
  (is (= {:ctx/run-id "run-1"}
         (attr/sanitize-attribution {:ctx/run-id "run-1"
                                     :plain-key "dropped"
                                     "bad" "dropped"}))))

(deftest with-attribution-strict-sanitizes-after-validation
  (binding [attr/*attribution* {:ctx/run-id "outer"}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (attr/with-attribution-strict {:plain-key "bad"}
                   (attr/current-attribution))))
    (is (= {:ctx/run-id "inner"}
           (attr/with-attribution-strict {:ctx/run-id "inner"}
             (attr/current-attribution))))))

(deftest with-resolved-attribution-binds-attributed-state
  (binding [attr/*attribution* {:ctx/run-id "dynamic"}]
    (let [attributed (attr/wrap-state {:balance 100} {:ctx/run-id "wrapped"})]
      (is (= {:ctx/run-id "wrapped"}
             (attr/with-resolved-attribution attributed
               (attr/current-attribution)))))))

;; ── require-attribution! ─────────────────────────────────────────────────────

(deftest require-attribution-passes-with-complete-context
  (binding [attr/*attribution* sample-attribution]
    (let [result (ev/require-attribution! :transition)]
      (is (map? result))
      (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result))))))

(deftest require-attribution-throws-on-missing-keys
  (binding [attr/*attribution* partial-attribution]
    (try
      (ev/require-attribution! :transition)
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :transition (:evidence-kind data)))
          (is (some #{:ctx/scenario-id} (:missing data)))
          (is (some #{:ctx/step} (:missing data)))
          (is (some #{:ctx/event-id} (:missing data))))))))

(deftest require-attribution-accepts-empty-attribution-for-unknown-kind
  (binding [attr/*attribution* {}]
    (let [result (ev/require-attribution! :unknown-kind)]
      (is (map? result)))))

;; ── hash-with-intent ──────────────────────────────────────────────────────────

(deftest hash-with-intent-deterministic
  (let [data {:a 1 :b [2 3] :c {:nested "value"}}
        h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
        h2 (hc/hash-with-intent {:hash/intent :evidence-record} data)
        h3 (hc/hash-with-intent {:hash/intent :evidence-record} (into (sorted-map) data))]
    (is (string? h1))
    (is (= 64 (count h1)))
    (is (= h1 h2))
    (is (= h1 h3))))

(deftest hash-with-intent-differs-for-different-inputs
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :evidence-record} {:a 2}))))

(deftest hash-with-intent-handles-keyword-values
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:status :active :tags [:a :b]})]
    (is (string? h))
    (is (= 64 (count h)))))

(deftest hash-with-intent-handles-empty-map
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {})]
    (is (string? h))
    (is (= 64 (count h)))))

(deftest hash-with-intent-rejects-unknown-intent
  (is (thrown? Exception (hc/hash-with-intent {:hash/intent :nonexistent} {:a 1}))))

(deftest hash-with-intent-world-structure-projects
  (let [world {:step 42 :resolver-unavailable #{:addr1}}
        h (hc/hash-with-intent {:hash/intent :world-structure} world)]
    (is (string? h))
    (is (= 64 (count h)))))

(deftest hash-intents-differ-by-intent
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :world-structure} {:a 1}))))

;; ── make-evidence-record ─────────────────────────────────────────────────────

(deftest make-evidence-record-includes-attribution
  (let [record (ev/make-evidence-record
                {:artifact-kind :transition
                 :before sample-world
                 :after sample-world
                 :action sample-action
                 :result sample-result
                 :attribution sample-attribution})]
    (is (= "evidence-record.v1" (:schema-version record)))
    (is (= :transition (:artifact-kind record)))
    (is (= sample-attribution (:attribution record)))
    (is (string? (:evidence-hash record)))))

(deftest make-evidence-record-includes-hashes
  (let [record (ev/make-evidence-record
                {:artifact-kind :transition
                 :before sample-world
                 :after sample-world
                 :action sample-action
                 :result sample-result
                 :attribution sample-attribution})]
    (is (string? (:context-hash record)))
    (is (string? (:before-hash record)))
    (is (string? (:after-hash record)))
    (is (string? (:action-hash record)))
    (is (string? (:action-hash-at record)))
    (is (string? (:result-hash record)))
    (is (string? (:evidence-hash record)))
    (is (not= (:action-hash record) (:action-hash-at record)))
    (testing "action-hash-at binds action to execution position"
      (is (some? (:action-hash-at record))))))

(deftest make-evidence-record-hash-changes-when-attribution-changes
  (let [record1 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})
        record2 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution (assoc sample-attribution :ctx/step 99)})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-hash-changes-when-result-changes
  (let [record1 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})
        record2 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result (assoc sample-result :success? false)
                  :attribution sample-attribution})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-hash-changes-when-before-changes
  (let [record1 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})
        record2 (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before (assoc-in sample-world [:escrows "0xabc" :balance] 2000)
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-consistent-for-same-inputs
  (let [spec {:artifact-kind :transition
              :before sample-world
              :after sample-world
              :action sample-action
              :result sample-result
              :attribution sample-attribution}
        record1 (ev/make-evidence-record spec)
        record2 (ev/make-evidence-record spec)]
    (is (= (:evidence-hash record1) (:evidence-hash record2)))
    (is (= record1 record2))))

(deftest test-action-hash-and-action-hash-at-not-interchangeable
  (let [spec {:artifact-kind :transition
              :before sample-world
              :after sample-world
              :action sample-action
              :result sample-result
              :attribution sample-attribution}
        record (ev/make-evidence-record spec)
        action-hash (:action-hash record)
        action-hash-at (:action-hash-at record)]
    (is (not= action-hash action-hash-at))
    (testing "swapping would change evidence-hash: construct what-if record"
      (let [swapped (assoc (dissoc record :action-hash :action-hash-at)
                           :action-hash action-hash-at
                           :action-hash-at action-hash)
            swapped-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                              (dissoc swapped :evidence-hash))]
        (is (not= (:evidence-hash record) swapped-hash))))))

(deftest make-evidence-record-with-different-kinds
  (let [invariant-record (ev/make-evidence-record
                          {:artifact-kind :invariant
                           :before sample-world
                           :after sample-world
                           :action sample-action
                           :result {:invariant :solvency :passed? true}
                           :attribution sample-attribution})]
    (is (= :invariant (:artifact-kind invariant-record)))
    (is (string? (:evidence-hash invariant-record)))))

;; ── emit-evidence! ───────────────────────────────────────────────────────────

(deftest emit-evidence-produces-valid-record
  (binding [attr/*attribution* sample-attribution]
    (let [record (ev/emit-evidence!
                  {:artifact-kind :transition
                   :block-time 1000
                   :step 1
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result sample-result})]
      (is (= "evidence-record.v1" (:schema-version record)))
      (is (= :transition (:artifact-kind record)))
      (is (contains? record :attribution))
      (is (string? (:evidence-hash record))))))

(deftest emit-evidence-throws-on-missing-attribution
  (binding [attr/*attribution* {}]
    (try
      (ev/emit-evidence!
       {:artifact-kind :transition
        :block-time 1000
        :step 1
        :before {}
        :after {}
        :action {}
        :result {}})
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :transition (:evidence-kind data)))
          (is (some? (:missing data))))))))

(deftest emit-evidence-throws-on-missing-temporal-context
  (binding [attr/*attribution* sample-attribution]
    (try
      (ev/emit-evidence!
       {:artifact-kind :transition
        :before {}
        :after {}
        :action {}
        :result {}})
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (nil? (:block-time data)))
          (is (nil? (:step data))))))))

(deftest emit-evidence-works-with-partial-attribution-for-scenario
  (binding [attr/*attribution* {:ctx/run-id "run-1"
                                :ctx/scenario-id "scenario-1"}]
    (let [record (ev/emit-evidence!
                  {:artifact-kind :scenario
                   :block-time 1000
                   :step 1
                   :before {}
                   :after {}
                   :action {:type :start}
                   :result {:status :ok}})]
      (is (= "evidence-record.v1" (:schema-version record)))
      (is (string? (:evidence-hash record))))))

(deftest emit-evidence-works-with-explicit-attribution
  (let [explicit-attr {:ctx/run-id "run-1"
                       :ctx/scenario-id "scen-1"
                       :ctx/step 1
                       :ctx/event-id "evt-1"}]
    (binding [attr/*attribution* {}] ;; Ensure dynamic is empty
      (let [record (ev/emit-evidence!
                    {:artifact-kind :transition
                     :block-time 1000
                     :step 1
                     :before {}
                     :after {}
                     :action {}
                     :result {}
                     :attribution-context explicit-attr})]
        (is (= "run-1" (get-in record [:attribution :ctx/run-id])))))))

(deftest emit-evidence-works-with-attributed-state
  (let [explicit-attr {:ctx/run-id "run-1"
                       :ctx/scenario-id "scen-1"
                       :ctx/step 1
                       :ctx/event-id "evt-1"}
        attributed (attr/wrap-state {} explicit-attr)]
    (binding [attr/*attribution* {}] ;; Ensure dynamic is empty
      (let [record (ev/emit-evidence!
                    {:artifact-kind :transition
                     :block-time 1000
                     :step 1
                     :before {}
                     :after {}
                     :action {}
                     :result {}
                     :attribution-context attributed})]
        (is (= "run-1" (get-in record [:attribution :ctx/run-id])))))))

;; ── wrap-attribution ─────────────────────────────────────────────────────────

(deftest wrap-attribution-preserves-context
  (binding [attr/*attribution* sample-attribution]
    (let [wrapped (ev/wrap-attribution (fn [] (attr/current-attribution)))]
      (binding [attr/*attribution* {}]
        (let [preserved (wrapped)]
          (is (= (:ctx/run-id sample-attribution) (:ctx/run-id preserved)))
          (is (= (:ctx/scenario-id sample-attribution) (:ctx/scenario-id preserved)))
          (is (= (:ctx/step sample-attribution) (:ctx/step preserved))))))))

(deftest wrap-attribution-passes-arguments
  (binding [attr/*attribution* sample-attribution]
    (let [wrapped (ev/wrap-attribution (fn [a b] (+ a b)))]
      (is (= 7 (wrapped 3 4)))
      (let [wrapped2 (ev/wrap-attribution (fn [x] (assoc (attr/current-attribution) :arg x)))]
        (binding [attr/*attribution* {}]
          (let [result (wrapped2 "test-arg")]
            (is (= "test-arg" (:arg result)))
            (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result)))))))))

;; ── contextual-future ────────────────────────────────────────────────────────

(deftest contextual-future-preserves-attribution
  (binding [attr/*attribution* sample-attribution]
    (let [f (ev/contextual-future (attr/current-attribution))
          result @f]
      (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result)))
      (is (= (:ctx/scenario-id sample-attribution) (:ctx/scenario-id result)))
      (is (= (:ctx/step sample-attribution) (:ctx/step result))))))

(deftest contextual-future-isolated-from-outer-changes
  (binding [attr/*attribution* sample-attribution]
    (let [f (ev/contextual-future (do (Thread/sleep 50) (attr/current-attribution)))]
      (binding [attr/*attribution* {:ctx/run-id "overwritten"}]
        (let [result @f]
          (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result))))))))

;; ── contextual-pmap ──────────────────────────────────────────────────────────

(deftest contextual-pmap-preserves-attribution
  (binding [attr/*attribution* sample-attribution]
    (let [items [1 2 3]
          results (doall (ev/contextual-pmap
                          (fn [x] [x (attr/current-attribution)])
                          items))]
      (is (= 3 (count results)))
      (doseq [[_ attr-result] results]
        (is (= (:ctx/run-id sample-attribution) (:ctx/run-id attr-result)))))))

;; ── Edge Cases ───────────────────────────────────────────────────────────────

(deftest make-evidence-record-with-nil-values
  (let [record (ev/make-evidence-record
                {:artifact-kind :transition
                 :before nil
                 :after nil
                 :action nil
                 :result nil
                 :attribution {:ctx/run-id "run-1"}})]
    (is (string? (:evidence-hash record)))
    (is (string? (:before-hash record)))
    (is (string? (:after-hash record)))
    (is (= "evidence-record.v1" (:schema-version record)))))

(deftest evidence-chain-immutability
  (testing "evidence records are independent and immutable across chaining steps"
    (binding [attr/*attribution* sample-attribution]
      (let [world-0 (assoc sample-world :step 0)
            ;; Step 1: Capture evidence
            record-1 (ev/emit-evidence!
                      {:artifact-kind :transition
                       :block-time 1000
                       :step 1
                       :before world-0
                       :after (assoc world-0 :step 1)
                       :action {:type :step-1}
                       :result {:status :ok}})
            hash-1 (:evidence-hash record-1)

            ;; Step 2: Create "next" world state without mutating world-0
            world-1 (assoc world-0 :step 1)
            record-2 (ev/emit-evidence!
                      {:artifact-kind :transition
                       :block-time 1001
                       :step 2
                       :before world-1
                       :after (assoc world-1 :step 2)
                       :action {:type :step-2}
                       :result {:status :ok}})

            ;; Assertions
            ;; 1. The original world state reference is unchanged (Clojure property)
            ;; 2. Record 1 remains exactly as it was
            record-1-recheck (ev/emit-evidence!
                              {:artifact-kind :transition
                               :block-time 1000
                               :step 1
                               :before world-0
                               :after (assoc world-0 :step 1)
                               :action {:type :step-1}
                               :result {:status :ok}})]
        (is (= hash-1 (:evidence-hash record-1-recheck))
            "Evidence hash for step 1 remains stable across later steps")
        (is (= record-1 record-1-recheck)
            "Full evidence record 1 is identical after later steps")
        (is (not= hash-1 (:evidence-hash record-2))
            "Evidence hashes are unique per transition")))))
