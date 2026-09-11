(ns resolver-sim.scenario.golden
  "Pure golden report comparison and structured mismatch extraction.

   Does not read or write golden EDN files — compares in-memory report maps.")

(def replay-snapshot-keys
  "Keys compared in every golden verify mode.

   :suite-id is stored in golden EDN for provenance but intentionally excluded
   from comparison — the same trace may appear in multiple suites."
  #{:trace-id :final-state-hash :metrics :outcome})

(defn replay-golden-snapshot
  "Replay fields compared in every golden verify mode."
  [report]
  (select-keys report replay-snapshot-keys))

(defn- key-union [a b]
  (into #{} (concat (keys a) (keys b))))

(defn diff-maps
  "Return a vector of {:path [...] :expected v :actual v} for leaf differences.

   `path-prefix` is prepended to every mismatch path (e.g. `[:theory]`)."
  ([left right] (diff-maps [] left right))
  ([path-prefix left right]
   (let [path (vec path-prefix)]
     (cond
       (= left right) []
       (and (map? left) (map? right))
       (vec (mapcat (fn [k]
                      (diff-maps (conj path k)
                                 (get left k)
                                 (get right k)))
                    (sort (key-union left right))))
       :else [{:path path :expected left :actual right}]))))

(defn- sort-mismatches
  "Stable CI/evidence ordering — sort by path segments lexicographically."
  [mismatches]
  (vec (sort-by (fn [{:keys [path]}]
                  (mapv (fn [seg] (if (keyword? seg) (name seg) (str seg))) path))
                mismatches)))

(defn- golden-summary
  [mismatches]
  (cond
    (empty? mismatches) "match"
    (= 1 (count mismatches))
    (if (= :theory (first (:path (first mismatches))))
      "theory snapshot mismatch"
      "replay snapshot mismatch")
    :else (str (count mismatches) " mismatches")))

(defn missing-golden-check
  "Structured check when a golden snapshot file is absent in :verify mode.

   Distinct from mismatch (`:error :missing-golden`) and from disabled verify
   (no `:checks :golden` key at all)."
  [trace-id golden-path golden-verify-mode]
  {:ok? false
   :summary "golden snapshot missing"
   :error :missing-golden
   :mismatches []
   :golden-verify-mode golden-verify-mode
   :trace-id trace-id
   :golden-path golden-path})

(defn compare-reports
  "Compare golden (expected) and actual report maps.

   opts:
     :golden-verify-mode — :replay-only | :replay-and-theory (default)

   Returns a map suitable for `:checks :golden`:
     :ok? :summary :mismatches :golden-verify-mode
     :replay-ok? :theory-ok? — legacy flags
     :expected :actual — **compatibility/debug only** on mismatch; prefer
       :mismatches for new consumers (avoids bloating result maps)."
  [golden actual {:keys [golden-verify-mode]
                  :or {golden-verify-mode :replay-and-theory}}]
  (let [replay-ok?       (= (replay-golden-snapshot golden)
                            (replay-golden-snapshot actual))
        legacy?          (nil? (:golden-schema-version golden))
        theory-compare?  (and (= golden-verify-mode :replay-and-theory)
                              (not legacy?)
                              (some? (:theory golden)))
        theory-ok?       (or (not theory-compare?)
                             (= (:theory golden) (:theory actual)))
        replay-mismatches (when-not replay-ok?
                            (diff-maps [] (replay-golden-snapshot golden)
                                       (replay-golden-snapshot actual)))
        theory-mismatches (when (and theory-compare? (not theory-ok?))
                            (diff-maps [:theory] (:theory golden) (:theory actual)))
        mismatches       (sort-mismatches (vec (concat replay-mismatches theory-mismatches)))
        ok?              (and replay-ok? theory-ok?)]
    (if ok?
      {:ok? true
       :summary "match"
       :mismatches []
       :golden-verify-mode golden-verify-mode
       :replay-ok? true
       :theory-ok? true}
      {:ok? false
       :summary (golden-summary mismatches)
       :mismatches mismatches
       :golden-verify-mode golden-verify-mode
       :replay-ok? replay-ok?
       :theory-ok? theory-ok?
       :expected golden
       :actual actual})))
