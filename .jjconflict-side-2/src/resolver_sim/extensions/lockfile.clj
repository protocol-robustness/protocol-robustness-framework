(ns resolver-sim.extensions.lockfile
  "Extension lockfile format: a content-addressed pin of a resolution snapshot.

   A lockfile records, per package, the pinned version and package root plus
   the run-level :extensions/resolution-root, so a sealed run can be reproduced
   without examining a developer's classpath. This is the pinning mechanism for
   sealed extensions (ADR-0005, Section 1 and 4)."
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const lockfile-version 1)

(def ^:const lockfile-domain-tag
  "EXTENSION_LOCKFILE_V1")

;; ── builder ───────────────────────────────────────────────────────────────

(defn- package-pins
  [resolution]
  (->> (:extensions/packages resolution)
       (sort-by key)
       (mapv (fn [[_ p]]
               {:package/id (:package/id p)
                :package/version (:package/version p)
                :package-root (:package-root p)
                :sealed (:sealed p)}))))

(defn build-lockfile
  "Build a lockfile map from a valid resolution snapshot."
  [resolution]
  (let [base {:lockfile/version lockfile-version
              :lockfile/packages (package-pins resolution)
              :lockfile/resolution-root (:extensions/resolution-root resolution)}
        root (hc/domain-hash lockfile-domain-tag
                             (select-keys base
                                          [:lockfile/version
                                           :lockfile/packages
                                           :lockfile/resolution-root]))]
    (assoc base :lockfile/hash root)))

;; ── validation ────────────────────────────────────────────────────────────

(defn validate-lockfile
  "Validate a lockfile structurally and check its committed hash.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [lockfile]
  (let [v (cond-> []
            (not= lockfile-version (:lockfile/version lockfile))
            (conj :unsupported-lockfile-version)

            (not (vector? (:lockfile/packages lockfile)))
            (conj :invalid-packages)

            (not (string? (:lockfile/resolution-root lockfile)))
            (conj :invalid-resolution-root)

            (not (string? (:lockfile/hash lockfile)))
            (conj :invalid-hash))

        v (if (and (empty? v)
                   (vector? (:lockfile/packages lockfile)))
            (let [bad (keep (fn [p]
                              (cond
                                (not (keyword? (:package/id p))) :invalid-package-id
                                (not (string? (:package/version p))) :invalid-package-version
                                (not (string? (:package-root p))) :invalid-package-root
                                (not (contains? #{:unsealed :source-pinned :artifact-replayable}
                                                (:sealed p))) :invalid-sealed-classification
                                :else nil))
                            (:lockfile/packages lockfile))]
              (if (seq bad) (into v (distinct bad)) v))
            v)
        v (if (empty? v)
            (let [computed (hc/domain-hash lockfile-domain-tag
                                           (select-keys lockfile
                                                        [:lockfile/version
                                                         :lockfile/packages
                                                         :lockfile/resolution-root]))]
              (if (= computed (:lockfile/hash lockfile))
                v
                (conj v :hash-mismatch)))
            v)]
    {:valid? (empty? v)
     :errors v}))

;; ── I/O ───────────────────────────────────────────────────────────────────

(defn parse-lockfile
  "Parse a lockfile from an EDN string."
  [s]
  (edn/read-string s))

(defn pr-str-lockfile
  "Serialize a lockfile to an EDN string."
  [lockfile]
  (pr-str lockfile))
