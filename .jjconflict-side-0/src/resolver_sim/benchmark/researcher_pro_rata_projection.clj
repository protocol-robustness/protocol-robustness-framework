(ns resolver-sim.benchmark.researcher-pro-rata-projection
  "Rooted projection from governed researcher reward obligations to pro-rata rows.

   This is downstream of both domains: researcher identity comes from the
   researcher axis and reward obligation root, while row identity and allocated
   values remain authoritative in the existing pro-rata result."
  (:require [resolver-sim.benchmark.researcher-axis :as researcher-axis]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants])
  (:import [java.util.concurrent ConcurrentLinkedDeque]))

(def ^:const projection-schema "researcher-pro-rata-projection.v1")
(def ^:const projection-domain "PRF_RESEARCHER_PRO_RATA_PROJECTION_V1")

(defn projection-root [projection]
  (hash-ref/sha256-ref
   (hc/domain-hash projection-domain
                   (dissoc projection :researcher-pro-rata-projection/root))))

(defn- valid-allocation-result! [result]
  (when-not (and (map? result)
                 (allocation/allocation-hash-valid? result)
                 (empty? (invariants/result-violations result)))
    (throw (ex-info "pro-rata allocation result is invalid"
                    {:allocation/root (:allocation/hash result)})))
  result)

(defn- valid-obligations! [obligations]
  (let [obligations (vec (or obligations []))
        roots (mapv :researcher-reward-obligation/root obligations)]
    (when-not (= (count roots) (count (distinct roots)))
      (throw (ex-info "duplicate researcher reward obligation roots"
                      {:roots roots})))
    (doseq [obligation obligations]
      (when-not (reward/valid-obligation? obligation)
        (throw (ex-info "invalid researcher reward obligation"
                        {:root (:researcher-reward-obligation/root obligation)}))))
    obligations))

(defn- row-order [row]
  (allocation/canonical-id-key (:row/id row)))

(defn execute-work-units
  "Execute keyed pure work units sequentially or through a bounded shared deque.

   Scheduling is operational only. Results are returned in work-unit key order,
   never completion or worker order. `:delay-fn` is a deterministic test hook
   and is not part of any canonical artifact."
  [{:keys [mode workers delay-fn]
    :or {mode :sequential workers 1}}
   work-units f]
  (let [work-units (vec work-units)
        delay-fn (or delay-fn (constantly 0))
        run-one (fn [[key unit]]
                  (let [delay-ms (delay-fn unit)]
                    (when (pos? delay-ms)
                      (Thread/sleep (long delay-ms)))
                    [key (f unit)]))]
    (if (= mode :sequential)
      (mapv run-one work-units)
      (if (= mode :work-stealing)
        (let [worker-count (max 1 (min (int workers) (max 1 (count work-units))))
              queue (ConcurrentLinkedDeque. (reverse work-units))
              results (atom {})
              worker (fn []
                       (loop [unit (.pollFirst queue)]
                         (when unit
                           (swap! results assoc (first unit) (run-one unit))
                           (recur (.pollFirst queue)))))]
          (let [futures (doall (repeatedly worker-count #(future (worker))))]
            (doseq [future futures] @future)
            (vec (sort-by first (vals @results)))))
        (throw (ex-info "unsupported work-unit execution mode" {:mode mode}))))))

(defn- build-binding [researcher-axis row-by-obligation obligation]
  (let [researcher-id (:researcher/id obligation)
        coordinate (researcher-axis/researcher-coordinate researcher-axis researcher-id)
        obligation-root (:researcher-reward-obligation/root obligation)
        matching-rows (sort-by row-order (get row-by-obligation obligation-root []))]
    {:researcher/key (:key coordinate)
     :researcher/id researcher-id
     :researcher-reward-obligation/root obligation-root
     :row/ids (mapv :row/id matching-rows)}))

(defn build-projection
  "Build a rooted researcher-coordinate -> pro-rata row projection.

   Every binding is one valid researcher reward obligation. Its root is matched
   exactly against pro-rata :obligation/id; zero, one, or many rows are valid.
   Unrelated pro-rata rows remain outside this additive projection."
  ([input] (build-projection input {}))
  ([{:keys [researcher-axis allocation-result obligations]} execution-options]
   (let [allocation-result (valid-allocation-result! allocation-result)
         obligations (valid-obligations! obligations)
         rows (:rows allocation-result)
         row-ids (mapv :row/id rows)
         row-by-obligation (group-by :obligation/id rows)
         keyed-obligations (map-indexed vector obligations)
         bindings (->> (execute-work-units execution-options keyed-obligations
                                           #(build-binding researcher-axis
                                                           row-by-obligation %))
                       (sort-by (juxt (comp :researcher/key second)
                                      (comp :researcher-reward-obligation/root second)))
                       (mapv second))
         bound-row-ids (mapcat :row/ids bindings)]
     (when-not (= (count bound-row-ids) (count (distinct bound-row-ids)))
       (throw (ex-info "pro-rata row is bound more than once"
                       {:row-ids bound-row-ids})))
     (when-not (= (count row-ids) (count (distinct row-ids)))
       (throw (ex-info "pro-rata allocation result has duplicate row IDs"
                       {:row-ids row-ids})))
     (let [projection {:artifact/schema projection-schema
                       :researcher-axis/root (:axis/root researcher-axis)
                       :allocation/root (:allocation/hash allocation-result)
                       :bindings bindings}]
       (assoc projection
              :researcher-pro-rata-projection/root
              (projection-root projection))))))

(defn validate-projection
  "Validate a projection against its researcher axis, obligations, and allocation result."
  [researcher-axis obligations allocation-result projection]
  (valid-allocation-result! allocation-result)
  (let [obligations (valid-obligations! obligations)
        obligation-by-root (into {}
                                 (map (juxt :researcher-reward-obligation/root identity)
                                      obligations))
        bindings (:bindings projection)
        binding-roots (mapv :researcher-reward-obligation/root (or bindings []))
        expected-roots (set (keys obligation-by-root))]
    (when-not (vector? bindings)
      (throw (ex-info "projection bindings must be a vector" {:bindings bindings})))
    (when-not (= expected-roots (set binding-roots))
      (throw (ex-info "projection obligation bindings are incomplete or unexpected"
                      {:expected-roots expected-roots
                       :binding-roots binding-roots})))
    (when-not (= (count binding-roots) (count (distinct binding-roots)))
      (throw (ex-info "projection obligation binding is duplicated"
                      {:binding-roots binding-roots})))
    (doseq [binding bindings]
      (when-not (and (= #{:researcher/key
                          :researcher/id
                          :researcher-reward-obligation/root
                          :row/ids}
                        (set (keys binding)))
                     (integer? (:researcher/key binding))
                     (not (neg? (:researcher/key binding)))
                     (string? (:researcher/id binding))
                     (seq (:researcher/id binding))
                     (hash-ref/valid-sha256-ref?
                      (:researcher-reward-obligation/root binding))
                     (vector? (:row/ids binding)))
        (throw (ex-info "projection binding shape is invalid" {:binding binding}))))
    (when-not (= projection-schema (:artifact/schema projection))
      (throw (ex-info "researcher pro-rata projection schema is invalid" {})))
    (when-not (= (:axis/root researcher-axis)
                 (:researcher-axis/root projection))
      (throw (ex-info "researcher axis root mismatch" {})))
    (when-not (= (:allocation/hash allocation-result)
                 (:allocation/root projection))
      (throw (ex-info "allocation root mismatch" {})))
    (when-not (= (:researcher-pro-rata-projection/root projection)
                 (projection-root projection))
      (throw (ex-info "researcher pro-rata projection root mismatch" {})))
    (doseq [binding (:bindings projection)]
      (let [obligation-root (:researcher-reward-obligation/root binding)
            obligation (get obligation-by-root obligation-root)]
        (when-not obligation
          (throw (ex-info "projection references an unknown reward obligation"
                          {:root obligation-root})))
        (when-not (= (:researcher/id obligation) (:researcher/id binding))
          (throw (ex-info "projection researcher does not match reward obligation"
                          {:binding binding}))))
      (let [coordinate {:scope/root (:researcher-axis/root projection)
                        :axis researcher-axis/axis-kind
                        :key (:researcher/key binding)}
            member (researcher-axis/researcher-member researcher-axis coordinate)]
        (when-not (= (:researcher/id member) (:researcher/id binding))
          (throw (ex-info "researcher identity does not match coordinate" {:binding binding})))))
    (let [rows-by-id (into {} (map (juxt :row/id identity) (:rows allocation-result)))
          seen (atom #{})]
      (doseq [binding (:bindings projection)]
        (let [obligation-root (:researcher-reward-obligation/root binding)
              row-ids (:row/ids binding)
              known-rows (mapv #(get rows-by-id %) row-ids)]
          (when (some nil? known-rows)
            (throw (ex-info "projection references an unknown row" {:row-ids row-ids})))
          (when-not (= (vec row-ids)
                       (mapv :row/id (sort-by row-order known-rows)))
            (throw (ex-info "projection row IDs are not canonically ordered" {:binding binding})))
          (doseq [[row-id row] (map vector row-ids known-rows)]
            (when (contains? @seen row-id)
              (throw (ex-info "pro-rata row is bound more than once" {:row/id row-id})))
            (swap! seen conj row-id)
            (when-not (= obligation-root (:obligation/id row))
              (throw (ex-info "projection row obligation mismatch"
                              {:row/id row-id
                               :expected obligation-root
                               :actual (:obligation/id row)})))))))
    projection))

(defn researcher-allocation-rows
  "Return the committed allocation rows for one scoped researcher coordinate.

   The result preserves separate reward obligations and never turns a missing
   row into a synthetic zero allocation."
  [researcher-axis obligations projection allocation-result coordinate]
  (validate-projection researcher-axis obligations allocation-result projection)
  (let [member (researcher-axis/researcher-member researcher-axis coordinate)
        bindings (filter #(and (= (:researcher/key %) (:key coordinate))
                               (= (:researcher/id %) (:researcher/id member)))
                         (:bindings projection))
        rows-by-id (into {} (map (juxt :row/id identity) (:rows allocation-result)))]
    (mapv (fn [binding]
            {:researcher/key (:researcher/key binding)
             :researcher/id (:researcher/id binding)
             :researcher-reward-obligation/root
             (:researcher-reward-obligation/root binding)
             :rows (mapv rows-by-id (:row/ids binding))})
          bindings)))
