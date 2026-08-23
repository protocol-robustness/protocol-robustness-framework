(ns resolver-sim.pro-rata.commitment-stack-conformance-test
  "Composition conformance for the protocol-neutral commitment stack.

   This is deliberately a synthetic native-state model. It proves only that the
   existing artifact builders compose over a closed all-active fixture; it does
   not claim production SEW execution, SEW frame preservation, or final-evidence
   publication."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.protocol-realization :as realization]
            [resolver-sim.pro-rata.protocol-transaction-realization :as join]
            [resolver-sim.pro-rata.transact :as transact]))

(def fixture-path "test/fixtures/pro-rata/commitment-stack/all-active-modeled-v1.edn")

(defn- fixture []
  (edn/read-string (slurp fixture-path)))

(defn- protocol-root [state]
  (hc/domain-hash :world-state state))

(defn- modeled-project [quantities state]
  {(:liquidity quantities) (get-in state [:pool :available])
   (:alice-filled quantities) (get-in state [:claims :alice :filled])
   (:alice-outstanding quantities) (get-in state [:claims :alice :outstanding])
   (:bob-filled quantities) (get-in state [:claims :bob :filled])
   (:bob-outstanding quantities) (get-in state [:claims :bob :outstanding])})

(defn- modeled-reconstruct [quantities state canonical-after _]
  (-> state
      (assoc-in [:pool :available] (get canonical-after (:liquidity quantities)))
      (assoc-in [:claims :alice :filled] (get canonical-after (:alice-filled quantities)))
      (assoc-in [:claims :alice :outstanding] (get canonical-after (:alice-outstanding quantities)))
      (assoc-in [:claims :bob :filled] (get canonical-after (:bob-filled quantities)))
      (assoc-in [:claims :bob :outstanding] (get canonical-after (:bob-outstanding quantities)))))

(defn- root-projection [artifact fields]
  (select-keys artifact fields))

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn snapshot
  "Build every artifact in the fixture chain and expose only stable checkpoints.
   Public to make the fixture vector reproducible from a REPL or clean-room
   conformance harness."
  []
  (let [{:keys [allocation-request quantity-identities modeled-protocol-before
                adapter-dependencies trace-policy write-set]} (fixture)
        allocation (allocation/allocate allocation-request)
        targets {:liquidity/root (:liquidity quantity-identities)
                 :claim/alice {:filled/root (:alice-filled quantity-identities)
                               :outstanding/root (:alice-outstanding quantity-identities)}
                 :claim/bob {:filled/root (:bob-filled quantity-identities)
                             :outstanding/root (:bob-outstanding quantity-identities)}}
        compilation (effects/build-pro-rata-effect-compilation
                     allocation targets (:effect-compilation-semantics/root adapter-dependencies))
        canonical-before (modeled-project quantity-identities modeled-protocol-before)
        canonical-transition (effects/transition canonical-before
                                                 (effects/compile-pro-rata-effects allocation targets))
        adapter (realization/build-adapter
                 {:protocol-id :modeled-sew
                  :protocol-state-schema-root (:protocol-state-schema/root adapter-dependencies)
                  :projection-semantics-root (:projection-semantics/root adapter-dependencies)
                  :quantity-mapping-root (:quantity-mapping/root adapter-dependencies)
                  :reconstruction-semantics-root (:reconstruction-semantics/root adapter-dependencies)
                  :write-set-semantics-root (:write-set-semantics/root adapter-dependencies)})
        protocol-realization
        (realization/build-realization
         {:adapter adapter
          :canonical-transition canonical-transition
          :protocol-before modeled-protocol-before
          :write-set write-set
          :project #(modeled-project quantity-identities %)
          :reconstruct #(modeled-reconstruct quantity-identities %1 %2 %3)
          :protocol-state-root protocol-root
          :realization-semantics-root (:realization-semantics/root adapter-dependencies)})
        transaction (transact/build-transaction
                     {:operations (mapv (fn [effect]
                                          {:quantity-root (:quantity/root effect)
                                           :delta (:delta effect)})
                                        (:effects canonical-transition))
                      :operation-semantics-root (:operation-semantics/root adapter-dependencies)
                      :trace-policy-root (:trace-policy/root adapter-dependencies)})
        trace (transact/execute canonical-before transaction canonical-transition trace-policy)
        binding-semantics (transact/build-binding-semantics :effect-exact)
        binding (transact/bind-transition canonical-transition transaction trace
                                          (:binding-semantics/root binding-semantics))
        protocol-transaction-realization
        (join/build {:canonical-transition-root (:canonical-effect-transition/root canonical-transition)
                     :transition-binding binding
                     :protocol-effect-realization protocol-realization})
        projections
        {:effect-compilation (root-projection compilation [:schema-version :realized-allocation/root
                                                           :effect-compilation-semantics/root :effects/root
                                                           :effect-compilation/root])
         :canonical-transition (root-projection canonical-transition [:schema-version :effect-semantics/root
                                                                      :state-before/root :effects/root
                                                                      :state-after/root :canonical-effect-transition/root])
         :transact (root-projection transaction [:schema-version :operations/root :operation-semantics/root
                                                 :trace-policy/root :transact/root])
         :trace (root-projection trace [:schema-version :transact/root :transition/input-root
                                        :transition/output-root :trace/root :trace/length
                                        :trace/max-length :operation-semantics/root :trace-policy/root
                                        :trace-bounded-transition/root])
         :transition-binding (root-projection binding [:schema-version :binding/mode
                                                       :canonical-transition/root
                                                       :trace-bounded-transition/root
                                                       :operation-footprint/root
                                                       :projected-trace-before/root
                                                       :projected-trace-after/root
                                                       :binding-semantics/root
                                                       :transition-binding/root])
         :protocol-realization (root-projection protocol-realization [:schema-version :protocol/id :adapter/root
                                                                      :canonical-transition/root
                                                                      :protocol-state-before/root
                                                                      :canonical-state-before/root
                                                                      :canonical-state-after/root
                                                                      :protocol-state-after/root :write-set/root
                                                                      :realization-semantics/root
                                                                      :protocol-effect-realization/root])
         :protocol-transaction-realization
         (root-projection protocol-transaction-realization [:schema-version :canonical-transition/root
                                                            :transition-binding/root
                                                            :protocol-effect-realization/root
                                                            :binding/mode
                                                            :protocol-transaction-realization/root])}]
    {:allocation allocation
     :compilation compilation
     :canonical-before canonical-before
     :canonical-transition canonical-transition
     :adapter adapter
     :protocol-realization protocol-realization
     :transaction transaction
     :trace trace
     :binding binding
     :protocol-transaction-realization protocol-transaction-realization
     :projections projections
     :canonical-bytes (into {}
                            (map (fn [[k projection]]
                                   [k (bytes->hex (hc/canonical-bytes projection))])
                                 projections))}))

(deftest all-active-modeled-composition-is-deterministic-and-complete
  (let [case (fixture)
        first-run (snapshot)
        second-run (snapshot)
        expected (:expected case)
        quantities (:quantity-identities case)
        expected-effects (mapv (fn [{:keys [quantity delta]}]
                                 (effects/delta (get quantities quantity) delta))
                               (get-in expected [:effects]))]
    (testing "all-active allocation is meaningful and has no failure dispositions"
      (is (= 100 (get-in first-run [:allocation :allocated-total])))
      (is (zero? (get-in first-run [:allocation :unallocated-residual])))
      (is (= {:claim/alice 60 :claim/bob 40}
             (into {} (map (juxt :row/id :allocated) (get-in first-run [:allocation :rows]))))))
    (testing "allocation compilation and ordered transaction exactly preserve canonical effects"
      (is (= expected-effects (get-in first-run [:canonical-transition :effects])))
      (is (= (mapv #(select-keys % [:quantity/root :delta]) expected-effects)
             (mapv #(select-keys % [:quantity/root :delta])
                   (get-in first-run [:transaction :operations]))))
      (is (= (:effects/root (:compilation first-run))
             (:effects/root (:canonical-transition first-run))))
      (is (= 5 (get-in first-run [:trace :trace/length])))
      (is (= :effect-exact (get-in first-run [:binding :binding/mode]))))
    (testing "the synthetic protocol realization preserves all fields outside the exact write set"
      (let [after (modeled-reconstruct quantities (:modeled-protocol-before case)
                                       (get-in first-run [:canonical-transition :state-after])
                                       (:write-set case))]
        (is (= (:workflow (:modeled-protocol-before case)) (:workflow after)))
        (is (= (get-in (:modeled-protocol-before case) [:claims :alice :metadata])
               (get-in after [:claims :alice :metadata])))
        (is (= (get-in (:modeled-protocol-before case) [:claims :bob :metadata])
               (get-in after [:claims :bob :metadata])))))
    (testing "all derived commitments and canonical bytes are deterministic"
      (is (= (:projections first-run) (:projections second-run)))
      (is (= (:canonical-bytes first-run) (:canonical-bytes second-run)))
      (is (= (get-in expected [:roots]) (:projections first-run)))
      (is (= (get-in expected [:canonical-bytes]) (:canonical-bytes first-run)))
      (is (= (get-in first-run [:binding :canonical-transition/root])
             (get-in first-run [:protocol-realization :canonical-transition/root])
             (get-in first-run [:protocol-transaction-realization :canonical-transition/root]))))))

(deftest composition-rejects-transplanted-or-expanded-artifacts
  (let [{:keys [canonical-transition binding protocol-realization protocol-transaction-realization transaction trace]} (snapshot)
        transition-root (:canonical-effect-transition/root canonical-transition)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (join/build {:canonical-transition-root transition-root
                              :transition-binding (assoc binding :canonical-transition/root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
                              :protocol-effect-realization protocol-realization})))
    (is (false? (join/valid? (assoc protocol-transaction-realization :binding/mode :net-equivalent)
                             binding protocol-realization)))
    (is (false? (join/valid? (assoc protocol-transaction-realization :unexpected true)
                             binding protocol-realization)))
    (let [reordered-transaction
          (transact/build-transaction
           {:operations (mapv (fn [operation]
                                {:quantity-root (:quantity/root operation)
                                 :delta (:delta operation)})
                              (reverse (:operations transaction)))
            :operation-semantics-root (:operation-semantics/root transaction)
            :trace-policy-root (:trace-policy/root transaction)})
          reordered-trace (transact/execute (:canonical-before (snapshot)) reordered-transaction
                                            canonical-transition {:max-fixed-steps 1 :max-steps-per-effect 1})
          reordered-binding (transact/bind-transition canonical-transition reordered-transaction reordered-trace
                                                      (:binding-semantics/root
                                                       (transact/build-binding-semantics :effect-exact)))]
      ;; Effect-exact is sequence-not-exact, but an ordered procedure has a
      ;; distinct commitment and cannot be transplanted under the old join.
      (is (not= (:transact/root transaction) (:transact/root reordered-transaction)))
      (is (not= (:trace-bounded-transition/root trace) (:trace-bounded-transition/root reordered-trace)))
      (is (false? (join/valid? protocol-transaction-realization reordered-binding protocol-realization))))))
