(ns resolver-sim.protocols.sew.compat
  "Compatibility and normalization helpers for Sew event inputs."
  (:require [clojure.string :as str]))

(defn wf-id
  "Compatibility accessor for workflow identifiers.
   Accepts either {:workflow-id n} (current) or {:id n} (legacy)."
  [event]
  (or (get-in event [:params :workflow-id])
      (get-in event [:params :id])))

(def ^:private action-spelling-aliases
  "Backward-compat US/UK spelling aliases reduced to canonical (Commonwealth)
   spellings. Guarantees identical logical actions are identically identifiable:
   a US-spelled event and its UK-spelled equivalent resolve to the same canonical
   identity (dispatch method, replay-sensitive membership, dedupe op-key)."
  {"grant-force-authorization"       "grant-force-authorisation"
   "revoke-force-authorization"      "revoke-force-authorisation"
   "execute-force-authorized-action" "execute-force-authorised-action"})

(defn canonical-action
  "Normalize action names: convert underscores to hyphens, then reduce
   backward-compat spelling aliases to their canonical form.
   Accepts keyword or string :action (JSON scenarios use strings)."
  [event]
  (let [a (:action event)]
    (-> (cond
          (keyword? a) (name a)
          (string? a)  a
          :else         (str a))
        (str/replace "_" "-")
        (#(get action-spelling-aliases % %)))))

(defn event-param
  "Read a param from an event, accepting snake_case JSON aliases."
  [event primary-key & alias-keys]
  (let [params (:params event {})]
    (or (get params primary-key)
        (some #(get params %) alias-keys))))

(defn- normalize-id [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    :else v))

(defn event-id
  "Optional logical event identifier used for replay dedupe.
   Normalized to string for type-stable dedupe key comparison."
  [event]
  (normalize-id (event-param event :event-id :event_id)))

(defn hop-id
  "Optional escalation-hop scope for replay dedupe (escalate/challenge).
   Normalized to string for type-stable dedupe key comparison."
  [event]
  (normalize-id (event-param event :hop-id :hop_id)))