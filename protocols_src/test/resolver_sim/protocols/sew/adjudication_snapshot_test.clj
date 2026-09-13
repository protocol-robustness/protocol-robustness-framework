(ns resolver-sim.protocols.sew.adjudication-snapshot-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.adjudication-snapshot :as snapshot]))

(defn- root [x] (str "sha256:" (hc/domain-hash :evidence-record {:x x})))
(defn- source [state]
  {:created-from-state-root state
   :dispute/question-root (root :question)
   :dispute/evidence-set-root (root :evidence)
   :authority/resolution-basis-root (root :authority-basis)
   :authority/context-root (root :authority-context)
   :configuration/root (root :configuration)
   :created-at 1000})

(deftest snapshots-are-rooted-immutable-and-parent-linked
  (let [state (root :state)
        first (snapshot/freeze-adjudication! {:dispute-levels {7 0}} 7 state (source state))
        second (snapshot/freeze-adjudication! (:world first) 7 (root :state-1) (source (root :state-1)))]
    (is (:ok first))
    (is (:ok second))
    (is (:valid? (snapshot/validate-adjudication-snapshot (:snapshot first))))
    (is (nil? (:adjudication/parent-snapshot-root (:snapshot first))))
    (is (= (:sew/adjudication-snapshot-root (:snapshot first))
           (:adjudication/parent-snapshot-root (:snapshot second))))
    (is (= 1 (:adjudication/round-id (:snapshot second))))
    (let [retry (snapshot/freeze-adjudication! (:world first) 7 state (source state))]
      (is (:idempotent? retry))
      (is (= (:sew/adjudication-snapshot-root (:snapshot first))
             (:sew/adjudication-snapshot-root (:snapshot retry))))
      (is (= 1 (count (get-in (:world retry) [:adjudication-snapshots 7])))))
    (is (= :adjudication-source-fence-mismatch
           (:error (snapshot/freeze-adjudication! {} 7 state (source (root :other))))))))
