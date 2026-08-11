(ns resolver-sim.cancellation.operation
  "Framework-level cancellation attempt and execution commitments.

   This namespace binds generic cancellation mechanics, not protocol state
   semantics. A protocol supplies a policy that evaluates the committed target,
   request, and context; this contract proves that a recorded execution is the
   exact decision, authorization, and effect plan that policy derived."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.hash.sequence :as sequence]))

(def schema-version "cancellation-operation.v1")
(def attempt-schema-version "cancellation-attempt.v1")
(def execution-schema-version "cancellation-execution.v1")
(def evaluation-inputs-schema-version "cancellation-evaluation-inputs.v1")
(def derivation-purpose :cancellation/derivation.v1)

(declare effective-decision-valid?)

(def execution-statuses #{:applied :rejected :expired :superseded :already-consumed})
(def authorization-kinds #{:ordinary :override})
(def effective-decision-classifications #{:authorized :authorized-by-override :forbidden})

(defn- populated? [v]
  (and (some? v) (not (and (string? v) (str/blank? v)))))

(defn- missing-paths [m paths]
  (->> paths
       (remove #(populated? (get-in m %)))
       vec))

(def attempt-required-paths
  [[:operation/schema] [:operation/purpose] [:event/id] [:protocol/id]
   [:target :kind] [:target :id] [:target :snapshot-root]
   [:target :state-before-root] [:target :lifecycle-head-root]
   [:request :caller/id] [:request :action] [:request :requested-at]
   [:policy :id] [:policy :root]
   [:evaluation :inputs-root] [:evaluation :context] [:evaluation :base-decision]
   [:evaluation :base-decision :classification] [:evaluation :decision]
   [:evaluation :decision :classification]
   [:evaluation :decision :derived-action]])

(def applied-required-paths
  (into attempt-required-paths
        [[:authorization :kind] [:authorization :root]
         [:evaluation :decision :derived-effects-root]
         [:execution :status] [:execution :effects-root]
         [:execution :state-after-root] [:conflict/consumption-key]
         [:previous-event-root]]))

(def non-applied-required-paths
  (into attempt-required-paths [[:execution :status]]))

(defn- status-paths [operation]
  (if (= :applied (get-in operation [:execution :status]))
    applied-required-paths
    non-applied-required-paths))

(defn missing-operation-fields
  "Returns required paths absent from an operation. Applied operations require
   authorization, derived and executed effects, successor state, consumption
   protection, and lifecycle predecessor. Rejected/expired attempts remain
   auditable without fabricating execution evidence."
  [operation]
  (missing-paths operation (status-paths operation)))

(defn operation-complete?
  "True when the outcome-specific cancellation contract is complete."
  [operation]
  (and (map? operation)
       (= schema-version (:operation/schema operation))
       (= :cancellation/execution (:operation/purpose operation))
       (empty? (missing-operation-fields operation))
       (contains? execution-statuses (get-in operation [:execution :status]))
       (contains? effective-decision-classifications
                  (get-in operation [:evaluation :decision :classification]))
       (effective-decision-valid? operation)
       (or (not= :applied (get-in operation [:execution :status]))
           (and (contains? authorization-kinds (get-in operation [:authorization :kind]))
                (= (get-in operation [:evaluation :decision :derived-effects-root])
                   (get-in operation [:execution :effects-root]))))))

(defn- canonical-project [value]
  (letfn [(walk [v]
            (cond
              (set? v) (vec (sort-by (fn [item]
                                                    (apply str (map #(format "%02x" (bit-and % 0xff))
                                                                    (hc/canonical-bytes item))))
                                                  (map walk v)))
              (map? v) (into {} (map (fn [[k x]] [k (walk x)]) v))
              (vector? v) (mapv walk v)
              :else v))]
    (walk value)))

(def disputed-statuses
  "Protocol-neutral dispute context values that mean cancellation is occurring
   during an active dispute. Protocols commit the underlying status; this fact
   is derived rather than accepted as a caller-provided boolean."
  #{:active :disputed})

(defn cancel-during-dispute?
  "Derives whether the committed cancellation evaluation ran during a dispute."
  [operation]
  (contains? disputed-statuses
             (get-in operation [:evaluation :context :dispute-status])))

(defn cancellation-binding
  "Builds the framework cancellation binding for an attempt. `:event/id` is the
   primary attempt identity; it distinguishes auditable retries without being
   relied on as replay protection (the consumption key and target snapshot are
   the security boundary). The dispute fact is derived from committed context."
  [operation]
  (cond-> {:schema "cancellation-binding.v2"
           :event/id (:event/id operation)
           :protocol/id (:protocol/id operation)
           :target (:target operation)
           :request (:request operation)
           :policy (:policy operation)
           :evaluation (:evaluation operation)
           :authorization (:authorization operation)
           :execution (:execution operation)
           :conflict/consumption-key (:conflict/consumption-key operation)
           :previous-event-root (:previous-event-root operation)
           :cancellation/during-dispute? (cancel-during-dispute? operation)}
    (nil? (:authorization operation)) (dissoc :authorization)
    (nil? (:conflict/consumption-key operation)) (dissoc :conflict/consumption-key)
    (nil? (:previous-event-root operation)) (dissoc :previous-event-root)))

(defn cancellation-binding-root
  "Returns the root of the typed cancellation binding. The binding is a
   structured canonical map, never an informal concatenation of field roots."
  [operation]
  (hash-ref/sha256-ref (hc/domain-hash :cancellation-binding
                                       (canonical-project (cancellation-binding operation)))))

(defn cancellation-binding-valid?
  "Verifies an optional stored :cancellation/binding-root against the operation."
  [operation]
  (= (:cancellation/binding-root operation)
     (cancellation-binding-root (dissoc operation :cancellation/binding-root))))

(defn operation-projection
  "Returns the full typed operation projection. No field roots are manually
   concatenated; canonical map framing binds every named semantic role."
  [operation]
  (canonical-project operation))

(defn operation-root
  "Returns a sha256 reference for a complete cancellation operation."
  [operation]
  (when-not (operation-complete? operation)
    (throw (ex-info "cannot hash an incomplete cancellation operation"
                    {:missing (missing-operation-fields operation)})))
  (hash-ref/sha256-ref (hc/domain-hash :cancellation-operation
                                       (operation-projection operation))))

(defn operation-root-valid?
  "Verifies a stored :operation/root against the complete operation projection."
  [operation]
  (and (operation-complete? operation)
       (= (:operation/root operation)
          (operation-root (dissoc operation :operation/root)))))

(defn evaluation-inputs-root
  "Commits named evaluation inputs. Inputs are a map because their roles are
   semantic, not merely positional."
  [inputs]
  (when-not (and (map? inputs)
                 (= evaluation-inputs-schema-version (:schema inputs)))
    (throw (ex-info "cancellation evaluation inputs require their schema"
                    {:expected evaluation-inputs-schema-version})))
  (hash-ref/sha256-ref (hc/domain-hash :cancellation-evaluation-inputs
                                       (canonical-project inputs))))

(def derivation-roles
  [:target-snapshot :policy :decision :authorization :effects :state-after])

(defn- valid-derivation-components? [components]
  (and (= (count derivation-roles) (count components))
       (= derivation-roles (mapv :role components))
       (every? #(and (keyword? (:ref/kind %))
                     (string? (:ref/root %))
                     (not (str/blank? (:ref/root %))))
               components)))

(defn derivation-root
  "Optional ordered proof that separately committed cancellation stages were
   composed in the declared order. Components must be typed references in the
   fixed role order; callers cannot substitute a bare root from another kind."
  [components]
  (when-not (valid-derivation-components? components)
    (throw (ex-info "invalid cancellation derivation components"
                    {:expected-roles derivation-roles :components components})))
  (hash-ref/sha256-ref
   (hc/domain-hash :cancellation-derivation
                   (sequence/bound-sequence
                    {:purpose derivation-purpose
                     :expected-component-count (count derivation-roles)}
                    components))))

(defn effective-decision-valid?
  "Validates ordinary and exceptional authority without collapsing the base
   prohibition and override grant into one opaque :forbidden-authorized label."
  [operation]
  (let [base (get-in operation [:evaluation :base-decision :classification])
        effective (get-in operation [:evaluation :decision :classification])
        kind (get-in operation [:authorization :kind])
        applied? (= :applied (get-in operation [:execution :status]))]
    (case effective
      :authorized (and (= :authorized base)
                       (or (not applied?) (= :ordinary kind)))
      :authorized-by-override (and (= :forbidden base) (= :override kind))
      :forbidden (and (= :forbidden base) (not applied?))
      false)))
