(ns resolver-sim.commands.publication
  "Trace an authoritative publication chain from a head root, ordering root,
   or partition identifier.

   Usage:
     java -jar prf.jar publication trace --head-root sha256:...
     java -jar prf.jar publication trace --ordering-root sha256:...
     java -jar prf.jar publication trace --partition sha256:...

   The trace walks the full H -> B -> O -> R -> P -> output -> allocation ->
   transition -> (V2: compilation) chain with per-edge resolution status and
   typed failure reasons. CAS-only entry points (--head-root, --ordering-root)
   require only a CAS directory; --partition additionally requires PostgreSQL."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.read-model :as read-model]
            [resolver-sim.pro-rata.postgres-publication-store :as pg]
            [resolver-sim.db.pool :as pool]))

(defn- create-resolution-fn
  "Build a CAS resolution function from a CAS directory path.
   Returns a function [root -> body-or-nil]."
  [cas-dir]
  (let [dir (or cas-dir (System/getenv "PRF_CAS_DIR"))]
    (when dir
      (let [store (cas/create-store dir)]
        (fn [root] (cas/resolve-artifact store root))))))

(defn- valid-root?
  "Validate that a string is a well-formed sha256 reference."
  [root]
  (and (string? root) (ref/valid-sha256-ref? root)))

(defn- print-human-report
  "Print a human-readable trace report to stdout."
  [trace-result]
  (let [edges (:trace/edges trace-result)
        status (:trace/status trace-result)
        reasons (:trace/reasons trace-result)]
    (println "Publication Trace")
    (println (format "  entry-point: %s" (:trace/entry-point trace-result)))
    (println (format "  status:      %s" (name status)))
    (when (seq reasons)
      (println (format "  reasons:     %s" (str/join ", " (map name reasons)))))
    (println)
    (if (empty? edges)
      (println "  (no edges resolved)")
      (doseq [edge edges]
        (let [edge-status (if (:resolved? edge)
                            "OK"
                            (str "FAIL: " (name (:reason edge))))
              root (or (:root edge) "-")
              role (name (:artifact-role edge))]
          (println (format "  %-45s %-18s %s" role root edge-status)))))))

(defn- print-diagnostic-report
  "Print a human-readable diagnostic result from resolve-authoritative-publication."
  [result]
  (let [authority (:authority result)
        correspondence (:correspondence result)
        semantic (:semantic-recompilation result)
        reachability (:reachability result)]
    (println "Publication Diagnostics")
    (println (format "  authority:              %s%s"
                     (name (:status authority))
                     (when-let [r (:reason authority)]
                       (str " (" (name r) ")"))))
    (println (format "  correspondence:         %s%s"
                     (name (:status correspondence))
                     (when-let [r (:reason correspondence)]
                       (str " (" (name r) ")"))))
    (println (format "  semantic-recompilation: %s%s"
                     (name (:status semantic))
                     (when-let [r (:reason semantic)]
                       (str " (" (name r) ")"))))
    (println (format "  reachability:           %s%s"
                     (name (:status reachability))
                     (when-let [r (:reason reachability)]
                       (str " (" (name r) ")"))))))

(defn- trace-from-head-root
  "CAS-only trace from a publication head root."
  [head-root opts]
  (if-let [resolution (create-resolution-fn (:cas-dir opts))]
    (let [trace (read-model/trace-authoritative-publication
                 resolution {:head-root head-root})]
      (if (:json? opts)
        (println (json/write-str (read-model/public-trace trace) :indent true))
        (print-human-report (read-model/public-trace trace)))
      {:exit-code (if (= :complete (:trace/status trace)) 0 1)
       :message (str "trace status: " (name (:trace/status trace)))})
    (do (println "No CAS directory configured. Set --cas-dir or PRF_CAS_DIR.")
        {:exit-code 2 :message "No CAS directory configured"})))

(defn- trace-from-ordering-root
  "CAS-only trace from an ordering root."
  [ordering-root opts]
  (if-let [resolution (create-resolution-fn (:cas-dir opts))]
    (let [trace (read-model/trace-authoritative-publication
                 resolution {:ordering-root ordering-root})]
      (if (:json? opts)
        (println (json/write-str (read-model/public-trace trace) :indent true))
        (print-human-report (read-model/public-trace trace)))
      {:exit-code (if (= :complete (:trace/status trace)) 0 1)
       :message (str "trace status: " (name (:trace/status trace)))})
    (do (println "No CAS directory configured. Set --cas-dir or PRF_CAS_DIR.")
        {:exit-code 2 :message "No CAS directory configured"})))

(defn- trace-from-partition
  "Trace from a PostgreSQL partition identifier.
   Uses PostgreSQL to resolve the head, then traces the downstream CAS chain."
  [partition-id opts]
  (let [database-url (or (:database-url opts) (System/getenv "DATABASE_URL"))
        db-spec (when database-url {:jdbc-url database-url})]
    (if (and db-spec (create-resolution-fn (:cas-dir opts)))
      (let [ds (pool/pool db-spec {})
            cas-resolver (create-resolution-fn (:cas-dir opts))
            store (pg/postgres-store ds {:resolve-durable-artifact cas-resolver})
            head (try
                   (pg/current-head-by-partition-id store partition-id)
                   (catch Exception _ nil))]
        (try
          (if head
            (let [trace (read-model/trace-authoritative-publication
                         cas-resolver {:head-root (:publication/head-root head)})]
              (if (:json? opts)
                (println (json/write-str (read-model/public-trace trace) :indent true))
                (print-human-report (read-model/public-trace trace)))
              {:exit-code (if (= :complete (:trace/status trace)) 0 1)
               :message (str "trace status: " (name (:trace/status trace)))})
            (do (println (format "No publication head found for partition: %s" partition-id))
                {:exit-code 1 :message "partition not found"}))
          (finally
            (.close ^java.sql.Connection ds))))
      (do (println "Partition tracing requires both PostgreSQL (DATABASE_URL) and CAS (--cas-dir or PRF_CAS_DIR).")
          {:exit-code 2 :message "Missing database-url or cas-dir"}))))

(defn trace-run
  "publication trace --head-root REF | --ordering-root REF | --partition REF"
  [{:keys [head-root ordering-root partition cas-dir database-url json?]}]
  (cond
    (not (or head-root ordering-root partition))
    (do (println "Usage: prf.jar publication trace [--head-root REF | --ordering-root REF | --partition REF]")
        (println "  --head-root REF      Trace from a publication head root (CAS-only)")
        (println "  --ordering-root REF  Trace from an ordering root (CAS-only)")
        (println "  --partition REF      Trace from a partition identifier (requires PostgreSQL)")
        (println "  --cas-dir DIR       CAS directory (default: PRF_CAS_DIR)")
        (println "  --json               Output as JSON")
        {:exit-code 2 :message "Missing --head-root, --ordering-root, or --partition"})

    (and partition (or head-root ordering-root))
    (do (println "Error: --partition cannot be combined with --head-root or --ordering-root.")
        {:exit-code 2 :message "Mutually exclusive options"})

    head-root
    (if (valid-root? head-root)
      (trace-from-head-root head-root {:cas-dir cas-dir :json? json?})
      (do (println "Invalid head root reference:")
          (println (format "  %s" head-root))
          {:exit-code 2 :message "Invalid head root reference"}))

    ordering-root
    (if (valid-root? ordering-root)
      (trace-from-ordering-root ordering-root {:cas-dir cas-dir :json? json?})
      (do (println "Invalid ordering root reference:")
          (println (format "  %s" ordering-root))
          {:exit-code 2 :message "Invalid ordering root reference"}))

    partition
    (if (valid-root? partition)
      (trace-from-partition partition {:cas-dir cas-dir :database-url database-url :json? json?})
      (do (println "Invalid partition reference:")
          (println (format "  %s" partition))
          {:exit-code 2 :message "Invalid partition reference"}))))
