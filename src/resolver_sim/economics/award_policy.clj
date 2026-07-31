(ns resolver-sim.economics.award-policy
  "Award policy artifact.
   Award-policy.v1 commits the required eligibility check-set root,
   enabling policy-relative completeness verification of an award
   calculation.  Given a resolved policy, a verifier can establish:

     (:policy/check-set-root policy)
     == (:award/check-set-root award)
     == (check-set-root supplied-check-ids)"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const award-policy-type :award-policy.v1)

(def ^:private policy-projection-fields
  [:artifact/type
   :policy/id
   :policy/required-check-ids
   :policy/check-set-root])

;; ── Hash ─────────────────────────────────────────────────────────────────────

(defn award-policy-hash-projection
  [policy]
  (select-keys policy policy-projection-fields))

(defn award-policy-hash
  [policy]
  (hc/hash-with-intent {:hash/intent :award-policy}
                       (award-policy-hash-projection policy)))

;; ── Builder ──────────────────────────────────────────────────────────────────

(defn build-award-policy
  "Build a content-addressed award policy artifact.
   opts — {:keys [policy/id policy/required-check-ids]}"
  [{:keys [policy/id policy/required-check-ids]}]
  (when-not (and (string? id) (seq id))
    (throw (ex-info ":policy/id must be a non-empty string"
                    {:policy/id id})))
  (when-not (and (vector? required-check-ids) (seq required-check-ids))
    (throw (ex-info ":policy/required-check-ids must be a non-empty vector"
                    {:policy/required-check-ids required-check-ids})))
  (doseq [cid required-check-ids]
    (when-not (or (keyword? cid) (string? cid))
      (throw (ex-info ":policy/required-check-ids entries must be keywords or strings"
                      {:check/id cid}))))
  (when (not= (count required-check-ids)
              (count (set required-check-ids)))
    (throw (ex-info "Duplicate required check IDs"
                    {:ids required-check-ids})))
  (let [sorted-ids (vec (sort required-check-ids))
        csr (hc/hash-with-intent {:hash/intent :check-set}
                                 {:check/ids sorted-ids})
        policy {:artifact/type award-policy-type
                :policy/id id
                :policy/required-check-ids sorted-ids
                :policy/check-set-root csr}
        hash (award-policy-hash policy)]
    (assoc policy :artifact/hash hash)))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn validate-award-policy
  "Structural validation of an award policy artifact.  Throws on invalid."
  [policy]
  (let [missing (set/difference (set policy-projection-fields)
                                (set (keys policy)))]
    (when (seq missing)
      (throw (ex-info "Missing award-policy fields"
                      {:missing missing}))))
  (let [extra (set/difference (set (keys policy))
                              (set policy-projection-fields)
                              #{:artifact/hash})]
    (when (seq extra)
      (throw (ex-info "Unknown award-policy fields" {:extra extra}))))
  (when-not (= award-policy-type (:artifact/type policy))
    (throw (ex-info "Wrong artifact type"
                    {:expected award-policy-type
                     :actual (:artifact/type policy)})))
  (let [ids (:policy/required-check-ids policy)
        expected-csr (hc/hash-with-intent {:hash/intent :check-set}
                                          {:check/ids (vec (sort ids))})]
    (when (not= expected-csr (:policy/check-set-root policy))
      (throw (ex-info "policy/check-set-root does not match required-check-ids"
                      {:expected expected-csr
                       :actual (:policy/check-set-root policy)}))))
  nil)

;; ── Verifier ─────────────────────────────────────────────────────────────────

(defn verify-award-policy
  "Independent verification of an award policy artifact.
   Returns {:valid? true} or {:valid? false :errors [...]}.  Never throws."
  [policy]
  (try
    (validate-award-policy policy)
    (let [expected (award-policy-hash policy)
          errors (cond-> []
                   (not= expected (:artifact/hash policy))
                   (conj {:type :hash-mismatch
                          :expected expected
                          :actual (:artifact/hash policy)}))]
      (if (empty? errors) {:valid? true} {:valid? false :errors errors}))
    (catch Exception e
      {:valid? false
       :errors [{:type :invalid-structure
                 :message (ex-message e)
                 :data (ex-data e)}]})))
