(ns resolver-sim.ordering.priority-composition
  "Composition layer for priority-order.v1.

   Owns how priority facts are combined with claims, available capacity, and
   within-class allocation policy. Consumes validated priority artifacts and
   never redefines or mutates priority classes.

   Target rule:
     priority determines which claims may compete together and which classes
     precede others; composition determines how available capacity is consumed
     within that ordering.

   This namespace may depend on both the priority primitive and registered
   allocation mechanisms; the primitive itself must not."
  (:require [resolver-sim.ordering.priority :as priority]
            [resolver-sim.pro-rata.allocation :as pro-rata]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Errors
;; ──────────────────────────────────────────────────────────────────────────────

(defn- invalid!
  [reason data]
  (throw (ex-info "Invalid priority-order composition request"
                  (assoc data :reason reason))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Within-class allocation policies (extension registry)
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Each within-class policy decides how available capacity is distributed among
;; the members of an already-eligible priority class. A policy is a
;; capacity-allocation policy, never a priority semantic: it must not alter
;; class membership, precedence, eligibility, entitlement, or allocation
;; weight. `:policy/allocate` receives the positive-demand rows for one class
;; (allocatable strictly below total demand) and returns
;; {:allocated {row/id amount} :unmet {row/id unmet}}.

(defn- pro-rata-within
  [rows allocatable]
  (let [result (pro-rata/allocate {:allocation/id :priority-within-class
                                   :available allocatable
                                   :rows rows})
        allocated (into {} (map (fn [row] [(:row/id row) (:allocated row)]) (:rows result)))
        unmet (into {} (map (fn [row] [(:row/id row) (- (:requested row) (:allocated row))])
                            (:rows result)))]
    {:allocated allocated :unmet unmet}))

(defn- first-satisfied-within
  [rows allocatable]
  (let [ordered (sort-by (comp priority/canonical-subject-key :row/id) rows)]
    (loop [remaining allocatable
           pending ordered
           allocated {}]
      (if (or (empty? pending) (zero? remaining))
        {:allocated allocated
         :unmet (into {} (map (fn [row]
                                [(:row/id row)
                                 (- (:requested row) (get allocated (:row/id row) 0))])
                              ordered))}
        (let [row (first pending)
              amount (min remaining (:requested row))]
          (recur (- remaining amount)
                 (rest pending)
                 (assoc allocated (:row/id row) amount)))))))

(def within-class-policies
  "Registry of within-class capacity-allocation methods. These are allocation
   policies, not priority semantics."
  {:pro-rata
   {:policy/name :pro-rata
    :policy/description "Proportional capacity allocation via the registered pro-rata allocation mechanism"
    :policy/allocate pro-rata-within}

   :first-satisfied
   {:policy/name :first-satisfied
    :policy/description "Canonical-subject-id sequential fill. A capacity-allocation policy, never a priority semantic"
    :policy/allocate first-satisfied-within}})

(defn register-within-class-policy!
  "Register or replace a within-class allocation policy in the extension
   registry. Returns the updated registry map."
  [policy]
  (when-not (and (map? policy)
                 (:policy/name policy)
                 (:policy/allocate policy)
                 (fn? (:policy/allocate policy)))
    (invalid! :malformed-within-class-policy {:policy policy}))
  (alter-var-root #'within-class-policies
                  (fn [registry] (assoc registry (:policy/name policy) policy))))

(defn resolve-within-class-policy
  [method-name]
  (or (within-class-policies method-name)
      (invalid! :unsupported-within-class-policy
                {:method method-name
                 :known (vec (sort (keys within-class-policies)))})))

;; ──────────────────────────────────────────────────────────────────────────────
;; Composition: apply-priority-allocation
;; ──────────────────────────────────────────────────────────────────────────────

(defn- demand-by-id
  [demand-by-subject subject-id]
  (bigint (get demand-by-subject subject-id 0)))

(defn- allocate-class
  [class available within-class-policy demand-by-subject]
  (let [rows (->> (:members class)
                  (mapv (fn [subject-id]
                          {:row/id subject-id
                           :requested (demand-by-id demand-by-subject subject-id)
                           :weight (demand-by-id demand-by-subject subject-id)
                           :cap (demand-by-id demand-by-subject subject-id)}))
                  (filterv #(pos? (:requested %))))
        class-total (reduce + 0 (map :requested rows))
        allocatable (min available class-total)]
    (if (zero? allocatable)
      {:allocated {} :unmet {} :used 0 :class-total class-total}
      (let [within (if (= allocatable class-total)
                     {:allocated (into {} (map (fn [row] [(:row/id row) (:requested row)]) rows))
                      :unmet (into {} (map (fn [row] [(:row/id row) 0N]) rows))}
                     ((:policy/allocate within-class-policy) rows allocatable))]
        {:allocated (:allocated within)
         :unmet (:unmet within)
         :used allocatable
         :class-total class-total}))))

(defn apply-priority-allocation
  "Allocate available capacity across the priority order, class by class in
   rank order. A class is eligible only after every higher-priority class has
   been fully satisfied (or the capacity is exhausted).

   Consumes a validated priority artifact and never redefines or mutates its
   priority classes.

   Request:
     :priority-order      — a valid priority-order.v1 artifact
     :available-capacity  — non-negative integer capacity
     :demand-by-subject   — map of subject-id -> demand (default {})
     :within-class-policy — {:method :pro-rata} (default) |
                            {:method :first-satisfied} or a registered policy

   Normative result shape:
     {:allocations {subject-id amount ...}
      :exhausted-at-rank <rank | nil>
      :partially-satisfied-class <rank | nil>}

   Deterministic derived diagnostics (:unmet, :capacity-after) live under
   :allocation/diagnostics and are not part of the normative parity contract."
  [{:keys [priority-order available-capacity demand-by-subject within-class-policy]}]
  (when-not (map? priority-order)
    (invalid! :missing-priority-order {}))
  (when-not (priority/valid-priority-order? priority-order)
    (invalid! :invalid-priority-order {}))
  (when-not (and (integer? available-capacity) (not (neg? available-capacity)))
    (invalid! :invalid-available-capacity {:available-capacity available-capacity}))
  (let [within-class-policy (or within-class-policy {:method :pro-rata})
        policy (resolve-within-class-policy (:method within-class-policy))
        demand-by-subject (or demand-by-subject {})
        members (set (mapcat :members (:priority-classes priority-order)))
        _ (doseq [subject-id (keys demand-by-subject)]
            (when-not (contains? members subject-id)
              (invalid! :demand-for-unclassified-subject {:subject/id subject-id})))
        {:keys [allocations exhausted-at-rank partially-satisfied-class capacity-after]}
        (loop [classes (:priority-classes priority-order)
               remaining (bigint available-capacity)
               allocations (into {} (map (fn [subject-id] [subject-id 0N]) members))
               exhausted nil
               partial nil]
          (if (or (empty? classes) (zero? remaining))
            {:allocations allocations
             :exhausted-at-rank exhausted
             :partially-satisfied-class partial
             :capacity-after remaining}
            (let [class (first classes)
                  result (allocate-class class remaining policy demand-by-subject)
                  used (:used result)
                  remaining' (- remaining used)
                  exhausted' (if (zero? remaining') (:priority/rank class) exhausted)
                  partial' (if (and (pos? used) (< used (:class-total result)))
                             (:priority/rank class)
                             partial)]
              (recur (rest classes)
                     remaining'
                     (merge allocations (:allocated result))
                     exhausted'
                     partial'))))]
    {:allocations allocations
     :exhausted-at-rank exhausted-at-rank
     :partially-satisfied-class partially-satisfied-class
     :allocation/diagnostics
     {:unmet (into {} (for [[subject-id amount] allocations]
                        [subject-id (- (demand-by-id demand-by-subject subject-id) amount)]))
      :capacity-after capacity-after}}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Deterministic serialization
;; ──────────────────────────────────────────────────────────────────────────────

(defn execution-order
  "Flatten the ordered classes into a deterministic subject sequence.

   Serialization is explicitly declared as :serialization-only: the canonical
   subject-id order inside a class must never alter entitlement, eligibility,
   allocation weight, precedence, or economic priority."
  ([artifact]
   (execution-order artifact {:method :canonical-subject-id
                              :semantics :serialization-only}))
  ([artifact policy]
   (when-not (= :canonical-subject-id (:method policy))
     (invalid! :unsupported-execution-order-method {:policy policy}))
   {:execution-order policy
    :subject-ids (vec (mapcat (fn [class]
                                (sort-by priority/canonical-subject-key (:members class)))
                              (priority/priority-classes artifact)))}))
