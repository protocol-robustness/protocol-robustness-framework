(ns resolver-sim.evidence.staged-capture-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.capture :as capture]
            [resolver-sim.evidence.staged-capture :as staged]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.io.event-evidence :as event-evidence]
            [resolver-sim.io.content-addressed-store :as store]
            [resolver-sim.protocols.sew.held-custody-test-env :as env])
  (:import [java.nio.file Files]))

(defn- evidence [id]
  (capture/finalize-evidence {:evidence/type :test/event :event/id id}))

(defn- backend []
  (store/create-store (str (Files/createTempDirectory "resolver-sim-staged-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest sealed-capture-is-immutable-and-content-addressed
  (let [session (staged/begin-capture-attempt {:attempt-id :attempt/one})
        first (evidence :first)
        _ (staged/stage-event! session first)
        sealed (staged/seal-capture! session)]
    (is (staged/valid-staged-capture? sealed))
    (is (= (:evidence/hash first) (:staged-evidence/head sealed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sealed"
                          (staged/stage-event! session (evidence :late))))
    (is (= sealed (staged/seal-capture! session)))))

(deftest staged-preparation-matches-normal-capture-before-chain-injection
  (let [root (str (Files/createTempDirectory "resolver-sim-capture-parity-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        args [:test/parity {:before 1} {:after 2} {:input :value} {:calculation 3}
              {:importance :core}]
        prepared (apply event-evidence/prepare-event-evidence args)]
    (chain/with-fresh-evidence-context*
      #(binding [evidence-config/*artifact-dir* root]
         (let [captured (apply event-evidence/capture-event-evidence! args)]
           (is (= prepared
                  (dissoc captured :evidence/chain-hash-scheme :evidence/chain-seq
                          :evidence/chain-prev-hash :evidence/chain-self-hash)))))))
  (doseq [args [[:test/arity {:before 1} {:after 2} {:input :value}]
                [:test/arity {:before 1} {:after 2} {:input :value} {:calc 1}]
                [:test/arity {:before 1} {:after 2} {:input :value} {:calc 1} {:importance :core}]]]
    (let [session (staged/begin-capture-attempt {:attempt-id :attempt/arities})]
      (apply (staged/staged-capture-fn session) args)
      (is (= 1 (:staged-evidence/count (staged/seal-capture! session)))))))

(deftest public-sew-actions-can-use-an-isolated-staging-backend
  (let [session (staged/begin-capture-attempt {:attempt-id :attempt/public})]
    (binding [capture/*capture-event-evidence!* (staged/staged-capture-fn session)]
      (let [{:keys [result]} (env/public-disputed-world)
            sealed (staged/seal-capture! session)]
        (is (:ok result))
        (is (= 2 (:staged-evidence/count sealed)))
        (is (staged/valid-staged-capture? sealed))))))

(deftest staged-root-commits-record-order-and-persists-unlinked
  (let [a (evidence :a)
        b (evidence :b)
        first-session (staged/begin-capture-attempt {:attempt-id :attempt/order})
        second-session (staged/begin-capture-attempt {:attempt-id :attempt/order})
        _ (staged/stage-event! first-session a)
        _ (staged/stage-event! first-session b)
        _ (staged/stage-event! second-session b)
        _ (staged/stage-event! second-session a)
        first-capture (staged/seal-capture! first-session)
        second-capture (staged/seal-capture! second-session)
        backend (backend)
        persisted (staged/prepare-capture! first-session backend store/put-if-absent!)]
    (is (not= (:staged-evidence/root first-capture) (:staged-evidence/root second-capture)))
    (is (= :created (:status persisted)))
    (is (= first-capture
           (store/resolve-artifact backend (:staged-evidence/root first-capture))))))
