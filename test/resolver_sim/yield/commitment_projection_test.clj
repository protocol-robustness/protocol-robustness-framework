(ns resolver-sim.yield.commitment-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.commitment-projection :as cp]
            [resolver-sim.yield.transition-basis :as basis]
            [resolver-sim.yield.partial-fill :as pf]))

(defn- rational
  "Assert a projected number equals the expected reduced numerator/denominator."
  [v n d]
  (is (= {:yield.number/schema :yield-rational.v1
          :yield.number/numerator (bigint n)
          :yield.number/denominator (bigint d)}
         (cp/project-yield-number v))))

(deftest project-yield-number-reduced-rational-equivalence
  (testing "decimal and rational spellings of the same economic value commit identically"
    (is (= (cp/project-yield-number 0.05)
           (cp/project-yield-number 1/20)))
    (is (= (cp/project-yield-number 0.050)
           (cp/project-yield-number 1/20)))
    (is (= (cp/project-yield-number 5e-2)
           (cp/project-yield-number 1/20)))
    (is (= (cp/project-yield-number 1.25)
           (cp/project-yield-number 5/4)))
    (is (= (cp/project-yield-number 0.0)
           (cp/project-yield-number 0)))))

(deftest project-yield-number-reduced-form
  (testing "reducible ratios, integers, zero, negatives"
    (rational 6/8 3 4)
    (rational 5 5 1)
    (rational 0 0 1)
    (rational 0.0 0 1)
    (rational -3/6 -1 2)
    (rational -0.5 -1 2)
    (rational 10000/1 10000 1)
    (rational 1/20 1 20)
    (rational 1.25 5 4)))

(deftest project-yield-number-invariants
  (letfn [(gcd [a b]
            (loop [a (Math/abs (long a)) b (Math/abs (long b))]
              (if (zero? b) a (recur b (mod a b)))))]
    (testing "reduced-rational invariants: positive denominator, gcd(|n|,d)=1, zero is 0/1"
      (doseq [v [0 5 1/20 0.05 -3/6 10000/1 1.25]]
        (let [p (cp/project-yield-number v)
              numerator (:yield.number/numerator p)
              denominator (:yield.number/denominator p)]
          (is (pos? denominator) (str v " denominator positive"))
          (is (= 1 (bigint (gcd numerator denominator))) (str v " reduced"))
          (when (zero? v)
            (is (and (zero? numerator) (= 1 denominator)) (str v " zero is 0/1"))))))))

(deftest project-yield-number-rejects-non-finite
  (testing "NaN and ±Infinity reject"
    (doseq [v [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"non-finite"
                            (cp/project-yield-number v))))))

(deftest project-yield-number-rejects-unsupported-numeric
  (testing "unsupported numeric classes reject rather than silently coercing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported numeric"
                          (cp/project-yield-number (java.util.concurrent.atomic.AtomicInteger. 1))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported numeric"
                          (cp/project-yield-number :not-a-number)))))

(def golden-state-world
  {:yield/held-balances {"USDC" 10000}
   :yield/indices {:yield.provider/liquid-lending {:USDC 1/20}}
   :yield/positions {"vault" {:owner/id "vault"
                              :entry-index 1/1
                              :shares 10000/1
                              :principal 10000
                              :realized-yield 0
                              :unrealized-yield 1/2
                              :deferred-yield 0
                              :haircut-yield 0
                              :accrual-dust-remainder 0
                              :principal-impairment 0
                              :original-priority 0
                              :module/id :yield.provider/liquid-lending
                              :position/id "vault"
                              :token :USDC
                              :status :active}}
   :yield/withdrawal-ledger []
   :yield/partial-fill-decisions {}})

(def golden-policy-world
  {:yield/risk {:yield.provider/liquid-lending
                {:USDC {:liquidity-mode :available
                        :loss-mode :none
                        :rate-mode :deterministic
                        :failure-modes #{}
                        :shortfall {:available-ratio 0.5
                                    :reason "liquidity-shortfall"}}}}
   :yield/rates {:yield.provider/liquid-lending {:USDC 0.05}}
   :yield/shortfall-models {:yield.provider/liquid-lending {:USDC nil}}
   :yield/withdrawal-policies {:yield.provider/liquid-lending {:USDC nil}}})

(deftest state-projection-is-deterministic-and-closed
  (let [a (cp/project-yield-state golden-state-world)
        b (cp/project-yield-state golden-state-world)
        noisy (assoc-in golden-state-world [:yield/modules :ops] (fn [] 1))]
    (is (= a b))
    (is (= a (cp/project-yield-state noisy))
        "runtime fields outside the committed projection are ignored"))
  (let [bad (assoc-in golden-state-world [:yield/positions "vault" :bad] (fn [] 1))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported value"
                          (cp/project-yield-state bad)))))

(deftest policy-projection-is-deterministic-and-closed
  (let [a (cp/project-effective-policy golden-policy-world)
        b (cp/project-effective-policy golden-policy-world)
        noisy (assoc-in golden-policy-world [:yield/schedules :runtime] (fn [] 1))]
    (is (= a b))
    (is (= a (cp/project-effective-policy noisy))
        "runtime fields outside the committed policy are ignored")))

(deftest state-root-independent-reconstruction
  (let [projected (cp/project-yield-state golden-state-world)
        direct (hc/domain-hash :prf-yield-state-v1 projected)]
    (is (= direct (basis/yield-state-root golden-state-world)))
    (is (= "8dbb07f36dc11e5964d11ce7a421ec0b5ab21715dfc0e354c29680dfa6c3e602"
           direct)
        "golden state root for the normative commitment projection")))

(deftest policy-root-independent-reconstruction
  (let [projected (cp/project-effective-policy golden-policy-world)
        direct (hc/domain-hash :prf-yield-effective-policy-v1 projected)]
    (is (= direct (basis/effective-policy-root golden-policy-world)))
    (is (= "c54eb226c0a26780008718f4b58a2e6821d7d43ff97e1079c4e396df49caf36f"
           direct)
        "golden effective-policy root for the normative commitment projection")))

(deftest mutation-sensitivity
  (let [state-mutated (assoc-in golden-state-world
                                [:yield/indices :yield.provider/liquid-lending :USDC]
                                2)
        policy-mutated (assoc-in golden-policy-world
                                 [:yield/rates :yield.provider/liquid-lending :USDC]
                                 0.06)]
    (is (not= (basis/yield-state-root golden-state-world)
              (basis/yield-state-root state-mutated)))
    (is (not= (basis/effective-policy-root golden-policy-world)
              (basis/effective-policy-root policy-mutated)))))

(def golden-cutpoint-world
  (merge golden-state-world
         {:yield/risk {:yield.provider/liquid-lending
                       {:USDC {:liquidity-mode :available
                               :loss-mode :none
                               :failure-modes #{}
                               :shortfall {:available-ratio 0.5
                                           :reason "liquidity-shortfall"}}}}
          :yield/shortfall-models {:yield.provider/liquid-lending {:USDC nil}}
          :yield/withdrawal-policies {:yield.provider/liquid-lending {:USDC nil}}}))

(defn- world-with-index
  "The golden cutpoint world with `:yield/indices :yield.provider/liquid-lending
   :USDC` replaced by `v`."
  [v]
  (assoc-in golden-cutpoint-world [:yield/indices :yield.provider/liquid-lending :USDC] v))

(deftest cutpoint-projection-reduced-rational-equivalence
  (testing "decimal and rational spellings of the same economic index commit
            identically in the cutpoint projection"
    (is (= (cp/project-cutpoint-state (world-with-index 0.05))
           (cp/project-cutpoint-state (world-with-index 1/20))))
    (is (= (cp/project-cutpoint-state (world-with-index 0.050))
           (cp/project-cutpoint-state (world-with-index 1/20))))
    (is (= (cp/project-cutpoint-state (world-with-index (java.math.BigDecimal. "0.05")))
           (cp/project-cutpoint-state (world-with-index 1/20)))))
  (testing "economically different values never collapse"
    (is (not= (cp/project-cutpoint-state (world-with-index 1/20))
              (cp/project-cutpoint-state (world-with-index 51/1000))))))

(deftest cutpoint-root-representation-independence
  (testing "representation-independent state-after root implies the same
            representation-independent cutpoint root (0.05 ≡ 0.050 ≡ BigDecimal
            0.05 ≡ 1/20)"
    (let [states (map world-with-index [0.05 0.050 (java.math.BigDecimal. "0.05") 1/20])
          state-roots (map basis/yield-state-root states)
          cutpoint-roots (map pf/ledger-state-cutpoint-root-v2 states)]
      (is (apply = state-roots)
          "all spellings share the same state-after root")
      (is (apply = cutpoint-roots)
          "all spellings share the same V2 cutpoint root"))
    (is (= (pf/ledger-state-cutpoint-root-v2 (world-with-index 0.05))
           (pf/ledger-state-cutpoint-root-v2 (world-with-index 1/20))))
    (testing "the negative case does not collapse"
      (is (not= (pf/ledger-state-cutpoint-root-v2 (world-with-index 1/20))
                (pf/ledger-state-cutpoint-root-v2 (world-with-index 51/1000)))))))

(deftest cutpoint-root-golden-commitments
  (is (= "sha256:928a9fd6fe797eec7ceae2ab1e9de556155603d3092e377ccea11d5069ed7ada"
         (pf/ledger-state-cutpoint-root-v1 golden-cutpoint-world))
      "V1 cutpoint root is byte-stable (legacy raw host representation)")
  (is (= "sha256:1168404dcf36139b794ce644b570245f9c14a1afd9b24c1d665f8a0d94a216d2"
         (pf/ledger-state-cutpoint-root-v2 golden-cutpoint-world))
      "V2 cutpoint root for the normative normalized commitment projection")
  (testing "V1 and V2 are distinct identities for a representation-sensitive world"
    (is (not= (pf/ledger-state-cutpoint-root-v1 golden-cutpoint-world)
              (pf/ledger-state-cutpoint-root-v2 golden-cutpoint-world)))))

(deftest cutpoint-root-schema-dispatch
  (testing "the default arity (new writes) is V2"
    (is (= (pf/ledger-state-cutpoint-root golden-cutpoint-world)
           (pf/ledger-state-cutpoint-root-v2 golden-cutpoint-world))))
  (testing "absent or V1 schema dispatches to the legacy byte-stable commitment"
    (is (= (pf/ledger-state-cutpoint-root golden-cutpoint-world nil)
           (pf/ledger-state-cutpoint-root-v1 golden-cutpoint-world)))
    (is (= (pf/ledger-state-cutpoint-root golden-cutpoint-world
                                          :yield/withdrawal-ledger-state-cutpoint-v1)
           (pf/ledger-state-cutpoint-root-v1 golden-cutpoint-world))))
  (testing "V2 schema dispatches to the normalized commitment"
    (is (= (pf/ledger-state-cutpoint-root golden-cutpoint-world
                                          :yield/withdrawal-ledger-state-cutpoint-v2)
           (pf/ledger-state-cutpoint-root-v2 golden-cutpoint-world))))
  (testing "an unknown schema fails closed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unknown withdrawal ledger state cutpoint schema"
                          (pf/ledger-state-cutpoint-root golden-cutpoint-world
                                                         :yield/withdrawal-ledger-state-cutpoint-v9)))))

(deftest event-time-is-protocol-native
  (testing "integer block/step time is accepted"
    (is (= 2 (:time (cp/project-event {:seq 1 :time 2 :agent "u" :action "x" :params {}})))))
  (testing "host temporal types are rejected with a domain-specific error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"yield event time must be a protocol-native integer"
                          (cp/project-event {:seq 1 :time (java.time.Instant/ofEpochSecond 1000)
                                             :agent "u" :action "x" :params {}}))))
  (testing "non-integer times are rejected before the canonical encoder"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"yield event time must be a protocol-native integer"
                          (cp/project-event {:seq 1 :time "1000" :agent "u" :action "x" :params {}})))))