(ns resolver-sim.yield.modules.fixed
  "Fixed-rate yield module implementation."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.evidence.capture :as evidence]))

(defn- normalize-token [token]
  (tok/normalize token))

(defn fixed-deposit [world module op]
  (attr/with-attribution {:deposit/module-id (:module/id module)
                          :deposit/position-id (:owner/id op)
                          :deposit/token (:token op)}
    (let [oid    (:owner/id op)
          amount (:amount op)
          token  (:token op)
          pos (model/make-position {:owner/id oid
                                    :module/id (:module/id module)
                                    :token token
                                    :principal amount
                                    :shares amount
                                    :entry-index 1.0})
          world' (assoc-in world [:yield/positions oid] pos)]
      (evidence/capture-event-evidence!
       :yield-deposit
       {:deposit/before-positions (:yield/positions world)}
       {:deposit/after-positions (:yield/positions world')}
       {:deposit/params {:owner/id oid :amount amount :token token :module/id (:module/id module)}}
       nil
       {:world-before world
        :world-after world'})
      world')))

(defn fixed-accrue [world module op]
  (attr/with-attribution {:accrue/module-id (:module/id module)
                          :accrue/token (:token op)}
    (let [{:keys [token dt]} op
          mid (:module/id module)
          ms (market-state/get-market-state world mid token (time-ctx/block-ts world))
          apy (:apy ms 0.05)
          old-index (or (get-in world [:yield/indices mid token]) 1.0)
          new-index (if-let [fixed-index (:index ms)]
                      fixed-index
                      (+ old-index (/ (* apy dt) time-ctx/seconds-per-year)))
          world-after-index (assoc-in world [:yield/indices mid token] new-index)
          world' (reduce (fn [w [oid pos]]
            (if (and (= (:module/id pos) mid)
                                    (= (normalize-token (:token pos)) (normalize-token token))
                                    (= (:status pos) :active))
                              (let [old-yield (:unrealized-yield pos 0)
                                    now       (time-ctx/block-ts world)
                                    updated   (acct/update-position-yield world pos new-index)
                                    updated'  (assoc updated :last-accrual-time now)
                                    yield-delta (- (:unrealized-yield updated 0) old-yield)]
                                (-> w
                                    (assoc-in [:yield/positions oid] updated')
                                    (update-in [:total-yield-generated token] (fnil + 0) yield-delta)))
                             w))
                         world-after-index
                         (:yield/positions world))]
      (evidence/capture-event-evidence!
       :yield-accrue
       {:accrue/before-indices (:yield/indices world)
        :accrue/before-positions (:yield/positions world)}
       {:accrue/after-indices (:yield/indices world')
        :accrue/after-positions (:yield/positions world')}
       {:accrue/params {:module-id mid :token token :new-index new-index}}
       nil
       {:world-before world
        :world-after world'})
      world')))

(defn fixed-withdraw [world module op]
  (attr/with-attribution {:withdraw/module-id (:module/id module)
                          :withdraw/position-id (:owner/id op)}
    (let [oid     (:owner/id op)
          pos-key [:yield/positions oid]
          pos     (get-in world pos-key)]
      (if (nil? pos)
        world
        (let [mid   (:module/id pos)
              token (:token pos)
              current-index (or (get-in world [:yield/indices mid token])
                                (:entry-index pos 1.0))
              marked-pos (acct/update-position-yield world pos current-index)
              world' (-> world
                         (assoc-in pos-key (assoc marked-pos :status :withdrawn)))]
          (evidence/capture-event-evidence!
           :yield-withdraw
           {:withdraw/before-positions (:yield/positions world)}
           {:withdraw/after-positions (:yield/positions world')}
           {:withdraw/params {:owner/id oid :module/id mid :token token}}
           nil
           {:world-before world
            :world-after world'})
          world')))))

(defn make-fixed-module
  "Build a declarative fixed-rate module record (rates via world/config)."
  ([module-id]
   (make-fixed-module module-id :fixed-rate))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue}
    :accounting/type :principal
    :ops {:yield/deposit fixed-deposit
          :yield/withdraw fixed-withdraw
          :yield/accrue fixed-accrue}}))

(def fixed-rate-module
  (make-fixed-module :fixed-rate))