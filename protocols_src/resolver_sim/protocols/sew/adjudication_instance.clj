(ns resolver-sim.protocols.sew.adjudication-instance
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]))

(def schema "sew/adjudication-instance.v1")
(def domain :sew-adjudication-instance-v1)
(def ^:private root-key :sew/adjudication-instance/root)
(def ^:private fields #{:artifact/schema :workflow/id :adjudication/round-id :case/root
                        :adjudicator/type :adjudicator/principal :protocol-instance/root
                        :temporal-basis/root :adjudication/parent-root})
(defn- root [x] (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe (dissoc x root-key)))) )
(defn validate-instance [x]
  (let [missing (set/difference fields (set (keys x))) extra (set/difference (set (keys x)) (conj fields root-key))
        refs #{:case/root :protocol-instance/root :temporal-basis/root}]
    {:valid? (and (map? x) (empty? missing) (empty? extra) (= schema (:artifact/schema x))
                  (integer? (:workflow/id x)) (integer? (:adjudication/round-id x))
                  (= :sew/resolver (:adjudicator/type x))
                  (and (string? (:adjudicator/principal x))
                       (seq (:adjudicator/principal x)))
                  (every? #(ref/valid-sha256-ref? (get x %)) refs)
                  (or (nil? (:adjudication/parent-root x)) (ref/valid-sha256-ref? (:adjudication/parent-root x)))
                  (= (get x root-key) (root x)))}))
(defn freeze-instance! [world workflow-id]
  (let [round (t/dispute-level world workflow-id) published (get-in world [:adjudication-published-cases workflow-id round])
        prior-root (get-in world [:adjudication-current-instance-root workflow-id])
        prior (get-in world [:adjudication-instances workflow-id prior-root])]
    (cond
      (nil? published) {:ok false :error :missing-published-adjudication-case :world world}
      (and prior (not= round (inc (:adjudication/round-id prior)))) {:ok false :error :adjudication-instance-round-conflict :world world}
      :else (let [base {:artifact/schema schema :workflow/id workflow-id :adjudication/round-id round
                        :case/root (:case/root published)
                        :adjudicator/type :sew/resolver
                        :adjudicator/principal (get-in world [:escrow-transfers workflow-id :dispute-resolver])
                        :protocol-instance/root (ref/sha256-ref
                                                 (hc/domain-hash :sew-protocol-instance-v1
                                                                 (hc/project-canonical-safe {:workflow/id workflow-id})))
                        :temporal-basis/root (ref/sha256-ref (hc/domain-hash :sew-adjudication-temporal-basis-v1 (hc/project-canonical-safe {:block-ts (time-ctx/block-ts world)})))
                        :adjudication/parent-root prior-root}
                  instance (assoc base root-key (root base)) existing (get-in world [:adjudication-instances workflow-id (:sew/adjudication-instance/root instance)])]
              (if existing {:ok true :world world :instance existing :idempotent? true}
                  {:ok true :instance instance :world (-> world (assoc-in [:adjudication-instances workflow-id (:sew/adjudication-instance/root instance)] instance) (assoc-in [:adjudication-current-instance-root workflow-id] (:sew/adjudication-instance/root instance)))})))))
