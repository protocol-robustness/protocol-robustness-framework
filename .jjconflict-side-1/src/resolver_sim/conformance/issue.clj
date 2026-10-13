(ns resolver-sim.conformance.issue
  "Stable issue-code envelope (G7b).

   The stable contract is the issue CODE and structured fields, not human text.
   Each code maps to a class and severity so the CLI, CI, bundle verifier, and
   external tooling interoperate on the same semantics.")

(def issue-classes
  [:schema :semantic :identity :registry :capability :plan :execution
   :reconciliation :coverage :cryptography :policy :version :bundle])

;; issue code -> {:class <kw> :severity :error|:warning}
(def ^:private issue-metadata
  {:validator-not-resolved            {:class :registry :severity :error}
   :duplicate-validator-id            {:class :registry :severity :error}
   :required-layer-skipped            {:class :semantic :severity :error}
   :unknown-action                    {:class :semantic :severity :error}
   :unknown-role                      {:class :semantic :severity :error}
   :ambiguous-execute-resolution      {:class :semantic :severity :error}
   :undefined-wf-alias                {:class :semantic :severity :error}
   :unknown-subject-id                {:class :identity :severity :error}
   :unlinked-subject-root             {:class :identity :severity :error}
   :profile-root-mismatch             {:class :identity :severity :error}
   :inconsistent-canonical-root       {:class :identity :severity :error}
   :multiple-subject-kinds            {:class :identity :severity :error}
   :inclusion-exclusion-root-conflict {:class :identity :severity :error}
   :unresolved-implementation         {:class :registry :severity :error}
   :implementation-kind-mismatch      {:class :registry :severity :error}
   :missing-summary                   {:class :schema :severity :error}
   :missing-calculation-trace         {:class :schema :severity :error}
   :outcome-root-not-reproducible     {:class :semantic :severity :error}
   :unsupported-bundle-version        {:class :version :severity :error}
   :reconciliation-not-reproducible   {:class :reconciliation :severity :error}
   :derived-claim-mismatch            {:class :claim :severity :error}
   :claim-json-root-mismatch          {:class :claim :severity :error}
   :missing-reference                 {:class :coverage :severity :error}
   :duplicate-reference-root          {:class :coverage :severity :error}
   :unexpected-embedded-artifact      {:class :coverage :severity :warning}
   :unsupported-cdrs-version          {:class :version :severity :error}
   :unsupported-schema-version        {:class :version :severity :error}})

(defn- code->class
  "Derive a class from a code when not explicit: namespaced codes map to their
   namespace (e.g. :missing-authenticity -> :policy)."
  [code]
  (or (get-in issue-metadata [code :class])
      (when-let [ns (namespace code)] (keyword ns))
      :semantic))

(defn issue
  "Build a stable issue envelope.
     (issue code) or (issue code details)
   Returns {:issue/code ... :issue/class ... :issue/severity ...
            :issue/path [] :issue/subject-id nil :issue/details {...}}."
  ([code] (issue code {}))
  ([code details]
   {:issue/code code
    :issue/class (code->class code)
    :issue/severity (get-in issue-metadata [code :severity] :error)
    :issue/path []
    :issue/subject-id nil
    :issue/details details}))

(defn classify-issue
  "Extract the machine-relevant fields from any issue-shaped map."
  [issue-map]
  (let [code (or (:issue/code issue-map) (:violation/id issue-map))]
    {:issue/code code
     :issue/class (code->class code)
     :issue/severity (get-in issue-metadata [code :severity] :error)}))
