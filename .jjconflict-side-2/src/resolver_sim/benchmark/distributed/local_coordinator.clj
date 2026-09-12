(ns resolver-sim.benchmark.distributed.local-coordinator
  "Topology-neutral in-memory fixed-chunk coordination."
  (:require [resolver-sim.benchmark.distributed.chunk-result :as result]))

(defprotocol FixedChunkCoordinator
  (register-run! [coordinator run-id fixed-chunk-set])
  (claim-chunk! [coordinator run-id claim])
  (complete-chunk! [coordinator run-id completion])
  (mark-run-execution-complete! [coordinator run-id])
  (resolve-chunk-completion! [coordinator run-id chunk-id]))

(defn- now-ms [{:keys [now-ms]}]
  (long (or now-ms (System/currentTimeMillis))))

(defn- descriptor [fixed-set chunk]
  {:chunk/id (:chunk/id chunk)
   :run-plan/root (:run-plan/root fixed-set)
   :execution-plan/root (:execution-plan/root fixed-set)
   :chunk/expected-input-root (:chunk/expected-input-root chunk)
   :chunk/expected-work-root (:chunk/expected-work-root chunk)
   :chunk/expected-sensitivity-root (:chunk/expected-sensitivity-root chunk)
   :chunk/expected-executable-distribution-root (:chunk/expected-executable-distribution-root chunk)
   :chunk/execution-ids (:chunk/execution-ids chunk)})

(defn- manifest-matches? [chunk manifest]
  (and (result/verify-manifest manifest)
       (= (:chunk/id chunk) (:chunk/id manifest))
       (= (:run-plan/root chunk) (:run-plan/root manifest))
       (= (:execution-plan/root chunk) (:execution-plan/root manifest))
       (= (:chunk/expected-input-root chunk) (:chunk/input-root manifest))
       (= (:chunk/expected-work-root chunk) (:chunk/work-root manifest))
       (= (:chunk/expected-sensitivity-root chunk) (:sensitivity/root manifest))
       (= (:chunk/expected-executable-distribution-root chunk)
          (:executable-distribution/root manifest))
       (= (:chunk/execution-ids chunk) (:chunk/execution-ids manifest))))

(defn- claimable? [now-ms chunk]
  (or (= :pending (:status chunk))
      (and (= :leased (:status chunk)) (<= (:lease/expires-at chunk) now-ms))))

(defn- valid-positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- local-key-request [claim]
  (select-keys claim [:local-key/id :local-key/capacity :local-key/budget]))

(defn- local-key-requested? [claim]
  (seq (local-key-request claim)))

(defn- valid-local-key-request? [claim]
  (let [{:local-key/keys [id capacity budget]} (local-key-request claim)]
    (and (string? id) (not (empty? id))
         (valid-positive-integer? capacity)
         (valid-positive-integer? budget))))

(defn- active-local-key-usage [run now local-key-id]
  (count (filter #(and (= :leased (:status %))
                       (> (:lease/expires-at %) now)
                       (= local-key-id (:local-key/id %)))
                 (vals (:chunks run)))))

(defn- capacity-refusal [run now claim]
  (let [{:local-key/keys [id capacity budget]} (local-key-request claim)
        in-use (active-local-key-usage run now id)]
    {:claim/status :unavailable
     :reason :execution/local-capacity-exceeded
     :local-key/id id
     :local-key/capacity capacity
     :local-key/budget budget
     :local-key/in-use in-use}))

(defrecord LocalCoordinator [state]
  FixedChunkCoordinator
  (register-run! [_ run-id fixed-set]
    (let [chunks (into {}
                       (map (fn [chunk]
                              [(:chunk/id chunk) (assoc (descriptor fixed-set chunk)
                                                        :status :pending :fence 0)])
                            (:chunks fixed-set)))
          candidate {:fixed-set fixed-set :status :dispatching :chunks chunks}]
      (swap! state update run-id
             (fn [existing]
               (cond
                 (nil? existing) candidate
                 (= (:fixed-set existing) fixed-set) existing
                 :else (throw (ex-info "Run is bound to another fixed chunk set"
                                       {:reason :run-plan-conflict :run-id run-id})))))
      {:outcome :registered :run-id run-id :chunk-set/root (:chunk-set/root fixed-set)}))
  (claim-chunk! [_ run-id claim]
    (let [now (now-ms claim)
          lease-ms (:lease-ms claim)]
      (when-not (and (integer? lease-ms) (pos? lease-ms))
        (throw (ex-info "Lease duration must be positive" {:lease-ms lease-ms})))
      (when (and (local-key-requested? claim) (not (valid-local-key-request? claim)))
        (throw (ex-info "Local-key capacity and budget must be positive"
                        {:reason :invalid-local-key-capacity :claim (local-key-request claim)})))
      (let [claimed (atom nil)
            refusal (atom nil)]
        (swap! state update run-id
               (fn [run]
                 (if-not (and run (= :dispatching (:status run)))
                   run
                   (if (and (local-key-requested? claim)
                            (>= (active-local-key-usage run now (:local-key/id claim))
                                (min (:local-key/capacity claim) (:local-key/budget claim))))
                     (do (reset! refusal (capacity-refusal run now claim)) run)
                     (if-let [chunk (first (filter #(claimable? now %)
                                                   (sort-by :chunk/id (vals (:chunks run)))))]
                       (let [next (assoc chunk :status :leased :fence (inc (:fence chunk))
                                         :lease/token (str (java.util.UUID/randomUUID))
                                         :lease/expires-at (+ now lease-ms)
                                         :local-key/id (:local-key/id claim)
                                         :local-key/capacity (:local-key/capacity claim)
                                         :local-key/budget (:local-key/budget claim))]
                         (reset! claimed (assoc next :run-id run-id))
                         (assoc-in run [:chunks (:chunk/id chunk)] next))
                       run)))))
        (or @refusal
            (some-> @claimed
                    (select-keys [:run-id :chunk/id :run-plan/root :execution-plan/root
                                  :chunk/expected-input-root :chunk/expected-work-root
                                  :chunk/expected-sensitivity-root
                                  :chunk/expected-executable-distribution-root :chunk/execution-ids
                                  :local-key/id :local-key/capacity :local-key/budget
                                  :fence :lease/token :lease/expires-at]))))))
  (complete-chunk! [_ run-id completion]
    (let [now (now-ms completion)
          {:keys [chunk-id lease-token fence detached-chunk-result]} completion
          answer (atom nil)]
      (swap! state update run-id
             (fn [run]
               (let [chunk (get-in run [:chunks chunk-id])]
                 (cond
                   (nil? run) (do (reset! answer {:outcome :rejected :reason :unknown-run}) run)
                   (nil? chunk) (do (reset! answer {:outcome :rejected :reason :unknown-chunk}) run)
                   (= :completed (:status chunk))
                   (do (reset! answer (if (= (:detached-chunk-result/root chunk)
                                             (:detached-chunk-result/root detached-chunk-result))
                                        {:outcome :idempotent-completion}
                                        {:outcome :rejected :reason :completed-result-conflict})) run)
                   (not= :dispatching (:status run)) (do (reset! answer {:outcome :rejected :reason :run-not-runnable}) run)
                   (not= :leased (:status chunk)) (do (reset! answer {:outcome :rejected :reason :chunk-not-leased}) run)
                   (not= fence (:fence chunk)) (do (reset! answer {:outcome :rejected :reason :stale-fence}) run)
                   (not= lease-token (:lease/token chunk)) (do (reset! answer {:outcome :rejected :reason :lease-token-mismatch}) run)
                   (<= (:lease/expires-at chunk) now) (do (reset! answer {:outcome :rejected :reason :lease-expired}) run)
                   (not (manifest-matches? chunk detached-chunk-result)) (do (reset! answer {:outcome :rejected :reason :manifest-descriptor-mismatch}) run)
                   :else (let [completed (assoc chunk :status :completed
                                                :detached-chunk-result/root (:detached-chunk-result/root detached-chunk-result)
                                                :result detached-chunk-result)]
                           (reset! answer {:outcome :completed
                                           :detached-chunk-result/root (:detached-chunk-result/root detached-chunk-result)})
                           (assoc-in run [:chunks chunk-id] completed))))))
      @answer))
  (mark-run-execution-complete! [_ run-id]
    (let [answer (atom nil)]
      (swap! state update run-id
             (fn [run]
               (cond
                 (nil? run) (do (reset! answer {:outcome :rejected :reason :unknown-run}) run)
                 (every? #(= :completed (:status %)) (vals (:chunks run)))
                 (do (reset! answer {:outcome :execution-complete}) (assoc run :status :execution-complete))
                 :else (do (reset! answer {:outcome :rejected :reason :incomplete-run}) run))))
      @answer))
  (resolve-chunk-completion! [_ run-id chunk-id]
    (get-in @state [run-id :chunks chunk-id :result])))

(defn local-coordinator [] (->LocalCoordinator (atom {})))
