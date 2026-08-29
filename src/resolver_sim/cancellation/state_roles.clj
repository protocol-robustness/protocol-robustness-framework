(ns resolver-sim.cancellation.state-roles
  "Typed cancellation state roles for cancellation-operation.v2.(ns resolver-sim.cancellation.state-roles)"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def state-before-schema "cancellation-state-before.v1")
(def lifecycle-head-schema "cancellation-lifecycle-head.v1")
(def state-before-domain "CANCELLATION_STATE_BEFORE_V1")
(def lifecycle-head-domain "CANCELLATION_LIFECYCLE_HEAD_V1")
(def state-before-fields #{:artifact/schema :cancellation/subject-id :cancellation/snapshot-root})
(def lifecycle-head-fields #{:artifact/schema :cancellation/subject-id
                             :cancellation/represented-state-before-root})

(defn- root [domain root-key body]
  (ref/sha256-ref (hc/domain-hash domain (dissoc body root-key))))

(defn state-before-root [body]
  (root state-before-domain :cancellation-state-before/root body))
(defn lifecycle-head-root [body]
  (root lifecycle-head-domain :cancellation-lifecycle-head/root body))

(defn- validate [body schema fields root-key root-fn]
  (let [expected (conj fields root-key)
        errors (cond-> []
                 (not (map? body)) (conj "artifact must be a map")
                 (and (map? body) (not= schema (:artifact/schema body))) (conj "unsupported schema")
                 (and (map? body) (not= expected (set (keys body)))) (conj "missing or unknown keys")
                 (and (map? body) (not (some? (:cancellation/subject-id body)))) (conj "missing subject")
                 (and (map? body) (not (ref/valid-sha256-ref? (:cancellation/snapshot-root body)))
                      (= schema state-before-schema)) (conj "invalid snapshot root")
                 (and (map? body) (not (ref/valid-sha256-ref? (:cancellation/represented-state-before-root body)))
                      (= schema lifecycle-head-schema)) (conj "invalid represented state root")
                 (and (map? body) (not= (get body root-key) (root-fn body))) (conj "root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn validate-state-before [body]
  (validate body state-before-schema state-before-fields :cancellation-state-before/root state-before-root))
(defn validate-lifecycle-head [body]
  (validate body lifecycle-head-schema lifecycle-head-fields :cancellation-lifecycle-head/root lifecycle-head-root))
(defn build-state-before [body]
  (let [b (assoc body :artifact/schema state-before-schema)
        x (assoc b :cancellation-state-before/root (state-before-root b))]
    (when-not (:valid? (validate-state-before x))
      (throw (ex-info "invalid cancellation state-before" (validate-state-before x))))
    x))
(defn build-lifecycle-head [body]
  (let [b (assoc body :artifact/schema lifecycle-head-schema)
        x (assoc b :cancellation-lifecycle-head/root (lifecycle-head-root b))]
    (when-not (:valid? (validate-lifecycle-head x))
      (throw (ex-info "invalid cancellation lifecycle head" (validate-lifecycle-head x))))
    x))
