(ns resolver-sim.research.accounting-semantics
  "Directional claim targets and qualified research position artifacts.

   These artifacts record semantic relationships and observed/accounting state.
   They confer no acceptance, ownership, authority, payment, or consumption
   permission. Existing related-claims remains the inter-claim relation model."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const claim-to-schema "claim-to-relation.v1")
(def ^:const aggregate-target-schema "claim-target-aggregate.v1")
(def ^:const possession-position-schema "possession-position.v1")
(def ^:const accounting-position-schema "accounting-position.v1")

(def possession-dimensions
  [:availability :possession :readability :usability :authority])

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- canonical-roots? [xs]
  (and (vector? xs) (= xs (vec (sort xs)))
       (= (count xs) (count (set xs))) (every? root? xs)))

(defn aggregate-target-root [artifact]
  (root :claim-target-aggregate-v1
        (dissoc artifact :claim-target-aggregate/root)))

(defn build-aggregate-target
  "Build an explicit primary target for a claim whose economic target is a
   collection. Members are roots, not implicit inter-claim relationships."
  [category member-roots]
  (let [members (vec (sort member-roots))]
    (when-not (and (qualified-keyword? category) (seq members)
                   (canonical-roots? members))
      (throw (ex-info "Invalid aggregate claim target" {})))
    (let [body {:artifact/schema aggregate-target-schema
                :claim-target/category category
                :claim-target/member-roots members}]
      (assoc body :claim-target-aggregate/root (aggregate-target-root body)))))

(defn claim-to-root [relation]
  (root :claim-to-relation-v1 (dissoc relation :claim-to/root)))

(defn valid-claim-to? [relation]
  (and (map? relation)
       (= #{:artifact/schema :claim/root :claimant/root :claim-target/root
            :claim-target/category :relationship/direction :relationship/meaning
            :claim-to/root}
          (set (keys relation)))
       (= claim-to-schema (:artifact/schema relation))
       (every? root? ((juxt :claim/root :claimant/root :claim-target/root) relation))
       (qualified-keyword? (:claim-target/category relation))
       (= :claimant-to-target (:relationship/direction relation))
       (= :does-not-establish-acceptance-or-authority (:relationship/meaning relation))
       (= (:claim-to/root relation)
          (try (claim-to-root relation) (catch Exception _ nil)))))

(defn build-claim-to
  [{:keys [claim-root claimant-root target-root target-category]}]
  (when-not (and (every? root? [claim-root claimant-root target-root])
                 (qualified-keyword? target-category))
    (throw (ex-info "Invalid directional claim-to relationship" {})))
  (let [body {:artifact/schema claim-to-schema
              :claim/root claim-root
              :claimant/root claimant-root
              :claim-target/root target-root
              :claim-target/category target-category
              :relationship/direction :claimant-to-target
              :relationship/meaning :does-not-establish-acceptance-or-authority}
        rooted (assoc body :claim-to/root (claim-to-root body))]
    (when-not (valid-claim-to? rooted)
      (throw (ex-info "Invalid rooted claim-to relationship" {})))
    rooted))

(defn possession-position-root [position]
  (root :possession-position-v1 (dissoc position :possession-position/root)))

(defn build-possession-position
  "Record independently declared possession dimensions at a rooted cutpoint.
   Deliberately imposes no implication between the five dimensions."
  [{:keys [subject-root subject-kind resource-root cutpoint-root source-roots
           availability possession readability usability authority]}]
  (let [sources (vec (sort source-roots))
        states {:availability availability :possession possession
                :readability readability :usability usability
                :authority authority}]
    (when-not (and (every? root? [subject-root resource-root cutpoint-root])
                   (contains? #{:actor :execution-environment} subject-kind)
                   (canonical-roots? sources)
                   (every? boolean? (vals states)))
      (throw (ex-info "Invalid possession position" {})))
    (let [body {:artifact/schema possession-position-schema
                :position/subject-root subject-root
                :position/subject-kind subject-kind
                :position/resource-root resource-root
                :position/cutpoint-root cutpoint-root
                :position/source-roots sources
                :possession/states states
                :position/meaning :observation-not-authority}
          rooted (assoc body :possession-position/root
                        (possession-position-root body))]
      rooted)))

(defn accounting-position-root [position]
  (root :accounting-position-v1 (dissoc position :accounting-position/root)))

(defn- integer-values? [values]
  (and (map? values) (seq values)
       (every? qualified-keyword? (keys values))
       (every? integer? (vals values))))

(defn build-accounting-position
  [{:keys [subject-root accounting-domain cutpoint-root source-roots values
           accounting-definition-root accounting-policy-root]}]
  (let [sources (vec (sort source-roots))]
    (when-not (and (root? subject-root) (qualified-keyword? accounting-domain)
                   (every? root? [cutpoint-root accounting-definition-root
                                  accounting-policy-root])
                   (canonical-roots? sources) (seq sources)
                   (integer-values? values))
      (throw (ex-info "Invalid accounting position" {})))
    (let [body {:artifact/schema accounting-position-schema
                :position/subject-root subject-root
                :accounting/domain accounting-domain
                :position/cutpoint-root cutpoint-root
                :position/source-roots sources
                :accounting/values (into (sorted-map) values)
                :accounting-definition/root accounting-definition-root
                :accounting-policy/root accounting-policy-root
                :position/meaning :accounting-state-not-fund-movement}]
      (assoc body :accounting-position/root (accounting-position-root body)))))
