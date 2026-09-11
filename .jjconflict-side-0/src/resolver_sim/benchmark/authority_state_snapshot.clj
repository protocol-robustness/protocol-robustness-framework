(ns resolver-sim.benchmark.authority-state-snapshot
  "P1 rooted durable-authority snapshot and immutable dependency-closure
  contracts. These artifacts identify a complete logical store state; they do
  not themselves choose a filesystem commit protocol."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const snapshot-schema "authority-state-snapshot.v1")
(def ^:const dependency-manifest-schema "authority-state-dependency-manifest.v1")
(def ^:const snapshot-domain :authority-state-snapshot-v1)
(def ^:const dependency-manifest-domain :authority-state-dependency-manifest-v1)
(def ^:const store-format "authority-state-store.v1")

(def snapshot-fields
  #{:artifact/schema
    :authority-store/format
    :authority-store/id
    :chain-instance-genesis/root
    :publication/version
    :current-authoritative-state-envelope/root
    :current-configuration-head/root
    :current-chain-configuration/root
    :current-authority-semantics-policy/root
    :current-governed-authority-semantics/root
    :current-authority-material-manifest/root
    :activation-lineage-index/root
    :issued-fence-index/root
    :terminal-result-index/root
    :receipt-index/root
    :authority-state-dependency-manifest/root
    :authority-state-snapshot/root})

(def dependency-manifest-fields
  #{:artifact/schema
    :dependency/roots
    :authority-state-dependency-manifest/root})

(defn- root? [value]
  (ref/valid-sha256-ref? value))

(defn- root-of [domain value]
  (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe value))))

(defn dependency-manifest-root [manifest]
  (root-of dependency-manifest-domain
           (dissoc manifest :authority-state-dependency-manifest/root)))

(defn snapshot-root [snapshot]
  (root-of snapshot-domain
           (dissoc snapshot :authority-state-snapshot/root)))

(defn- sorted-roots [roots]
  (vec (sort roots)))

(defn validate-dependency-manifest [manifest]
  (let [have (if (map? manifest) (set (keys manifest)) #{})
        roots (:dependency/roots manifest)
        expected-root (when (map? manifest) (dependency-manifest-root manifest))
        errors (cond-> []
                 (not (map? manifest)) (conj "dependency manifest must be a map")
                 (and (map? manifest) (not= dependency-manifest-schema (:artifact/schema manifest)))
                 (conj "dependency manifest schema is invalid")
                 (and (map? manifest) (not= dependency-manifest-fields have))
                 (conj "dependency manifest has an invalid shape")
                 (not (vector? roots)) (conj "dependency roots must be a vector")
                 (and (vector? roots) (not= roots (sorted-roots roots)))
                 (conj "dependency roots must be unique and sorted")
                 (and (vector? roots) (not-every? root? roots))
                 (conj "dependency root is invalid")
                 (and (map? manifest)
                      (not= (:authority-state-dependency-manifest/root manifest) expected-root))
                 (conj "dependency manifest self root does not match"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-dependency-manifest [roots]
  (let [candidate {:artifact/schema dependency-manifest-schema
                   :dependency/roots (sorted-roots roots)}
        manifest (assoc candidate :authority-state-dependency-manifest/root
                        (dependency-manifest-root candidate))]
    (when-not (:valid? (validate-dependency-manifest manifest))
      (throw (ex-info "authority-state dependency manifest is invalid" {})))
    manifest))

(defn snapshot-dependency-roots [snapshot]
  (set (map snapshot
            [:current-authoritative-state-envelope/root
             :current-configuration-head/root
             :current-chain-configuration/root
             :current-authority-semantics-policy/root
             :current-governed-authority-semantics/root
             :current-authority-material-manifest/root
             :activation-lineage-index/root
             :issued-fence-index/root
             :terminal-result-index/root
             :receipt-index/root])))

(defn validate-snapshot [snapshot]
  (let [have (if (map? snapshot) (set (keys snapshot)) #{})
        root-fields (disj snapshot-fields :artifact/schema :authority-store/format :publication/version
                          :authority-state-snapshot/root)
        expected-root (when (map? snapshot) (snapshot-root snapshot))
        errors (cond-> []
                 (not (map? snapshot)) (conj "authority snapshot must be a map")
                 (and (map? snapshot) (not= snapshot-schema (:artifact/schema snapshot)))
                 (conj "authority snapshot schema is invalid")
                 (and (map? snapshot) (not= snapshot-fields have))
                 (conj "authority snapshot has an invalid shape")
                 (and (map? snapshot) (not= store-format (:authority-store/format snapshot)))
                 (conj "authority snapshot store format is invalid")
                 (not (and (integer? (:publication/version snapshot))
                           (not (neg? (:publication/version snapshot)))))
                 (conj "authority snapshot publication version is invalid")
                 (and (map? snapshot) (not-every? #(root? (get snapshot %)) root-fields))
                 (conj "authority snapshot root field is invalid")
                 (and (map? snapshot)
                      (not= (:authority-state-snapshot/root snapshot) expected-root))
                 (conj "authority snapshot self root does not match"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-snapshot
  "Build a closed snapshot and its exact dependency manifest. The caller supplies
  only actual root identities; it cannot nominate either derived self root."
  [snapshot]
  (let [snapshot (dissoc snapshot :artifact/schema
                         :authority-state-dependency-manifest/root
                         :authority-state-snapshot/root)
        manifest (build-dependency-manifest (snapshot-dependency-roots snapshot))
        candidate (assoc snapshot
                         :artifact/schema snapshot-schema
                         :authority-state-dependency-manifest/root
                         (:authority-state-dependency-manifest/root manifest))
        result (assoc candidate :authority-state-snapshot/root (snapshot-root candidate))]
    (when-not (:valid? (validate-snapshot result))
      (throw (ex-info "authority-state snapshot is invalid" {})))
    {:snapshot result :dependency-manifest manifest}))

(defn verify-snapshot-closure!
  "Verify an independently read snapshot before it is eligible as `current`.

  `read-object` resolves immutable bodies by root. `verify-object` must verify
  each resolved dependency's canonical root and artifact-specific semantics.
  `verify-semantic-joins` receives the snapshot and resolved root-to-body map
  and must prove E/H/C/P/S/material/index relationships for the store profile.
  No fallback to an earlier snapshot is performed here."
  [snapshot read-object verify-object verify-semantic-joins]
  (when-not (:valid? (validate-snapshot snapshot))
    (throw (ex-info "authority-state snapshot is invalid" {:reason :invalid-authority-state-snapshot})))
  (let [manifest-root (:authority-state-dependency-manifest/root snapshot)
        manifest (read-object manifest-root)]
    (when-not manifest
      (throw (ex-info "authority-state dependency manifest is unavailable"
                      {:reason :missing-snapshot-dependency-manifest :root manifest-root})))
    (when-not (:valid? (validate-dependency-manifest manifest))
      (throw (ex-info "authority-state dependency manifest is invalid"
                      {:reason :invalid-snapshot-dependency-manifest :root manifest-root})))
    (when-not (= manifest-root (:authority-state-dependency-manifest/root manifest))
      (throw (ex-info "authority-state dependency manifest root is substituted"
                      {:reason :snapshot-dependency-manifest-root-mismatch :root manifest-root})))
    (let [expected (snapshot-dependency-roots snapshot)
          declared (set (:dependency/roots manifest))]
      (when-not (= expected declared)
        (throw (ex-info "authority-state dependency closure is incomplete or substituted"
                        {:reason :snapshot-dependency-closure-mismatch
                         :expected expected :declared declared})))
      (let [objects (into {}
                          (map (fn [root]
                                 (let [object (read-object root)]
                                   (when-not object
                                     (throw (ex-info "authority-state dependency is unavailable"
                                                     {:reason :missing-snapshot-dependency :root root})))
                                   (when-not (verify-object root object)
                                     (throw (ex-info "authority-state dependency failed verification"
                                                     {:reason :invalid-snapshot-dependency :root root})))
                                   [root object])))
                          declared)]
        (when-not (verify-semantic-joins snapshot objects)
          (throw (ex-info "authority-state snapshot semantic joins are invalid"
                          {:reason :invalid-authority-state-snapshot-joins})))
        {:snapshot snapshot :dependency-manifest manifest :objects objects}))))
