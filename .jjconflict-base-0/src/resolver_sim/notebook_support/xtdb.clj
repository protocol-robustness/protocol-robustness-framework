(ns resolver-sim.notebook-support.xtdb
  "Notebook-support query helpers for the XTDB Temporal Explorer.

   These are thin, pure compositions over the db/* query APIs only. They never
   touch the seed/dev namespaces and never infer semantic equivalence — matching
   is always against an explicit caller-supplied scope."
  (:require [resolver-sim.db.execution-projection :as ep]))

(def ^:private temporal-column-keys
  "XTDB-managed bitemporal bounds, excluded from `:changed-keys`."
  #{:execution/_valid_from :execution/_valid_to
    :execution/_system_from :execution/_system_to})

(defn execution-run-knowledge-diff
  "Compare one execution run's index observation AS KNOWN at `known-at`
   (system time) vs BEST-KNOWN-NOW effective at `valid-at` (valid time).

   Input keys: :execution-run/id, :valid-at, :known-at (both java.util.Date).
   Returns, or {} when the run is absent from either snapshot:
     :then, :now                     — the two snapshots
     :changed-keys                   — projection-CONTENT changes only (the four
                                       XTDB temporal bounds are excluded, so this
                                       describes what the indexed assertion
                                       actually changed)
     :temporal-metadata-changed-keys — the XTDB temporal bounds that changed
                                       (e.g. _system_from between versions),
                                       exposed separately as provenance"
  [ds {:keys [execution-run/id valid-at known-at]}]
  (let [then (first (filter #(= id (:execution/_id %))
                            (ep/execution-runs-as-known-at ds known-at)))
        now  (first (filter #(= id (:execution/_id %))
                            (ep/execution-runs-valid-at ds valid-at)))
        all-changed (->> (keys then)
                         (filter (fn [k] (not= (get then k) (get now k))))
                         vec)]
    (if (and then now)
      {:then then
       :now now
       :changed-keys (vec (remove temporal-column-keys all-changed))
       :temporal-metadata-changed-keys (vec (filter temporal-column-keys all-changed))}
      {})))

(def ^:private scope->row-key
  "Map normalized comparison-scope keys to the run-row keys produced by the
   execution projection decoder (snake_case columns → :execution/* namespaced)."
  {:benchmark-id           :execution/benchmark_id
   :scenario-id            :execution/scenario_id
   :use-case               :execution/use_case
   :run-id                 :execution/_id
   :input-set-root         :execution/input_set_root
   :semantic-composition-root :execution/semantic_composition_root})

(defn comparable-executions
  "Group execution runs under an explicit comparison scope by their result root
   (bundle_root). Matching is exact against the caller-supplied scope — XTDB
   never infers semantic equivalence.

   Returns {:scope {...} :result-roots [...] :groups {root [run-id ...]}}."
  [ds scope]
  (let [rows     (ep/recent ds 1000)
        scope    (ep/comparison-scope scope)
        matching (filterv (fn [row]
                            (every? (fn [[k v]]
                                      (if-let [row-key (get scope->row-key k)]
                                        (= v (get row row-key))
                                        true))
                                    (dissoc scope :as-of)))
                          rows)
        groups   (group-by :execution/bundle_root matching)]
    {:scope       (dissoc scope :as-of)
     :result-roots (vec (sort (keys groups)))
     :groups      (into (sorted-map)
                        (map (fn [[root runs]]
                               [root (mapv :execution/_id runs)]))
                        groups)}))