(ns resolver-sim.pro-rata.execution-context
  "Closed adapter descriptors and explicit provider-selection context.

   Extension-backed contexts are structurally fail-closed here. This namespace
   does not yet verify that an extension resolution was authorized by a chain
   configuration; that assurance belongs to a later resolution-bound wrapper."
  (:require [resolver-sim.hash.canonical :as hc]))

(def adapter-schema "native-realization-adapter-descriptor.v1")
(def context-schema "adapter-execution-context.v1")
(def exact-leaf-frame-profile :exact-native-leaf-paths.v1)

(defn- root? [value]
  (and (string? value) (re-matches #"(?:sha256:)?[0-9a-f]{64}" value)))

(defn descriptor-root [descriptor]
  (hc/domain-hash :native-realization-adapter-descriptor
                  (select-keys descriptor [:schema-version :adapter/id :adapter/version
                                           :projection/profile :reconstruction/profile
                                           :frame/profile])))

(defn build-descriptor
  [{:keys [adapter-id adapter-version projection-profile reconstruction-profile frame-profile] :as input}]
  (when-not (and (keyword? adapter-id) (pos-int? adapter-version)
                 (keyword? projection-profile) (keyword? reconstruction-profile)
                 (= exact-leaf-frame-profile frame-profile))
    (throw (ex-info "invalid native realization adapter descriptor" {:input input})))
  (let [base {:schema-version adapter-schema
              :adapter/id adapter-id
              :adapter/version adapter-version
              :projection/profile projection-profile
              :reconstruction/profile reconstruction-profile
              :frame/profile frame-profile}]
    (assoc base :adapter/descriptor-root (descriptor-root base))))

(defn context-root [context]
  (hc/domain-hash :adapter-execution-context
                  (select-keys context [:schema-version :adapter/source
                                        :adapter/descriptor-root
                                        :extension-resolution/root
                                        :extension/capability-root])))

(defn build-context
  "Build an explicit core or extension execution context. Core contexts reject
   extension fields; extension contexts require both committed authorization
   references, but only validate their closed shape in this milestone."
  [{:keys [adapter-source adapter-descriptor-root extension-resolution-root
           extension-capability-root] :as input}]
  (when-not (root? adapter-descriptor-root)
    (throw (ex-info "execution context requires adapter descriptor root" {:input input})))
  (case adapter-source
    :core
    (when (or extension-resolution-root extension-capability-root)
      (throw (ex-info "core execution context cannot carry extension bindings" {:input input})))

    :extension
    (when-not (every? root? [extension-resolution-root extension-capability-root])
      (throw (ex-info "extension execution context requires authorization bindings" {:input input})))

    (throw (ex-info "unknown adapter execution source" {:input input})))
  (let [base {:schema-version context-schema
              :adapter/source adapter-source
              :adapter/descriptor-root adapter-descriptor-root
              :extension-resolution/root extension-resolution-root
              :extension/capability-root extension-capability-root}]
    (assoc base :adapter-execution-context/root (context-root base))))

(defn valid-context?
  [execution-context]
  (and (= context-schema (:schema-version execution-context))
       (= (:adapter-execution-context/root execution-context)
          (context-root execution-context))))
