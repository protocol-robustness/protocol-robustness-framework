(ns resolver-sim.assurance.parameter-attribution
  "Protocol-independent structural contract for optional, hash-bound parameter
   attribution. This namespace does not resolve parameters or assess policy."
  (:require [clojure.string :as str]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private reserved-parameter-attribution-key-set
  #{:parameter/context :parameter/address})

(defn reserved-parameter-attribution-keys
  "Return reserved attribution keys present in carrier."
  [carrier]
  (set (filter reserved-parameter-attribution-key-set (keys (or carrier {})))))

(defn parameter-attribution-present?
  [carrier]
  (boolean (or (some? (:parameter/context carrier))
               (some? (:parameter/address carrier)))))

(defn parameter-attribution-error
  "Return nil for an absent or valid pair, otherwise the stable error keyword."
  [carrier]
  (let [context (:parameter/context carrier)
        address (:parameter/address carrier)
        locator? #(or (keyword? %) (integer? %) (and (string? %) (not (str/blank? %))))
        context-keys (set (if (map? context) (keys context) []))
        address-keys (set (if (map? address) (keys address) []))
        root-context? (contains? context-keys :parameter-context/root)
        id-context? (contains? context-keys :parameter-context/id)
        root-form? (and (= :protocol-parameters (:parameter-context/type context))
                        root-context?
                        (not id-context?)
                        (hash-ref/valid-sha256-ref? (:parameter-context/root context))
                        (pos-int? (:parameter-context/version context))
                        (every? #{:parameter-context/type :parameter-context/root
                                  :parameter-context/version :parameter-context/scope-id}
                                context-keys)
                        (or (nil? (:parameter-context/scope-id context))
                            (locator? (:parameter-context/scope-id context))))
        id-form? (and (= :world-params (:parameter-context/type context))
                      id-context?
                      (not root-context?)
                      (locator? (:parameter-context/id context))
                      (= #{:parameter-context/type :parameter-context/id} context-keys))
        context-valid? (and (map? context) (or root-form? id-form?))
        id-address? (contains? address-keys :parameter/id)
        path-address? (contains? address-keys :parameter/path)
        semantic-id-form? (and id-address?
                               (not path-address?)
                               (locator? (:parameter/id address))
                               (every? #{:parameter/id :parameter/instance} address-keys)
                               (or (nil? (:parameter/instance address))
                                   (locator? (:parameter/instance address))))
        path-form? (and path-address?
                        (not id-address?)
                        (= #{:parameter/path} address-keys)
                        (vector? (:parameter/path address))
                        (boolean (seq (:parameter/path address)))
                        (every? locator? (:parameter/path address)))
        address-valid? (and (map? address) (or semantic-id-form? path-form?))]
    (cond
      (and (nil? context) (nil? address)) nil
      (nil? address) :parameter-context-without-address
      (nil? context) :parameter-address-without-context
      (not context-valid?) :invalid-parameter-context
      (not address-valid?) :invalid-parameter-address
      :else nil)))

(defn parameter-attribution-valid?
  [carrier]
  (nil? (parameter-attribution-error carrier)))

(defn project-parameter-attribution
  "Return the exact valid pair or {} when absent. Throws no exceptions."
  [carrier]
  (if (and (parameter-attribution-valid? carrier)
           (parameter-attribution-present? carrier))
    (select-keys carrier reserved-parameter-attribution-key-set)
    {}))
