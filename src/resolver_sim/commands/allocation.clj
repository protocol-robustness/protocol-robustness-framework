(ns resolver-sim.commands.allocation
  "PRF allocation command family.

   Commands:
     allocation build-context     — build and print the canonical allocation context
     allocation verify-proposal   — run the reference kernel and print public values
     allocation vectors           — generate and print conformance vectors
     allocation issue-certificate — compose allocation-assurance-certificate.v1

   Conventions:
     - input read from an explicit file path or from stdin when \"-\" or absent;
     - canonical JSON result written to stdout;
     - diagnostics written only to stderr;
     - nonzero exit status for malformed or non-passing requests;
     - no keywords or tagged literals in JSON output;
     - arbitrary-size integers serialized as decimal strings."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [buddy.core.keys :as keys]
            [resolver-sim.allocation.certificate :as cert]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.roots :as roots]
            [resolver-sim.allocation.vectors :as vectors]))

(defn- stderr [& lines]
  (binding [*out* *err*]
    (doseq [line lines] (println line))))

(defn- read-input
  "Read a JSON input document from a file path, or stdin when path is \"-\" or
   absent. Returns the parsed value, or nil on failure."
  [path]
  (let [source (if (or (nil? path) (= "-" path))
                 (io/reader *in*)
                 (try
                   (io/reader path)
                   (catch Exception _
                     nil)))]
    (when source
      (try
        (json/read-str (slurp source) :key-fn identity)
        (catch Exception _
          nil)))))

(defn- input-result
  "Parse the input document and return either the parsed map or a structured
   malformed-input rejection."
  [path]
  (let [raw (read-input path)]
    (if (and (map? raw) (or (contains? raw "claimants") (contains? raw "allocation-id")))
      raw
      {:result/status :rejected
       :rejection/classification :malformed-input
       :rejection/reason (str "Failed to parse allocation input from " (or path "stdin"))})))

(defn- print-json [value]
  (println (json/write-str (vectors/project-json value))))

(defn- parse-args
  "Resolve the input source from the global --input option or a positional
   argument. Rejects unexpected positional args."
  [opts]
  (let [args (:cmd/args opts)
        option-input (:input opts)]
    (loop [args (vec args)
           positional-input nil]
      (cond
        (empty? args)
        {:input (or option-input positional-input)}

        (str/starts-with? (first args) "--")
        {:error (str "Unexpected option: " (first args))}

        (some? positional-input)
        {:error (str "Unexpected positional argument: " (first args))}

        :else
        (recur (subvec args 1) (first args))))))

(defn- run-result->exit [result]
  (if (= :passing (:result/status result)) 0 1))

(defn build-context
  "allocation build-context [--input FILE|-]
   Build and print the canonical allocation context with its hash and roots."
  [opts]
  (let [{:keys [input error]} (parse-args opts)]
    (cond
      error (do (stderr (str "allocation build-context: " error))
                {:exit-code 2 :message error})

      :else
      (let [input-doc (input-result input)]
        (if (:rejection/classification input-doc)
          (do (stderr (str "allocation build-context: " (:rejection/reason input-doc)))
              (print-json input-doc)
              {:exit-code 1 :message (:rejection/reason input-doc)})
          (try
            (let [ctx (context/build-context input-doc)
                  ctx-hash (context/context-hash ctx)]
              (print-json {:result/status :passing
                           :allocation-context-hash ctx-hash
                           :claimant-set-root (roots/claimant-set-root ctx)
                           :outcome-set-root (roots/outcome-set-root ctx)
                           :proposed-rates-root (roots/proposed-rates-root ctx)
                           :allocation-kernel-version context/kernel-version
                            :selection-algorithm context/selection-algorithm-str}
              {:exit-code 0 :message "context built"})
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)
                    result {:result/status :rejected
                            :rejection/classification (or (:rejection/classification data) :malformed-input)
                            :rejection/reason (or (:rejection/reason data) (.getMessage e))}]
                (stderr (str "allocation build-context: " (:rejection/reason result)))
                (print-json result)
                {:exit-code 1 :message (:rejection/reason result)}))))))))

(defn verify-proposal
  "allocation verify-proposal [--input FILE|-]
   Run the reference kernel and print the stable public-value projection."
  [opts]
  (let [{:keys [input error]} (parse-args opts)]
    (cond
      error (do (stderr (str "allocation verify-proposal: " error))
                {:exit-code 2 :message error})

      :else
      (let [input-doc (input-result input)]
        (if (:rejection/classification input-doc)
          (do (stderr (str "allocation verify-proposal: " (:rejection/reason input-doc)))
              (print-json input-doc)
              {:exit-code 1 :message (:rejection/reason input-doc)})
          (let [result (kernel/run-kernel input-doc)]
            (when-let [reason (:rejection/reason result)]
              (stderr (str "allocation verify-proposal: " reason)))
            (print-json (vectors/public-value-projection result))
            {:exit-code (run-result->exit result)
             :message (if (= :passing (:result/status result))
                        "allocation verified"
                        (str "rejected: " (name (:rejection/classification result))))}))))))

(defn vectors
  "allocation vectors
   Generate and print the full conformance vector suite as a JSON array."
  [opts]
  (let [parsed (parse-args opts)]
    (if (:error parsed)
      (do (stderr (str "allocation vectors: " (:error parsed)))
          {:exit-code 2 :message (:error parsed)})
      (let [all (vectors/all-vectors)]
        (print-json (mapv (fn [v]
                            {:vector_version (:vector_version v)
                             :vector_id (:vector_id v)
                             :description (:description v)
                             :input (:input v)
                             :expected (:expected v)})
                          all))
        {:exit-code 0 :message (str (count all) " vectors generated")}))))

(defn issue-certificate
  "allocation issue-certificate [--input FILE|-] [--key KEY --key-id ID]
   Run the reference kernel and compose allocation-assurance-certificate.v1.

   The certificate always carries its content-addressed :certificate/hash.
   When --key and --key-id are supplied, the certificate is additionally
   signed as an attestation by that issuer key (see
   resolver-sim.allocation.certificate/sign-certificate)."
  [{:keys [key key-id] :as opts}]
  (let [{:keys [input error]} (parse-args opts)]
    (cond
      error (do (stderr (str "allocation issue-certificate: " error))
                {:exit-code 2 :message error})

      (and key (nil? key-id))
      {:exit-code 2 :message "issue-certificate: --key-id is required when --key is supplied"}

      :else
      (let [input-doc (input-result input)]
        (if (:rejection/classification input-doc)
          (do (stderr (str "allocation issue-certificate: " (:rejection/reason input-doc)))
              (print-json input-doc)
              {:exit-code 1 :message (:rejection/reason input-doc)})
          (let [result (kernel/run-kernel input-doc)]
            ;; A rejected kernel result is useful diagnostic evidence, but never
            ;; an issuable assurance artifact. In particular, do not attach a
            ;; certificate identity or signer attestation to a failed proposal:
            ;; those fields are easily misread as authoritative issuance.
            (if (not= :passing (:result/status result))
              (do
                (stderr (str "allocation issue-certificate: issuance forbidden — "
                             (:rejection/reason result)))
                (print-json {:result/status :rejected
                             :certificate/issued? false
                             :rejection/classification (:rejection/classification result)
                             :rejection/reason (:rejection/reason result)})
                {:exit-code 1
                 :message (str "certificate issuance forbidden: "
                               (name (:rejection/classification result)))})
              (let [certificate (cert/compose-certificate result)
                    certificate (if (and key key-id)
                                  (let [private-key (keys/private-key key)]
                                    (cert/sign-certificate certificate private-key key-id))
                                  certificate)]
                (print-json certificate)
                {:exit-code 0 :message "certificate issued"}))))))))
