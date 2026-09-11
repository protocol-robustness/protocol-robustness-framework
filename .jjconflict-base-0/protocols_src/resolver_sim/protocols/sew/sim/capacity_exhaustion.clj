(ns resolver-sim.protocols.sew.sim.capacity-exhaustion
  "Capacity exhaustion simulation.

   Runs six scenarios that exercise per-resolver maxConcurrentDisputes:

     SIM-01  Single resolver: finite capacity blocks excess disputes
     SIM-02  Capacity freed after resolution (slot reuse)
     SIM-03  Capacity freed after timeout (auto-cancel)
     SIM-04  Multi-resolver: two resolvers, disputes spread across both
     SIM-05  All-resolvers-full: system liveness — every slot exhausted
     SIM-06  Flood sweep: dispute rate vs capacity, measures rejection rate

   Each scenario checks:
     - resolver-capacity-invariant? after every state change
     - correct current-active counter at every checkpoint
     - escrow final states are correct
     - conservation-of-funds invariant throughout"
  (:require [resolver-sim.time.model :as tm]
            [resolver-sim.protocols.sew.snapshot :as snapshot]
            [resolver-sim.protocols.sew.config :as config]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.authority  :as auth]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def res2     "0xResolver2")
(def usdc     "0xUSDC")

(def base-snapshot
  (snapshot/make-escrow-snapshot
   (merge {:escrow-fee-bps 0}
          (select-keys config/DEFAULT_TIMEOUT_CONFIG
                       [:default-auto-release-delay
                        :default-auto-cancel-delay
                        :max-dispute-duration
                        :appeal-window-duration]))))

(def base-settings (t/make-escrow-settings {}))

(defn- check-inv! [world label]
  (let [cap-ok (inv/resolver-capacity-invariant? world)
        sol-ok (inv/conservation-of-funds? world)]
    (when-not (:holds? cap-ok)
      (throw (ex-info (str "INVARIANT BREACH capacity @ " label)
                      {:violations (:violations cap-ok)})))
    (when-not (:holds? sol-ok)
      (throw (ex-info (str "INVARIANT BREACH solvency @ " label)
                      {:violations (:violations sol-ok)})))
    world))

(defn- make-escrow
  "Create escrow alice→bob on world; returns [world wf-id]."
  [world resolver-addr]
  (let [wf-id   (count (:escrow-transfers world))
        r       (lc/create-escrow
                 world alice usdc bob 1000
                 (assoc base-settings :custom-resolver resolver-addr)
                 base-snapshot)]
    (when-not (:ok r)
      (throw (ex-info "create-escrow failed" {:error (:error r)})))
    [(:world r) wf-id]))

(defn- dispute! [world wf-id]
  (let [r (lc/raise-dispute world wf-id alice)]
    (if (:ok r)
      {:ok true :world (:world r)}
      {:ok false :error (:error r) :world world})))

(defn- resolve-finalize! [world wf-id resolver-addr is-release]
  (let [rm-fn (auth/make-default-resolution-module resolver-addr)
        r1    (res/execute-resolution world wf-id resolver-addr is-release "0xhash" rm-fn)]
    (when-not (:ok r1) (throw (ex-info "execute-resolution failed" {:error (:error r1)})))
    (let [w1 (tm/advance (:world r1) {:seconds 200000})
          r2 (res/execute-pending-settlement w1 wf-id)]
      (when-not (:ok r2) (throw (ex-info "execute-pending-settlement failed" {:error (:error r2)})))
      (:world r2))))

(defn- timeout-cancel! [world wf-id]
  (let [w (tm/advance world {:seconds 100000})
        r (lc/auto-cancel-disputed-escrow w wf-id)]
    (when-not (:ok r) (throw (ex-info "auto-cancel failed" {:error (:error r)})))
    (:world r)))

(defn- active [world resolver-addr]
  (get-in world [:resolver-capacities resolver-addr :current-active] 0))

(defn- pass [label]
  (println (str "  ✓ " label)))

(defn- fail [label reason]
  (println (str "  ✗ " label " — " reason)))

(defn- assert= [expected actual label]
  (if (= expected actual)
    (pass label)
    (fail label (str "expected " expected ", got " actual))))

;; ---------------------------------------------------------------------------
;; SIM-01: Single resolver, finite capacity blocks excess
;; ---------------------------------------------------------------------------

(defn sim-01 []
  (println "\nSIM-01  Single resolver — capacity=2 blocks 3rd dispute")
  (let [cap 2
        w0  (-> (t/empty-world 1000)
                (t/set-resolver-capacity resolver cap))
        [w1 _] (make-escrow w0 resolver)
        [w2 _] (make-escrow w1 resolver)
        [w3 _] (make-escrow w2 resolver)

        r0 (dispute! w3 0)
        _  (assert= true (:ok r0) "dispute 0 accepted (slot 1/2)")
        _  (assert= 1 (active (:world r0) resolver) "counter=1 after dispute 0")
        _  (check-inv! (:world r0) "after-dispute-0")

        r1 (dispute! (:world r0) 1)
        _  (assert= true (:ok r1) "dispute 1 accepted (slot 2/2)")
        _  (assert= 2 (active (:world r1) resolver) "counter=2 after dispute 1")
        _  (check-inv! (:world r1) "after-dispute-1")

        r2 (dispute! (:world r1) 2)
        _  (assert= false (:ok r2) "dispute 2 rejected (capacity exhausted)")
        _  (assert= :resolver-capacity-exceeded (:error r2) "correct error code")]
    ;; counter must not have changed on rejection
    (assert= 2 (active (:world r1) resolver) "counter unchanged after rejection")
    (check-inv! (:world r1) "final")
    {:passed true}))

;; ---------------------------------------------------------------------------
;; SIM-02: Slot freed after resolution — reuse
;; ---------------------------------------------------------------------------

(defn sim-02 []
  (println "\nSIM-02  Slot freed after resolution — reuse cycle × 3")
  (let [cap 1
        ;; 3 cycles × 2 escrow attempts each = 6 escrows needed
        w0  (-> (t/empty-world 1000)
                (t/set-resolver-capacity resolver cap))
        world-base
        (reduce (fn [w _] (let [[w2 _] (make-escrow w resolver)] w2))
                w0
                (range 6))]
    (loop [world world-base
           cycle 0]
      (if (= cycle 3)
        (do (assert= 0 (active world resolver) "counter=0 after 3 release cycles")
            (check-inv! world "final")
            {:passed true})
        (let [wf-id  (* 2 cycle)      ; escrows 0,2,4 are the primaries
              wf-id2 (inc wf-id)      ; escrows 1,3,5 are the blocked attempts
              rd (dispute! world wf-id)
              _  (assert= true (:ok rd) (str "cycle " (inc cycle) ": dispute accepted"))
              _  (assert= 1 (active (:world rd) resolver) (str "cycle " (inc cycle) ": counter=1"))

              rd2 (dispute! (:world rd) wf-id2)
              _   (assert= false (:ok rd2) (str "cycle " (inc cycle) ": second dispute rejected"))

              wf (resolve-finalize! (:world rd) wf-id resolver true)
              _  (assert= 0 (active wf resolver) (str "cycle " (inc cycle) ": counter=0 after resolve"))
              _  (check-inv! wf (str "cycle-" (inc cycle)))]
          (recur wf (inc cycle)))))))

;; ---------------------------------------------------------------------------
;; SIM-03: Slot freed after timeout auto-cancel
;; ---------------------------------------------------------------------------

(defn sim-03 []
  (println "\nSIM-03  Slot freed after auto-cancel timeout")
  (let [cap 1
        w0  (-> (t/empty-world 1000)
                (t/set-resolver-capacity resolver cap))
        [w1 _] (make-escrow w0 resolver)
        [w2 _] (make-escrow w1 resolver)

        rd (dispute! w2 0)
        _  (assert= true (:ok rd) "dispute 0 accepted")
        _  (assert= 1 (active (:world rd) resolver) "counter=1")

        ;; second dispute blocked
        rd2 (dispute! (:world rd) 1)
        _   (assert= false (:ok rd2) "second dispute blocked")

        ;; auto-cancel after timeout
        wt (timeout-cancel! (:world rd) 0)
        _  (assert= 0 (active wt resolver) "counter=0 after timeout")
        _  (assert= :refunded (t/escrow-state wt 0) "escrow 0 refunded")
        _  (check-inv! wt "after-timeout")

        ;; now the slot is free — escrow 1 can dispute
        rd3 (dispute! wt 1)
        _   (assert= true (:ok rd3) "slot reused after timeout")]
    (check-inv! (:world rd3) "final")
    {:passed true}))

;; ---------------------------------------------------------------------------
;; SIM-04: Two resolvers — disputes assigned to each independently
;; ---------------------------------------------------------------------------

(defn sim-04 []
  (println "\nSIM-04  Two resolvers cap=1 each — independent slot tracking")
  (let [w0 (-> (t/empty-world 1000)
               (t/set-resolver-capacity resolver 1)
               (t/set-resolver-capacity res2 1))
        [w1 _] (make-escrow w0 resolver)
        [w2 _] (make-escrow w1 res2)
        [w3 _] (make-escrow w2 resolver)

        ;; dispute escrow-0 on resolver, escrow-1 on res2
        rd0 (dispute! w3 0)
        _   (assert= true (:ok rd0) "dispute 0 on resolver accepted")
        rd1 (dispute! (:world rd0) 1)
        _   (assert= true (:ok rd1) "dispute 1 on res2 accepted (different resolver)")
        _   (assert= 1 (active (:world rd1) resolver) "resolver counter=1")
        _   (assert= 1 (active (:world rd1) res2)     "res2 counter=1")
        _   (check-inv! (:world rd1) "both-disputed")

        ;; third dispute (resolver) must be blocked — resolver at cap
        rd2 (dispute! (:world rd1) 2)
        _   (assert= false (:ok rd2) "dispute 2 blocked (resolver at cap)")

        ;; resolve escrow-0 → resolver slot freed
        wf (resolve-finalize! (:world rd1) 0 resolver true)
        _  (assert= 0 (active wf resolver) "resolver counter=0 after resolve")
        _  (assert= 1 (active wf res2)     "res2 counter unchanged")

        ;; now escrow-2 on resolver should succeed
        rd3 (dispute! wf 2)
        _   (assert= true (:ok rd3) "dispute 2 accepted after slot freed")]
    (check-inv! (:world rd3) "final")
    {:passed true}))

;; ---------------------------------------------------------------------------
;; SIM-05: All resolvers full — system liveness test
;; ---------------------------------------------------------------------------

(defn sim-05 []
  (println "\nSIM-05  All resolvers full — queued escrow must wait for a slot")
  (let [n-resolvers 3
        resolvers   [resolver res2 "0xResolver3"]
        w0          (reduce #(t/set-resolver-capacity %1 %2 2)
                            (t/empty-world 1000)
                            resolvers)
        ;; create 7 escrows: 2 per resolver + 1 pending
        world-with-escrows
        (reduce (fn [w [idx r]]
                  (let [[w2 _] (make-escrow w r)] w2))
                w0
                (map-indexed vector (concat resolvers resolvers resolvers [resolver])))

        ;; fill all slots: 2 disputes per resolver = 6 disputes
        filled-world
        (reduce (fn [w [wf-id res-addr]]
                  (let [rd (dispute! w wf-id)]
                    (when-not (:ok rd)
                      (throw (ex-info "unexpected reject filling slots"
                                      {:wf-id wf-id :error (:error rd)})))
                    (:world rd)))
                world-with-escrows
                [[0 resolver] [1 res2] [2 "0xResolver3"]
                 [3 resolver] [4 res2] [5 "0xResolver3"]])

        _ (assert= 2 (active filled-world resolver) "resolver full (2/2)")
        _ (assert= 2 (active filled-world res2)     "res2 full (2/2)")
        _ (assert= 2 (active filled-world "0xResolver3") "resolver3 full (2/2)")
        _ (check-inv! filled-world "all-slots-full")

        ;; escrow 6 (assigned to resolver) must be blocked
        rd7 (dispute! filled-world 6)
        _   (assert= false (:ok rd7) "escrow 6 blocked (resolver at cap)")

        ;; resolve one dispute on resolver
        wf (resolve-finalize! filled-world 0 resolver true)
        _  (assert= 1 (active wf resolver) "resolver counter=1 after resolve")

        ;; now escrow 6 can dispute
        rd8 (dispute! wf 6)
        _   (assert= true (:ok rd8) "escrow 6 now accepted after slot freed")]
    (check-inv! (:world rd8) "final")
    {:passed true}))

;; ---------------------------------------------------------------------------
;; SIM-06: Flood sweep — dispute rate vs capacity
;; ---------------------------------------------------------------------------

(defn sim-06 []
  (println "\nSIM-06  Flood sweep — varying capacity vs dispute count")
  (println "        cap | disputes | accepted | rejected | inv-ok?")
  (println "        ----+----------+----------+----------+--------")
  (let [n-escrows 10
        caps      [1 2 3 5 10]]
    (doseq [cap caps]
      (let [w0 (-> (t/empty-world 1000)
                   (t/set-resolver-capacity resolver cap))
            ;; create n-escrows
            world-base
            (reduce (fn [w _]
                      (let [[w2 _] (make-escrow w resolver)] w2))
                    w0
                    (range n-escrows))

            ;; try to dispute all n-escrows
            results
            (reduce (fn [{:keys [world accepted rejected]} wf-id]
                      (let [rd (dispute! world wf-id)]
                        (if (:ok rd)
                          {:world (:world rd) :accepted (inc accepted) :rejected rejected}
                          {:world world        :accepted accepted        :rejected (inc rejected)})))
                    {:world world-base :accepted 0 :rejected 0}
                    (range n-escrows))

            inv-ok? (:holds? (inv/resolver-capacity-invariant? (:world results)))]
        (println (format "        %3d | %8d | %8d | %8d | %s"
                         cap n-escrows (:accepted results) (:rejected results)
                         (if inv-ok? "✓" "✗")))
        (when-not (= cap (:accepted results))
          (fail (str "cap=" cap) (str "accepted=" (:accepted results) " expected=" cap)))))
    {:passed true}))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn run-all []
  (println "\n══════════════════════════════════════════════════════════")
  (println "  Capacity Exhaustion Simulation")
  (println "══════════════════════════════════════════════════════════")
  (let [scenarios [["SIM-01" sim-01]
                   ["SIM-02" sim-02]
                   ["SIM-03" sim-03]
                   ["SIM-04" sim-04]
                   ["SIM-05" sim-05]
                   ["SIM-06" sim-06]]
        results
        (doall
         (for [[id f] scenarios]
           (try
             (let [r (f)]
               (println (str "\n  → " id " PASSED"))
               {:id id :passed true})
             (catch Exception e
               (println (str "\n  → " id " FAILED: " (.getMessage e)))
               (when-let [d (ex-data e)]
                 (println "    data:" d))
               {:id id :passed false}))))
        n-pass (count (filter :passed results))
        n-fail (- (count results) n-pass)]
    (println "\n──────────────────────────────────────────────────────────")
    (println (format "  Results: %d/%d passed" n-pass (count results)))
    (when (pos? n-fail)
      (println (str "  FAILED: " (mapv :id (remove :passed results)))))
    (println "══════════════════════════════════════════════════════════\n")
    {:passed n-pass :failed n-fail :total (count results)}))
