(ns prf.pro-rata.rows
  "Canonical identity ordering and input-row normalization for pro-rata allocation.

   This is allocation-owned semantic behavior. The only shared dependency is
   the canonical value codec; callers adapt execution and application context."
  (:require [prf.canonical.codec :as canonical]))

(defn- invalid! [reason data]
  (throw (ex-info "Invalid pro-rata allocation request"
                  (assoc data :reason reason))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn canonical-id-key
  "Return a typed, byte-stable ordering key for an allocation identity."
  [identity]
  (try
    (canonical/validate-canonical-value! identity)
    (letfn [(key* [value]
              (cond
                (keyword? value) [:keyword (or (namespace value) "") (name value)]
                (string? value) [:string value]
                (vector? value) [:vector (mapv key* value)]
                (map? value) [:map (mapv #(bit-and (int %) 0xff)
                                         (canonical/canonical-bytes value))]
                :else [:scalar (mapv #(bit-and (int %) 0xff)
                                     (canonical/canonical-bytes value))]))]
      (key* identity))
    (catch clojure.lang.ExceptionInfo error
      (invalid! :unsupported-allocation-row-id
                {:row/id identity :cause (ex-data error)}))))

(defn canonical-rows
  "Validate and canonically order domain-neutral allocation rows.

   `:row/id` is an allocation identity, not a participant identity. It must be
   unique so duplicate economic rows remain visible rather than being collapsed.
   Repeated obligation IDs or identical requested/weight/cap values are valid."
  [rows]
  (let [rows (vec (or rows []))
        ids (mapv :row/id rows)]
    (when (some nil? ids)
      (invalid! :missing-allocation-row-id {:rows rows}))
    (when-not (= (count ids) (count (distinct ids)))
      (invalid! :duplicate-allocation-row-id {:row-ids ids}))
    (doseq [row rows]
      (canonical-id-key (:row/id row)))
    (doseq [row rows
            field [:requested :weight]]
      (when (nil? (get row field))
        (invalid! :missing-allocation-row-field
                  {:row/id (:row/id row) :field field})))
    (doseq [row rows
            field [:requested :weight :cap]
            :let [value (get row field)]
            :when (and (some? value) (not (non-negative-integer? value)))]
      (invalid! :invalid-allocation-row-amount
                {:row/id (:row/id row) :field field :value value}))
    (->> rows
         (mapv (fn [row]
                 (let [declared-cap (:cap row)
                       requested (:requested row)]
                   {:row/id (:row/id row)
                    :obligation/id (:obligation/id row)
                    ;; Persisted allocation quantities normalize 3 and 3N alike.
                    :requested (bigint requested)
                    :weight (bigint (:weight row))
                    :declared-cap (some-> declared-cap bigint)
                    :effective-cap (bigint (if (some? declared-cap)
                                             (min requested declared-cap)
                                             requested))})))
         (sort-by (comp canonical-id-key :row/id))
         vec)))
