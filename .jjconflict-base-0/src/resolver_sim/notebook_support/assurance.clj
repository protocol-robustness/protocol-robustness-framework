(ns resolver-sim.notebook-support.assurance
  "Assurance cell helpers that make workbench statuses mechanically derived
   from the values that produced them.

   Rule: no assurance status may be authored independently of its computed
   value.  `cell` derives `:status` strictly from `:verified?`; `root-compare`
   refuses to report equality when either operand is absent, so a nil = nil
   (or missing-root) result is never silently successful.")

(defn cell
  "Mechanically derive an assurance cell from its computed value.
   Returns {:verified? bool :status kw :evidence str}.  The status is never
   independent of the value: it is :verified exactly when `verified?` is
   truthy, and :failed otherwise."
  [verified? evidence]
  (let [ok? (boolean verified?)]
    {:verified? ok?
     :status (if ok? :verified :failed)
     :evidence evidence}))

(defn status
  "Three-level assurance status derived from a verification value, never
   authored independently: `:verified` exactly when `verified?` is true;
   `:unimplemented` when verification is absent because the mechanism is
   explicitly not implemented; `:not-established` when verification is absent
   because the authority is not established; otherwise `:failed`."
  [verified? reason]
  (cond
    (boolean verified?) :verified
    (= :transition/unimplemented reason) :unimplemented
    (= :not-established reason) :not-established
    :else :failed))

(defn root-compare
  "Non-vacuous equality for root-comparison invariants: returns true only when
   both operands are present and equal.  A missing operand (nil) can never
   satisfy an equality claim, preventing nil = nil assurance results."
  [a b]
  (and (some? a) (some? b) (= a b)))

(defn rows
  "Build clerk-table rows from [label computed-value evidence] triples.
   Each row is [label status-str verified? evidence]; status-str and verified?
   are always derived by `cell` from the computed value, never authored
   independently."
  [triples]
  (mapv (fn [[label verified? evidence]]
          (let [{:keys [status verified?]} (cell verified? evidence)]
            [label (str ":" (name status)) verified? evidence]))
        triples))