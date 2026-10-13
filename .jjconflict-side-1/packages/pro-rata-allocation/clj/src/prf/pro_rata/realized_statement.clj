(ns prf.pro-rata.realized-statement
  "Canonical realized-allocation statement projection, excluding host context adapters."
  (:require [prf.canonical.hash :as canonical-hash]))

(def ^:const schema-version "realized-allocation-statement.v1")
(def ^:const request-set-domain "REALIZED_REQUEST_SET_V1")
(def ^:const allocation-policy-domain "ALLOCATION_POLICY_V1")
(def ^:const fail-action-policy-domain "FAIL_ACTION_POLICY_V1")
(def ^:const realized-results-domain "REALIZED_RESULTS_V1")
(def ^:const round-lifecycle-domain "ROUND_LIFECYCLE_V1")
(def ^:const statement-domain "REALIZED_ALLOCATION_STATEMENT_V1")

(defn request-set-root [requested]
  (canonical-hash/hash-with-domain request-set-domain (into (sorted-map) requested)))

(defn allocation-policy-root [policy]
  (canonical-hash/hash-with-domain allocation-policy-domain
                                   (dissoc policy :fail-action-policy)))

(defn fail-action-policy-root [policy]
  (canonical-hash/hash-with-domain
   fail-action-policy-domain
   (or (:fail-action-policy policy)
       {:mode :pro-rata-treatment
        :deferred-policy :same-ratio
        :haircut-policy :same-ratio})))

(defn- exact-amount [field value]
  (if (integer? value)
    value
    (throw (ex-info "realized amount must be an exact integer"
                    {:type :invalid-realized-amount :field field :value value}))))

(defn disposition-of [{:keys [requested filled deferred haircut]}]
  (let [requested (exact-amount :requested requested)
        filled (exact-amount :filled filled)
        deferred (exact-amount :deferred deferred)
        haircut (exact-amount :haircut haircut)]
    (cond
      (and (pos? deferred) (pos? haircut)) :deferred-and-haircut
      (pos? deferred) :deferred
      (pos? haircut) :haircut
      (= requested filled) :full-fill
      (zero? filled) :zero-filled
      :else :partial-fill)))

(defn realized-results-root [{:keys [requested filled deferred haircut unrealized]}]
  (let [claims (->> (concat (keys requested) (keys filled)
                            (keys deferred) (keys haircut) (keys unrealized))
                    distinct
                    sort)]
    (canonical-hash/hash-with-domain
     realized-results-domain
     (mapv (fn [claim-id]
             (let [row {:claim/id claim-id
                        :requested (exact-amount :requested (get requested claim-id 0))
                        :filled (exact-amount :filled (get filled claim-id 0))
                        :deferred (exact-amount :deferred (get deferred claim-id 0))
                        :haircut (exact-amount :haircut (get haircut claim-id 0))
                        :unrealized (exact-amount :unrealized (get unrealized claim-id 0))}]
               (assoc row :disposition (disposition-of row))))
           claims))))

(defn round-lifecycle-root [lifecycle]
  (canonical-hash/hash-with-domain round-lifecycle-domain lifecycle))

(defn all-active? [{:keys [requested filled deferred haircut]}]
  (and (every? zero? (vals deferred))
       (every? zero? (vals haircut))
       (= (into (sorted-map) requested)
          (into (sorted-map) filled))))

(defn statement-preimage
  [{:keys [allocation-context-root request-set-root allocation-policy-root
           realized-results-root fail-action-policy-root round-lifecycle-root]}]
  {:schema-version schema-version
   :allocation-context-root allocation-context-root
   :request-set-root request-set-root
   :allocation-policy-root allocation-policy-root
   :realized-results-root realized-results-root
   :fail-action-policy-root fail-action-policy-root
   :round-lifecycle-root round-lifecycle-root})

(defn statement-root [statement]
  (canonical-hash/hash-with-domain statement-domain (statement-preimage statement)))

(defn build-statement-from-roots
  "Build the frozen statement from raw allocation fields plus host-derived context roots.

   The caller supplies allocation-context-root and round-lifecycle-root because
   their source schemas and lifecycle derivations remain owned by host adapters."
  [{:keys [allocation-context-root round-lifecycle-root decision]}]
  (let [statement {:schema-version schema-version
                   :allocation-context-root allocation-context-root
                   :request-set-root (request-set-root (:requested decision))
                   :allocation-policy-root (allocation-policy-root (:policy decision))
                   :realized-results-root (realized-results-root decision)
                   :fail-action-policy-root (fail-action-policy-root (:policy decision))
                   :round-lifecycle-root round-lifecycle-root}
        all-active (all-active? decision)]
    (assoc statement
           :statement/root (statement-root statement)
           :statement/all-active? all-active
           :statement/verification-equalities
           {:all-active-all-full-fill
            (and all-active
                 (every? #(= :full-fill
                             (disposition-of
                              {:requested (long (get-in decision [:requested %] 0))
                               :filled (long (get-in decision [:filled %] 0))
                               :deferred (long (get-in decision [:deferred %] 0))
                               :haircut (long (get-in decision [:haircut %] 0))
                               :unrealized (long (get-in decision [:unrealized %] 0))}))
                        (keys (:requested decision))))})))
