(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk])

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn edn-file? [f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")
       (not= "manifest.edn" (.getName f))
       (not= "VERSION" (.getName f))))

(defn json-file? [f]
  (and (.isFile f) (str/ends-with? (.getName f) ".json")
       (not (str/includes? (.getPath f) "data/fixtures"))))

(defn parse-file [f]
  (let [n (.getName f)]
    (try [(edn/read-string (slurp f)) nil]
         (catch Exception e [nil (.getMessage e)]))))

(defn- stem-from-name [^String n]
  (-> (cond (.endsWith n ".json") (subs n 0 (- (count n) 5))
            (.endsWith n ".edn") (subs n 0 (- (count n) 4))
            :else n)
      (str/replace #"_" "-")
      str/lower-case))

(defn ^String file-stem
  "Extract canonical stem from a File or path string."
  [f]
  (when f
    (let [n (if (instance? java.io.File f) (.getName f) (str f))]
      (stem-from-name n))))

(defn scenario-id-from-content [data]
  (or (:scenario/id data)
      (:id data)
      (get-in data [:metadata :scenario-id])
      (when-let [sid (:simulator/scenario-path data)]
        (-> (io/file sid) (.getName) file-stem))))

;; ── Discovery: files grouped by canonical ID ─────────────────────────────────

(defn discover-scenarios
  "Scan all scenario directories and return a map of canonical ID → file info vector."
  []
  (let [dirs ["scenarios" "scenarios/edn"
              "suites/reference-validation-v1/scenarios"
              "suites/sew-domain-reference-v1/scenarios"
              "suites/dispute-resolution-v2/scenarios"
              "suites/yield-reference-v1/scenarios"]
        by-id (atom {})]
    (doseq [d dirs]
      (let [f (io/file d)]
        (when (.exists f)
          (doseq [entry (file-seq f)]
            (when (and (or (edn-file? entry) (json-file? entry))
                     (not= "catalog.edn" (.getName entry)))
              (let [[data err] (parse-file entry)
                    canonical-id (or (scenario-id-from-content data)
                                     (file-stem entry))
                    info {:path (.getPath entry)
                          :format (if (edn-file? entry) :edn :json)
                          :purpose (or (:purpose data) (get-in data [:metadata :purpose]))
                          :threat-tags (or (:threat-tags data) (:tags data) (get-in data [:metadata :threat-tags]))
                          :claim-id (or (:claim-id data) (get-in data [:metadata :claim-id]))}]
                (swap! by-id update canonical-id (fnil conj []) info)))))))
    by-id))

;; ── Suite manifests (rich annotations) ───────────────────────────────────────

(defn load-suite-manifests []
  (let [paths ["suites/reference-validation-v1/manifest.edn"
               "suites/sew-domain-reference-v1/manifest.edn"
               "suites/dispute-resolution-v2/manifest.edn"
               "suites/yield-reference-v1/manifest.edn"]]
    (reduce (fn [acc mpath]
              (if-let [[data err] (parse-file (io/file mpath))]
                (let [suite-id (:suite/id data)]
                  (reduce (fn [acc2 s]
                            (let [sim-path (:simulator/scenario-path s)
                                  stem (when sim-path
                                          (file-stem (io/file sim-path)))]

                              (if sim-path
                                (-> acc2
                                    (assoc-in [:by-path sim-path] (assoc s :suite-id suite-id))
                                    (assoc-in [:by-stem stem] (assoc s :suite-id suite-id)))
                                acc2)))
                          acc
                          (:scenarios data)))
                acc))
            {:by-path {} :by-stem {}} (map io/file paths))))

;; ── Golden reports ───────────────────────────────────────────────────────────

(defn load-golden-outcomes []
  (let [dir (io/file "data/fixtures/golden")]
    (if (.exists dir)
      (reduce (fn [acc f]
                (if (edn-file? f)
                  (if-let [[data err] (parse-file f)]
                    (let [trace-id (:trace-id data)
                          sid (or (:scenario/id data)
                                  (when trace-id (-> trace-id name file-stem)))]
                      (if sid
                        (assoc acc sid {:golden-path (.getPath f)
                                        :outcome (or (get-in data [:judgement :outcome])
                                                     (:outcome data)
                                                     (get-in data [:result :outcome]))
                                        :claims (:claim-results data)
                                        :invariants (or (:invariant-results data)
                                                        (get-in data [:judgement :invariant-results]))})
                        acc))
                    acc)
                  acc))
              {} (filter edn-file? (file-seq dir)))
      {})))

;; ── Suites.clj path list (suite membership) ──────────────────────────────────

(defn file-in-suite? [path]
  (let [suites-file (io/file "src/resolver_sim/scenario/suites.clj")]
    (when (.exists suites-file)
      (let [content (slurp suites-file)]
        (str/includes? content (str "\"" path "\""))))))

;; ── Catalog entry builder ────────────────────────────────────────────────────

(defn build-entry
  "Build a single catalog entry from a canonical ID and its file infos."
  [canonical-id file-infos suite-manifests golden-outcomes]
  (let [;; Pick the best data source: prefer files with richer metadata
        best-file (first (sort-by (fn [fi]
                                    (let [p (:purpose fi) t (:threat-tags fi) c (:claim-id fi)]
                                      (+ (if p 1 0) (if (seq t) 1 0) (if c 1 0))))
                                  (reverse (sort-by :format file-infos))))
        path (:path best-file)
        format (:format best-file)

        ;; Extract purpose, tags, claim from best file info
        purpose (:purpose best-file)
        threat-tags (:threat-tags best-file)
        claim-id (:claim-id best-file)

        ;; Suite manifest cross-reference (by path or stem)
        rich (or (get-in suite-manifests [:by-path path])
                 (get-in suite-manifests [:by-stem canonical-id]))

        ;; Golden outcome (debug first few)
        _ (when (< (count (take-while (fn [_] true) (repeat nil))) 1) nil) ;; no-op
        golden (get golden-outcomes canonical-id)

        ;; Derive protocol from path
        protocol (cond
                   (str/starts-with? path "scenarios/yield") "yield-v1"
                   (str/includes? path "reference-validation") "sew-v1"
                   (str/includes? path "domain-reference") "sew-v1"
                   :else "sew-v1")]

    (cond->
     {:scenario/id canonical-id
      :scenario/protocol protocol
      :scenario/path path
      :scenario/format format
      :scenario/files (mapv (fn [fi]
                              (select-keys fi [:path :format]))
                            (sort-by :format file-infos))}

      purpose (merge {:scenario/purpose purpose})
      (seq threat-tags) (merge {:scenario/threat-tags (vec (distinct threat-tags))})
      claim-id (merge {:scenario/claims [claim-id]})
      rich (merge {:scenario/classification (:classification rich)
                   :scenario/claim-id (:claim-id rich)
                   :scenario/claim (:claim rich)
                   :scenario/threat (:threat rich)
                   :scenario/invariants (vec (:invariant-ids rich))
                   :scenario/suite-id (:suite-id rich)})
      golden (merge {:scenario/golden-path (:golden-path golden)
                     :scenario/expected-outcome (:outcome golden)}))));; ── Main ─────────────────────────────────────────────────────────────────────

(defn generate-catalog []
  (println "Generating scenario catalog...")
  (let [by-id (discover-scenarios)
        suite-manifests (load-suite-manifests)
        golden-outcomes (load-golden-outcomes)
        _ (println "  Golden outcomes loaded:" (count golden-outcomes) "keys")
        entries (mapv (fn [[canonical-id file-infos]]
                        (build-entry canonical-id file-infos suite-manifests golden-outcomes))
                       (sort-by first @by-id))
        catalog {:catalog/version 1
                 :catalog/description
                 "Unified scenario catalog. One entry per canonical scenario ID, with
                  cross-references to suite manifests, claims, invariants, golden
                  reports, and file locations. Generated — do not edit manually."
                 :catalog/scenario-count (count entries)
                 :catalog/generated-at (str (java.util.Date.))
                 :scenarios entries}]
    (println "  Serializing catalog...")
    (spit "scenarios/catalog.edn" (pr-str catalog))
    (println "  Catalog written.")
    (println (count entries) "scenarios catalogued → scenarios/catalog.edn")
    catalog))

(let [catalog (generate-catalog)]
  (println "Done.")
  (System/exit 0))
