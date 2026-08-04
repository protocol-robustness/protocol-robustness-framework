(ns resolver-sim.conformance.capability
  "Capability compatibility gate.

   Before replay, a subject's fixture-requirements and the assurance profile's
   requirements must be satisfied by the implementation's declared
   capabilities.  Unknown or unsupported capabilities result in
   'not executable' — never a replay failure halfway through.

   Capability shape: {:capability <kind> :version <int>}.

   A capability is satisfied when the implementation provides the kind at a
   version >= the required version.  Missing kinds and version conflicts are
   reported separately, so callers can distinguish 'not implemented' from
   'implemented at an older version'.")

(defn- available-version
  "Resolve the available version for a capability kind from either an
   implementation-capabilities map {kind version} or a seq of
   {:capability kind :version version} entries."
  [available kind]
  (if (map? available)
    (get available kind)
    (some (fn [e] (when (= kind (:capability e)) (:version e)))
          available)))

(defn compare-capability
  "Compare one required capability against available capabilities.
   Returns {:capability <kind>
            :kind :satisfied | :missing | :version-conflict
            :required <n>
            :available <n-or-nil>}."
  [required available]
  (let [kind (:capability required)
        req-v (:version required)
        avail-v (available-version available kind)]
    (cond
      (nil? avail-v)
      {:capability kind :kind :missing :required req-v :available nil}

      (< avail-v req-v)
      {:capability kind :kind :version-conflict
       :required req-v :available avail-v}

      :else
      {:capability kind :kind :satisfied :required req-v :available avail-v})))

(defn compatible-capabilities?
  "Evaluate fixture-requirements and profile-requirements against an
   implementation's capabilities.

   Args:
     fixture-requirements       seq of {:capability kind :version n}
     implementation-capabilities {kind version} OR seq of {:capability k :version v}
     profile-requirements       seq of {:capability kind :version n} (or nil)

   Returns:
     {:compatible? bool
      :missing-capabilities     [capability-comparisons ...]
      :version-conflicts        [capability-comparisons ...]
      :unsupported-capabilities [capability-comparisons ...]
      :satisfied-capabilities   [capability-comparisons ...]}

   `:compatible?` is true only when there are no missing capabilities and no
   version conflicts."
  [fixture-requirements implementation-capabilities profile-requirements]
  (let [required (concat (or fixture-requirements [])
                         (or profile-requirements []))
        comparisons (mapv #(compare-capability % implementation-capabilities) required)
        missing (filterv #(= :missing (:kind %)) comparisons)
        conflicts (filterv #(= :version-conflict (:kind %)) comparisons)
        satisfied (filterv #(= :satisfied (:kind %)) comparisons)]
    {:compatible? (and (empty? missing) (empty? conflicts))
     :missing-capabilities missing
     :version-conflicts conflicts
     :unsupported-capabilities (into [] (remove #(= :satisfied (:kind %))) comparisons)
     :satisfied-capabilities satisfied}))

(defn unsupported-capability?
  "True when a single capability comparison is not :satisfied."
  [{:keys [kind]}]
  (not= :satisfied kind))

;; ---------------------------------------------------------------------------
;; Observed capability satisfaction (declared / resolved / exercised)
;; ---------------------------------------------------------------------------

(defn capability-status
  "Three-stage capability status.

   declared?  — the capability id is declared by an active profile;
   resolved?  — an executable implementation is registered for it;
   exercised? — a successful receipt for the CURRENT subject set exists.

   A claim may only be emitted when required capabilities are declared AND
   resolved (executable profile) and exercised for the actual subject set."
  [{:keys [capability version]} declared resolved exercised]
  {:capability/id capability
   :capability/version version
   :declared? (contains? declared capability)
   :resolved? (contains? resolved capability)
   :exercised? (contains? exercised capability)})

(defn receipt-satisfies-capability?
  "True when `receipt` is a :pass receipt exercising `capability-id` for one of
   the given subject roots.  A capability is satisfied only by an appropriate
   successful receipt for the CURRENT subject — never merely because its
   keyword is listed."
  [capability-id receipt subject-roots]
  (and (= capability-id (:capability/id receipt))
       (= :pass (:status receipt))
       (contains? subject-roots (:subject/root receipt))))

(defn observed-capabilities
  "Map of capability id -> exercised? for the current subject set, derived ONLY
   from receipts bound to those subjects."
  [required-caps receipts subject-roots]
  (into {}
        (map (fn [cap]
               [(:capability cap)
                (boolean (some #(receipt-satisfies-capability?
                                 (:capability cap) % subject-roots)
                               receipts))]))
        required-caps))

(defn exercised-capability-ids
  "Set of capability ids that have a successful receipt for the subject set."
  [required-caps receipts subject-roots]
  (into #{} (keep (fn [[k exercised?]] (when exercised? k)))
        (observed-capabilities required-caps receipts subject-roots)))

(defn capability-claimable?
  "A claim is emit-able only when every required capability was exercised for
   the actual subject set (stale registry declarations and bypassed validators
   cannot satisfy a capability)."
  [required-caps exercised-ids]
  (every? #(contains? exercised-ids (:capability %)) required-caps))
