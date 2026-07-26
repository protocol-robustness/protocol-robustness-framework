(ns resolver-sim.properties.invariants-test
  "Generator property checks for Sew protocol scenario replay and invariants."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.generators.scenario :as scenario]
            [resolver-sim.generators.stateful :as stateful]
            [resolver-sim.properties.harness :as pbh]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as inv]))

(deftest generated-scenario-preserves-baseline-invariants
  "For all seeds, a generated scenario replays successfully and the resulting
   world passes all canonical invariants. Distinguishes three failure modes:
     - generator produced invalid input (outcome :invalid)
     - replay rejected a valid scenario (outcome :fail, halt-reason set)
     - replay completed but an invariant failed"
  (let [prop (prop/for-all [seed (gen/large-integer* {:min 1 :max 100000})]
                (let [sc (scenario/build-scenario {:seed seed :max-steps 4})
                      r  (sew/replay-with-sew-protocol sc)
                      world (:world r)
                      all-ok? (:all-hold? (inv/check-all world))]
                  (and (not= :invalid (:outcome r))
                       (not= :fail (:outcome r))
                       (nil? (:halt-reason r))
                       all-ok?)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest generated-scenario-is-deterministic-for-seed
  (let [a (scenario/build-scenario {:seed 4242 :max-steps 4})
        b (scenario/build-scenario {:seed 4242 :max-steps 4})]
    (is (= (:events a) (:events b)))))

(deftest adversarial-profile-generation-is-deterministic
  (let [a (scenario/build-scenario {:seed 9001 :max-steps 6 :profile :timeout-boundary})
        b (scenario/build-scenario {:seed 9001 :max-steps 6 :profile :timeout-boundary})]
    (is (= (:events a) (:events b)))
    (is (= :timeout-boundary (:generator-profile a)))))

(deftest adversarial-profiles-remain-replay-valid
  (let [profiles [:timeout-boundary :same-block-ordering :dispute-flooding :withdrawal-under-exposure]
        results  (for [p profiles]
                   (let [sc (scenario/build-scenario {:seed 2026 :max-steps 6 :profile p})
                         r  (sew/replay-with-sew-protocol sc)]
                     {:profile p :outcome (:outcome r) :halt-reason (:halt-reason r)}))]
    (is (every? #(not= :invalid (:outcome %)) results) (pr-str results))))

(deftest intents-interpreter-is-shrink-friendly
  (let [context (proto/build-execution-context sew/protocol
                                               scenario/default-agents
                                               scenario/default-protocol-params)
        world0  (proto/init-world sew/protocol {:initial-block-time 1000})
        failing-prop
        (prop/for-all [intents (gen/vector gen/nat 0 8)]
                      (let [{:keys [events]} (stateful/generate-event-sequence-from-intents
                                              {:intents intents
                                               :context context
                                               :world0 world0
                                               :profile :phase1-lifecycle
                                               :initial-time 1000})]
            ;; Intentionally strict to force shrink and verify failure minimization path.
                        (<= (count events) 1)))
        res (tc/quick-check 60 failing-prop)]
    (is (false? (:pass? res)) (pr-str res))
    (is (vector? (get-in res [:shrunk :smallest])) (pr-str res))))
