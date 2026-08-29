(ns resolver-sim.cancellation.operation
  "The sole public cancellation operation statement. It binds role roots; semantic
   artifacts are independently resolved and recomputed by admission."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "cancellation-operation.v1")
(def schema-v2 "cancellation-operation.v2")

(defn- populated? [v] (and (some? v) (not (and (string? v) (str/blank? v)))))
(def required-paths
  [[:operation/schema] [:operation/purpose] [:event/id] [:protocol/id]
   [:target :kind] [:target :id] [:target :snapshot-root]
   [:target :state-before-root] [:target :lifecycle-head-root]
   [:request :caller/id] [:request :action] [:request :requested-at]
   [:policy :id] [:policy :root] [:evaluation :inputs-root] [:evaluation :base-decision]
   [:evaluation :decision] [:evaluation :decision :derived-effects-root]
   [:preconditions/root] [:authorization :kind] [:authorization :root]
   [:execution :status] [:execution :effects-root] [:execution :state-after-root]])
(def root-paths
  [[:target :snapshot-root] [:target :state-before-root] [:target :lifecycle-head-root]
   [:policy :root] [:evaluation :inputs-root]
   [:evaluation :decision :derived-effects-root] [:preconditions/root] [:authorization :root]
   [:execution :effects-root] [:execution :state-after-root]])
(defn missing-operation-fields [op] (->> required-paths (remove #(populated? (get-in op %))) vec))
(defn invalid-operation-references [op] (->> root-paths (filter #(populated? (get-in op %))) (remove #(hash-ref/valid-sha256-ref? (get-in op %))) vec))
(defn operation-complete? [op]
  (and (map? op) (contains? #{schema-version schema-v2} (:operation/schema op)) (= :cancellation/execution (:operation/purpose op))
       (= :ordinary (get-in op [:authorization :kind]))
       (= :applied (get-in op [:execution :status]))
       (empty? (missing-operation-fields op)) (empty? (invalid-operation-references op))
       ;; Derived intent and execution evidence have distinct schemas/roots.
       (not= (get-in op [:evaluation :decision :derived-effects-root]) (get-in op [:execution :effects-root]))))
(defn operation-root [op]
  (when-not (operation-complete? op) (throw (ex-info "cannot hash an invalid cancellation operation" {:missing (missing-operation-fields op) :invalid-references (invalid-operation-references op)})))
  (hash-ref/sha256-ref (hc/domain-hash :cancellation-operation op)))
(defn operation-root-valid? [op] (and (operation-complete? op) (= (:operation/root op) (operation-root (dissoc op :operation/root)))))
