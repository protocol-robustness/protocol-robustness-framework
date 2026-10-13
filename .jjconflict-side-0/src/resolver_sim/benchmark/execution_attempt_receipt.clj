(ns resolver-sim.benchmark.execution-attempt-receipt
  "Derived execution lifecycle artifacts.

   The receipt is derived from a command and the observed execution attempt;
   callers cannot select or attach its root(ns resolver-sim.benchmark.execution-attempt-receipt). Runtime hints are deliberately
   excluded from semantic identity, while semantic parameters are committed."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const command-schema-version "execution-command.v1")
(def ^:const attempt-schema-version "execution-attempt.v1")
(def ^:const receipt-schema-version "execution-attempt-receipt.v1")

(defn classify-execution-hints
  "Return explicit runtime/semantic hint projections. Unknown hints fail closed."
  [hints]
  (let [hints (or hints {})
        runtime (select-keys hints [:runtime/host :runtime/worker :runtime/trace-id
                                    :runtime/retry-count])
        semantic (select-keys hints [:execution/parameters :execution/environment
                                     :execution/seed :execution/limits])]
    (when-not (= (set (keys hints))
                 (set (concat (keys runtime) (keys semantic))))
      (throw (ex-info "unknown execution hint" {:hints hints})))
    {:runtime-only runtime :semantic semantic}))

(defn command
  "Build the canonical command composition. The optional receipt root is never
   accepted here; it is produced only after an attempt exists."
  [input]
  (let [command-id (:command/id input)
        operation (:command/operation input)
        inputs (:command/inputs input)
        hints (:execution/hints input)
        classified (classify-execution-hints hints)]
    {:schema-version command-schema-version
     :command/id command-id
     :command/operation operation
     :command/inputs (hc/project-canonical-safe inputs)
     :execution/semantic-hints (:semantic classified)}))

(defn command-root [command]
  (hc/domain-hash "execution-command.v1" command))

(defn execution-attempt
  "Derive an attempt from a canonical command and observed execution outcome."
  [input]
  (let [command (:command input)
        status (:attempt/status input)
        output-root (:attempt/output-root input)
        error (:attempt/error input)
        body {:schema-version attempt-schema-version
              :command/root (command-root command)
              :attempt/status status
              :attempt/output-root output-root
              :attempt/error error}]
    (assoc body :attempt/root (hc/domain-hash "execution-attempt.v1" body))))

(defn derived-attempt-receipt
  "Derive the receipt from the command and exact attempt. No receipt hash is an
   input; the returned root is computed from the complete receipt body."
  [command attempt]
  (let [body {:schema-version receipt-schema-version
              :command/root (command-root command)
              :attempt/root (:attempt/root attempt)
              :attempt/status (:attempt/status attempt)
              :attempt/output-root (:attempt/output-root attempt)}]
    (assoc body :receipt/root
           (hc/domain-hash "execution-attempt-receipt.v1" body))))

(defn valid-receipt?
  [receipt]
  (and (= receipt-schema-version (:schema-version receipt))
       (= (:receipt/root receipt)
          (hc/domain-hash "execution-attempt-receipt.v1"
                          (dissoc receipt :receipt/root)))))
