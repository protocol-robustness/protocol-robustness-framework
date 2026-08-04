(ns resolver-sim.trace.conformance.runner
  "Conformance validation runner for trace fixtures.

   Reads the trace-solidity manifest, runs the schema + semantic validators over
   every declared SOURCE fixture, and writes a conformance receipts file next to
   each source (`<source>.conformance.json`).  These receipts are the OBSERVED
   evidence for capability satisfaction (semantic-validation, canonicalization)
   consumed by scripts/reconcile.py.

   The runner is pure validation — it never mutates fixtures."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.trace.conformance.validators :as validators]))

(defn- fixture-source-path
  "The CDRS fixture source path within a manifest entry block."
  [block]
  (second (re-find #":source\s+\"([^\"]+)\"" block)))

(defn- manifest-entry-blocks
  "Split the manifest :traces block into entry substrings."
  [raw]
  (let [start (.indexOf raw ":traces")]
    (if (neg? start) [] (re-seq #"\{:id[^}]*\}" (subs raw start)))))

(defn- source-paths-from-manifest
  [manifest-path]
  (->> (manifest-entry-blocks (slurp manifest-path))
       (keep fixture-source-path)
       (filter #(not (re-find #"\.edn$" %)))))

(defn validate-source!
  "Validate one source fixture and write its conformance receipts file.
   Returns {:source <path> :valid? bool :conformance-receipt <map>}."
  [repo-root source]
  (let [abs-source (io/file repo-root source)
        _ (when-not (.exists abs-source)
            (throw (ex-info "manifest source fixture not found" {:source source})))
        fixture (json/read-str (slurp abs-source) :key-fn keyword)
        {:keys [valid? results]} (validators/validate-fixture fixture)
        receipt {:fixture-contract "trace-fixture.v2"
                 :subject/root (first (map :validation/subject-root results))
                 :valid? valid?
                 :validators (mapv (fn [r]
                                     {:validation/id (:validation/id r)
                                      :validation/kind (:validation/kind r)
                                      :validation/version (:validation/version r)
                                      :validation/status (:validation/status r)
                                      :validation/implementation-root (:validation/implementation-root r)
                                      :validation/issues (count (:validation/issues r))})
                                   results)}
        out-path (str abs-source ".conformance.json")]
    (spit out-path (json/write-str receipt
                                  {:indent true
                                   :key-fn (fn [k] (if (keyword? k)
                                                      (if-let [ns (namespace k)]
                                                        (str ns "/" (name k))
                                                        (name k))
                                                      k))}))
    {:source source :valid? valid? :conformance-receipt receipt}))

(defn run-all!
  "Validate every manifest source fixture and write conformance receipts.
   Returns {:results [...] :total N :passed N :rejected N}."
  [manifest-path repo-root]
  (let [sources (source-paths-from-manifest manifest-path)
        results (mapv #(validate-source! repo-root %) sources)
        passed (count (filter :valid? results))
        rejected (- (count results) passed)]
    {:results results
     :total (count results)
     :passed passed
     :rejected rejected}))

(defn -main
  "CLI: validate all manifest source fixtures and write conformance receipts.
   Usage: -m resolver-sim.trace.conformance.runner [manifest-path]"
  [& args]
  (let [manifest (or (first args) "etc/trace-solidity-manifest.edn")
        report (run-all! manifest ".")]
    (doseq [{:keys [source valid?]} (:results report)]
      (println (if valid? "PASS" "REJECT") source))
    (println (str "total=" (:total report) " passed=" (:passed report)
                  " rejected=" (:rejected report)))
    (when (zero? (:passed report))
      (System/exit 1))))
