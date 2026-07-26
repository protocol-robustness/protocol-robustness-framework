(ns resolver-sim.protocols.sew.replay-bridge-test
  "Smoke tests for the legacy Sew bridge functions in contract-model.replay.

   These functions (build-context, sew-dispatch-action,
   sew-check-invariants-single, sew-check-invariants-transition) wrap
   DisputeProtocol calls through the SewProtocol singleton.  They exist to
   keep existing callers (server/session, io/trace-export, sim/minimizer, etc.)
   unchanged while the protocol abstraction layer matures.

   Tests here verify the wiring — that each function calls the correct
   protocol method and returns the expected shape — not the Sew business logic
   itself (which is covered by protocols/sew/*_test.clj)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay    :as replay]
            [resolver-sim.protocols.registry       :as preg]
            [resolver-sim.protocols.sew.types      :as t]))

;; ---------------------------------------------------------------------------
;; Shared test data
;; ---------------------------------------------------------------------------

(def ^:private sew-protocol
  (preg/get-protocol "sew-v1"))

(def ^:private dummy-protocol
  (preg/get-protocol "dummy"))

(def ^:private yield-protocol
  (preg/get-protocol "yield-v1"))

(def ^:private agents
  [{:id "buyer"    :address "0xbuyer"    :type "honest"}
   {:id "seller"   :address "0xseller"   :type "honest"}
   {:id "resolver" :address "0xresolver" :type "resolver"}])

(def ^:private params
  {:resolver-fee-bps 50 :max-dispute-duration 2592000})

;; ---------------------------------------------------------------------------
;; build-context
;; ---------------------------------------------------------------------------

(deftest build-context-returns-agent-index
  (testing "returns a context map with :agent-index keyed by agent :id"
    (let [ctx (replay/build-context sew-protocol agents params)]
      (is (map? ctx))
      (is (contains? ctx :agent-index))
      (is (= #{"buyer" "seller" "resolver"} (set (keys (:agent-index ctx)))))
      (is (= "0xbuyer" (get-in ctx [:agent-index "buyer" :address]))))))

(deftest build-context-backward-compatible
  (testing "2-arg arity defaults to Sew and still works"
    (let [ctx (replay/build-context agents params)]
      (is (map? ctx))
      (is (contains? ctx :agent-index))
      (is (= #{"buyer" "seller" "resolver"} (set (keys (:agent-index ctx))))))))

(deftest build-context-dispatches-to-given-protocol
  (testing "3-arg arity dispatches to the given protocol, not SEW"
    (let [sew-ctx   (replay/build-context sew-protocol agents params)
          dummy-ctx (replay/build-context dummy-protocol agents params)
          yield-ctx (replay/build-context yield-protocol agents params)]
      (is (contains? sew-ctx :agent-index))
      (is (contains? dummy-ctx :agent-index))
      (is (contains? yield-ctx :agent-index))
      ;; Sew adds extra keys (protocol-params, etc) that dummy does not
      (is (> (count (keys sew-ctx)) (count (keys dummy-ctx)))))))

;; ---------------------------------------------------------------------------
;; sew-dispatch-action
;; ---------------------------------------------------------------------------

(deftest sew-dispatch-action-create-escrow
  (testing "create_escrow returns :ok true and assigns a workflow-id"
    (let [ctx    (replay/build-context sew-protocol agents params)
          world  (t/empty-world 1000)
          event  {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :params {:token "USDC" :to "0xseller" :amount 5000}}
          result (replay/sew-dispatch-action ctx world event)]
      (is (:ok result))
      (is (number? (get-in result [:extra :workflow-id]))))))

(deftest sew-dispatch-action-unknown-action
  (testing "unknown action returns :ok false with :unknown-action error"
    (let [ctx    (replay/build-context sew-protocol agents params)
          world  (t/empty-world 1000)
          event  {:seq 0 :time 1000 :agent "buyer" :action "nonexistent_action"
                  :params {}}
          result (replay/sew-dispatch-action ctx world event)]
      (is (false? (:ok result)))
      (is (= :unknown-action (:error result))))))

;; ---------------------------------------------------------------------------
;; sew-check-invariants-single
;; ---------------------------------------------------------------------------

(deftest sew-check-invariants-single-empty-world
  (testing "empty world holds all single-world invariants"
    (let [result (replay/sew-check-invariants-single (t/empty-world 1000))]
      (is (map? result))
      (is (contains? result :ok?))
      (is (true? (:ok? result)))
      (is (nil? (seq (:violations result)))))))

;; ---------------------------------------------------------------------------
;; sew-check-invariants-transition
;; ---------------------------------------------------------------------------

(deftest sew-check-invariants-transition-identical-worlds
  (testing "transition between identical worlds holds all transition invariants"
    (let [world  (t/empty-world 1000)
          result (replay/sew-check-invariants-transition world world)]
      (is (true? (:ok? result)))
      (is (nil? (seq (:violations result)))))))

(deftest sew-check-invariants-transition-valid-escrow-creation
  (testing "transition from empty world to world-with-escrow holds invariants"
    (let [world-before (t/empty-world 1000)
          ctx          (replay/build-context sew-protocol agents params)
          event        {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                        :params {:token "USDC" :to "0xseller" :amount 5000}}
          dispatch     (replay/sew-dispatch-action ctx world-before event)
          world-after  (:world dispatch)
          result       (replay/sew-check-invariants-transition world-before world-after)]
      (is (true? (:ok? result)))
      (is (nil? (:violations result))))))
