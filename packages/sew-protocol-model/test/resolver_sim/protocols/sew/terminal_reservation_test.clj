(ns resolver-sim.protocols.sew.terminal-reservation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]))

(defn- hash-ref [label] (str "sha256:" (format "%064x" (hash label))))
(defn- digest [label] (format "%064x" (hash label)))
(def reserved {:status :reserved :research-assignment/hash (hash-ref :assignment)
               :researcher-force-authorisation/hash (hash-ref :authorisation)
               :researcher-force-authorisation/consumption-key :consumption/key
               :sew/authorization-id "fa-0" :sew/scope-hash (digest :scope)
               :reservation/hash (hash-ref :reservation)
               :reservation/execution-attempt-id :attempt/one})
(def produced-terminal {:status :consumed :consumption/effect-outcome :produced
                        :outcome-manifest/hash (hash-ref :manifest) :correlation/hash (hash-ref :correlation)
                        :consumption/receipt-hash (hash-ref :receipt) :execution-evidence/hash (hash-ref :evidence)
                        :sew/world-before-root (hash-ref :before) :sew/world-after-root (hash-ref :after)
                        :sew/world-snapshot-hash (hash-ref :snapshot) :event-evidence/root (hash-ref :events)})

(deftest exact-terminal-cas-retains-anchors-and-is-idempotent
  (let [registry (atom {:consumption/key reserved})
        first (sew/finalize-consensus-reservation! registry :consumption/key reserved produced-terminal)
        second (sew/finalize-consensus-reservation! registry :consumption/key reserved produced-terminal)
        record (:binding first)]
    (is (= :new (:mode first))) (is (= :resumed (:mode second)))
    (is (sew/valid-consensus-terminal-reservation? record))
    (is (= (:reservation/hash reserved) (:reservation/hash record)))
    (is (= (:sew/scope-hash reserved) (:sew/scope-hash record)))
    (is (false? (:committed? (sew/finalize-consensus-reservation!
                              registry :consumption/key reserved
                              (assoc produced-terminal :execution-evidence/hash (hash-ref :other))))))))

(deftest terminal-cas-is-linearizable-under-contention
  (let [registry (atom {:consumption/key reserved})
        start (promise)
        left (assoc produced-terminal :execution-evidence/hash (hash-ref :left))
        right (assoc produced-terminal :execution-evidence/hash (hash-ref :right))
        run (fn [terminal]
              (future @start (sew/finalize-consensus-reservation!
                              registry :consumption/key reserved terminal)))
        left-run (run left)
        right-run (run right)
        results (do (deliver start true) [(deref left-run) (deref right-run)])
        winner (first (filter :committed? results))
        loser (first (remove :committed? results))]
    (is (= 1 (count (filter :committed? results))))
    (is (= :terminal-binding-conflict (:reason loser)))
    (is (= (:binding winner) (get @registry :consumption/key)))
    (is (= :resumed
           (:mode (sew/finalize-consensus-reservation!
                   registry :consumption/key reserved
                   (select-keys (:binding winner) (keys produced-terminal))))))))

(deftest terminal-record-hash-commits-every-field
  (let [registry (atom {:consumption/key reserved})
        record (:binding (sew/finalize-consensus-reservation!
                          registry :consumption/key reserved produced-terminal))]
    (is (sew/valid-consensus-terminal-reservation? record))
    (doseq [key (disj (set (keys record)) :terminal/hash)]
      (is (not (sew/valid-consensus-terminal-reservation?
                (assoc record key (if (= key :status) :failed-after-consumption
                                      (str "tampered-" (name key))))))
          (str "tampering " key " invalidates terminal record")))))

(deftest terminal-cas-enforces-complete-status-outcome-table
  (doseq [[status outcome correlation?] [[:consumed :produced true]
                                         [:failed-after-consumption :produced true]
                                         [:failed-after-consumption :not-produced false]
                                         [:rolled-back-after-consumption :reversed true]]]
    (let [registry (atom {:consumption/key reserved})
          terminal (cond-> (assoc produced-terminal :status status :consumption/effect-outcome outcome)
                     (not correlation?) (dissoc :correlation/hash))]
      (is (= :new (:mode (sew/finalize-consensus-reservation! registry :consumption/key reserved terminal))))))
  (doseq [[status outcome] [[:consumed :not-produced] [:consumed :reversed]
                            [:failed-after-consumption :reversed]
                            [:rolled-back-after-consumption :produced]
                            [:rolled-back-after-consumption :not-produced]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sew/finalize-consensus-reservation!
                  (atom {:consumption/key reserved}) :consumption/key reserved
                  (assoc produced-terminal :status status :consumption/effect-outcome outcome)))))
  (let [no-effect (-> produced-terminal (assoc :status :failed-after-consumption
                                                :consumption/effect-outcome :not-produced)
                      (dissoc :correlation/hash))]
    (is (not (contains? no-effect :correlation/hash)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sew/finalize-consensus-reservation!
                  (atom {:consumption/key reserved}) :consumption/key reserved
                  (assoc no-effect :unknown :rejected))))))
