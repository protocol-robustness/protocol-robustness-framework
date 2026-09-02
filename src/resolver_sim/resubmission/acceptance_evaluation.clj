(ns resolver-sim.resubmission.acceptance-evaluation
  "Closed, configuration-authorized attempt acceptance evaluation.

   A resolver only retrieves root-addressed bodies. It never decides whether an
   artifact, policy, check, finding, or outcome is valid. The configuration
   selects a rooted definition; this namespace deterministically interprets that
   definition using a closed semantic-check dispatch."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.acceptance-authority-basis :as authority-basis]
            [resolver-sim.resubmission.basis :as basis]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.publisher-authority :as publisher-authority]
            [resolver-sim.resubmission.publisher-statement :as publisher-statement]
            [resolver-sim.resubmission.submission-registry :as submission-registry]
            [resolver-sim.resubmission.execution-evidence-subject :as execution-subject]
            [resolver-sim.resubmission.certificate-subject :as certificate-subject]
            [resolver-sim.resubmission.results-artifact :as results-artifact]
            [resolver-sim.verification.basis :as verification-basis]))

(def ^:const definition-schema "attempt-acceptance-definition.v1")
(def ^:const evaluation-schema "acceptance-evaluation.v1")
(def ^:const definition-domain :prf-attempt-acceptance-definition-v1)
(def ^:const evaluation-domain :prf-acceptance-evaluation-v1)

(def required-check-ids
  [:prf.resubmission.acceptance/submitted-bundle-integrity-v1
   :prf.resubmission.acceptance/submission-basis-integrity-v1
   :prf.resubmission.acceptance/submission-components-present-v1
   :prf.resubmission.acceptance/submission-registry-integrity-v1
   :prf.resubmission.acceptance/authority-registries-verifiable-v1
   :prf.resubmission.acceptance/verifier-registry-selection-v1
   :prf.resubmission.acceptance/results-binding-v1])

(def check-statuses #{:verified :unavailable :root-mismatch :malformed :semantically-invalid})

(defn definition-projection [definition]
  (select-keys definition [:artifact/schema :definition/version :definition/id :definition/checks]))

(defn definition-root [definition]
  (hash-ref/sha256-ref (hc/domain-hash definition-domain (definition-projection definition))))

(defn valid-definition?
  "Closed V1 definition validation. Checks are semantic propositions, not
   runtime function names; the fixed ordered check list is the V1 outcome rule:
   every non-verified result rejects acceptance."
  [definition]
  (and (map? definition)
       (= definition-schema (:artifact/schema definition))
       (= 1 (:definition/version definition))
       (= #{:artifact/schema :definition/version :definition/id :definition/checks
            :attempt-acceptance-definition/root}
          (set (keys definition)))
       (string? (:definition/id definition))
       (vector? (:definition/checks definition))
       (= required-check-ids (mapv :check/id (:definition/checks definition)))
       (every? #(= #{:check/id :check/subject} (set (keys %)))
               (:definition/checks definition))
       (= [:submitted-bundle :submission-basis :submission-basis :submission-registry
           :authority-basis :results :results]
          (mapv :check/subject (:definition/checks definition)))
       (= (:attempt-acceptance-definition/root definition)
          (definition-root definition))))

(defn build-definition
  ([] (build-definition "prf.resubmission.acceptance.default.v1"))
  ([definition-id]
   (let [definition {:artifact/schema definition-schema
                     :definition/version 1
                     :definition/id definition-id
                     :definition/checks
                     [{:check/id :prf.resubmission.acceptance/submitted-bundle-integrity-v1
                       :check/subject :submitted-bundle}
                      {:check/id :prf.resubmission.acceptance/submission-basis-integrity-v1
                       :check/subject :submission-basis}
                      {:check/id :prf.resubmission.acceptance/submission-components-present-v1
                       :check/subject :submission-basis}
                      {:check/id :prf.resubmission.acceptance/submission-registry-integrity-v1
                       :check/subject :submission-registry}
                      {:check/id :prf.resubmission.acceptance/authority-registries-verifiable-v1
                       :check/subject :authority-basis}
                      {:check/id :prf.resubmission.acceptance/verifier-registry-selection-v1
                       :check/subject :results}
                      {:check/id :prf.resubmission.acceptance/results-binding-v1
                       :check/subject :results}]}]
     (assoc definition :attempt-acceptance-definition/root (definition-root definition)))))

(defn- result [id subject status & {:as detail}]
  (merge {:check/id id :check/subject subject :check/status status} detail))

(defn validate-publisher-binding
  "Validate the historical publisher statement/envelope against the selected
   publisher authority and submitted bundle root. Signature bytes are checked
   by the repository signing adapter at the envelope boundary; this function
   never upgrades key membership into authenticity."
  [statement envelope publisher-authority submitted-bundle-root]
  (let [entry (when (publisher-statement/envelope-binds-statement? envelope statement)
                (publisher-authority/authorized-key
                 publisher-authority (:publisher/key-id statement)))
        authentic? (and (some? entry)
                        (publisher-statement/verify-statement-signature
                         statement (:key/public entry)))]
    {:valid? (and (publisher-statement/statement-binds-bundle?
                   statement submitted-bundle-root)
                  (publisher-statement/envelope-binds-statement? envelope statement)
                  authentic?
                  (some? entry)
                  (= (:principal/id entry) (:publisher/principal-id statement)))
     :reason (cond
               (not (publisher-statement/statement-binds-bundle?
                     statement submitted-bundle-root)) :publisher-bundle-mismatch
               (not (publisher-statement/envelope-binds-statement? envelope statement))
               :publisher-envelope-mismatch
               (not authentic?) :publisher-signature-invalid
               (nil? entry) :publisher-not-authorized
               (not= (:principal/id entry) (:publisher/principal-id statement))
               :publisher-principal-mismatch
               :else :ok)}))

(defn verify-historical-verifier-result
  "Apply the repository's historical verifier-selection gate to an acceptance
   result. The registry and extension resolution are supplied from the
   configuration-selected authority snapshot; this adapter never consults a
   current head and never accepts a verifier nominated by the result.

   `basis` and `result` use the established verification.basis contract."
  [verifier-registry extension-resolution basis result]
  (verification-basis/verify-result-selection
   verifier-registry extension-resolution basis result))

(defn validate-execution-subject-binding
  "Validate that a typed execution-evidence subject names exactly the
   historical evidence, results, and submitted bundle roots."
  [subject evidence-root results-root submitted-bundle-root]
  {:valid? (execution-subject/binds-artifacts?
            subject evidence-root results-root submitted-bundle-root)
   :reason (if (execution-subject/binds-artifacts?
                subject evidence-root results-root submitted-bundle-root)
             :ok
             :execution-subject-binding-mismatch)})

(defn- check-submitted-bundle [resolved]
  (let [{:keys [root body]} (:submitted-bundle resolved)]
    (cond
      (nil? body) (result :prf.resubmission.acceptance/submitted-bundle-integrity-v1
                          :submitted-bundle :unavailable)
      (not (map? body)) (result :prf.resubmission.acceptance/submitted-bundle-integrity-v1
                                :submitted-bundle :malformed)
      (not= root (basis/final-bundle-root body))
      (result :prf.resubmission.acceptance/submitted-bundle-integrity-v1
              :submitted-bundle :root-mismatch :expected root
              :observed (basis/final-bundle-root body))
      :else (result :prf.resubmission.acceptance/submitted-bundle-integrity-v1
                    :submitted-bundle :verified))))

(defn- check-submission-basis [resolved]
  (let [{:keys [root body]} (:submission-basis resolved)]
    (cond
      (nil? body) (result :prf.resubmission.acceptance/submission-basis-integrity-v1
                          :submission-basis :unavailable)
      (not (basis/basis-shape-valid? body))
      (result :prf.resubmission.acceptance/submission-basis-integrity-v1
              :submission-basis :malformed)
      (not= root (basis/submission-basis-root body))
      (result :prf.resubmission.acceptance/submission-basis-integrity-v1
              :submission-basis :root-mismatch :expected root
              :observed (basis/submission-basis-root body))
      :else (result :prf.resubmission.acceptance/submission-basis-integrity-v1
                    :submission-basis :verified))))

(defn- check-submission-registry [resolved]
  (let [{:keys [root body]} (:submission-registry resolved)
        basis-body (get-in resolved [:submission-basis :body])
        content-root (fn [value]
                       (hash-ref/sha256-ref
                        (hc/domain-hash :evidence-record value)))
        expected-roots {:results (:attempt-results-artifact/root
                                  (:results-artifact basis-body))
                        :certificate (content-root (:certificate basis-body))
                        :execution-evidence (content-root (:execution-evidence basis-body))}
        entries (when (map? body) (:submission-registry/entries body))
        mismatches (when (sequential? entries)
                     (->> entries
                          (keep (fn [entry]
                                  (let [role (:entry/role entry)
                                        artifact-root (:artifact/root entry)]
                                    (when (and (contains? expected-roots role)
                                               (not= artifact-root (get expected-roots role)))
                                      {:role role
                                       :expected (get expected-roots role)
                                       :observed artifact-root}))))
                          vec))]
    (cond
      (nil? body)
      (result :prf.resubmission.acceptance/submission-registry-integrity-v1
              :submission-registry :unavailable)

      (not= root (:attempt-submission-registry/root body))
      (result :prf.resubmission.acceptance/submission-registry-integrity-v1
              :submission-registry :root-mismatch :expected root
              :observed (:attempt-submission-registry/root body))

      (not (submission-registry/valid? body))
      (result :prf.resubmission.acceptance/submission-registry-integrity-v1
              :submission-registry :malformed)

      (seq mismatches)
      (result :prf.resubmission.acceptance/submission-registry-integrity-v1
              :submission-registry :entry-root-mismatch :observed mismatches)

      :else
      (result :prf.resubmission.acceptance/submission-registry-integrity-v1
              :submission-registry :verified))))

(defn- check-submission-components [resolved]
  (let [body (get-in resolved [:submission-basis :body])
        required [:results-artifact :certificate :execution-evidence
                  :registry-entries :publisher-policy]
        missing (vec (remove #(contains? body %) required))
        results (get body :results-artifact)]
    (if (seq missing)
      (result :prf.resubmission.acceptance/submission-components-present-v1
              :submission-basis :semantically-invalid :missing missing)
      (if (results-artifact/valid? results)
        (result :prf.resubmission.acceptance/submission-components-present-v1
                :submission-basis :verified)
        (result :prf.resubmission.acceptance/submission-components-present-v1
                :submission-basis :semantically-invalid
                :observed :invalid-results-artifact)))))

(defn- check-authority-registries
  "Verify that genuine authority leaves named by the authority basis resolve
   to map bodies. The submitted registry is content and is checked separately."
  [resolved]
  (let [registries (get-in resolved [:attempt-acceptance-authority-registries])
        basis (get-in resolved [:attempt-acceptance-authority-basis :body])]
    (cond
      (nil? basis) (result :prf.resubmission.acceptance/authority-registries-verifiable-v1
                           :authority-basis :unavailable)
      (nil? registries) (result :prf.resubmission.acceptance/authority-registries-verifiable-v1
                                :authority-basis :malformed)
      :else
      (let [status (fn [entry]
                     (let [body (:body entry)]
                       (cond
                         (nil? body) :unavailable
                         (not (map? body)) :malformed
                         :else :verified)))
            vr-status (status (:verifier-registry registries))
            pa-status (status (:publisher-authority registries))
            er-status (status (:extension-resolution registries))
            all-ok? (and (= :verified vr-status)
                         (= :verified pa-status)
                         (= :verified er-status))]
        (if all-ok?
          (result :prf.resubmission.acceptance/authority-registries-verifiable-v1
                  :authority-basis :verified)
          (result :prf.resubmission.acceptance/authority-registries-verifiable-v1
                  :authority-basis :malformed
                  :verifier-registry-status vr-status
                  :publisher-authority-status pa-status
                  :extension-resolution-status er-status))))))

(defn- check-verifier-registry-selection
  "Verify that the verifier identified by the results-artifact is authorized by
   the verifier-registry named in the authority basis.  The results-artifact
   must carry :results/verifier-id; the verifier-registry body must be a map
   containing a :entries collection whose members have :verifier/id."
  [resolved]
  (let [results (get-in resolved [:submission-basis :body :results-artifact])
        vr-body (get-in resolved [:attempt-acceptance-authority-registries
                                  :verifier-registry :body])
        authority-basis (get-in resolved [:attempt-acceptance-authority-basis :body])]
    (cond
      (nil? authority-basis)
      (result :prf.resubmission.acceptance/verifier-registry-selection-v1
              :results :unavailable)
      (nil? vr-body)
      (result :prf.resubmission.acceptance/verifier-registry-selection-v1
              :results :unavailable)
      (nil? results)
      (result :prf.resubmission.acceptance/verifier-registry-selection-v1
              :results :unavailable)
      (not (map? results))
      (result :prf.resubmission.acceptance/verifier-registry-selection-v1
              :results :malformed)
      (not (map? vr-body))
      (result :prf.resubmission.acceptance/verifier-registry-selection-v1
              :results :malformed)
      :else
      (let [verifier-id (:results/verifier-id results)
            entries (get vr-body :entries)
            entry-ids (set (map :verifier/id entries))]
        (cond
          (nil? verifier-id)
          (result :prf.resubmission.acceptance/verifier-registry-selection-v1
                  :results :semantically-invalid
                  :missing [:results/verifier-id])
          (not (sequential? entries))
          (result :prf.resubmission.acceptance/verifier-registry-selection-v1
                  :results :malformed
                  :expected :entries
                  :observed (type entries))
          (contains? entry-ids verifier-id)
          (result :prf.resubmission.acceptance/verifier-registry-selection-v1
                  :results :verified)
          :else
          (result :prf.resubmission.acceptance/verifier-registry-selection-v1
                  :results :root-mismatch
                  :expected (seq entry-ids)
                  :observed verifier-id))))))

(defn- check-results-binding
  "Verify that the results-artifact is bound to the authority context:
   the results-artifact must carry :results/verifier-id (already checked by
   the verifier-registry-selection check), and the publisher who submitted
   the bundle must be authorized by the publisher-authority named in the
   the authority basis."
  [resolved]
  (let [authority-basis (get-in resolved [:attempt-acceptance-authority-basis :body])
        pa-body (get-in resolved [:attempt-acceptance-authority-registries
                                  :publisher-authority :body])
        results (get-in resolved [:submission-basis :body :results-artifact])
        publisher-policy (get-in resolved [:submission-basis :body :publisher-policy])
        publisher-envelope (get-in resolved [:publisher-envelope :body])
        publisher-statement (get-in resolved [:publisher-statement :body])
        publisher-validation (when (or publisher-envelope publisher-statement)
                               (validate-publisher-binding
                                publisher-statement publisher-envelope pa-body
                                (get-in resolved [:submitted-bundle :root])))]
    (cond
      (nil? authority-basis)
      (result :prf.resubmission.acceptance/results-binding-v1
              :results :unavailable)
      (nil? pa-body)
      (result :prf.resubmission.acceptance/results-binding-v1
              :results :unavailable)
      (or (nil? results) (nil? publisher-policy))
      (result :prf.resubmission.acceptance/results-binding-v1
              :results :unavailable)
      (and publisher-validation (not (:valid? publisher-validation)))
      (result :prf.resubmission.acceptance/results-binding-v1
              :results :semantically-invalid
              :observed (:reason publisher-validation))
      :else
      (let [results-verifier (:results/verifier-id results)
            pa-entries (get pa-body :entries)
            authorized-keys (set (keep :publisher-authority/public-key pa-entries))
            certificate-root (hash-ref/sha256-ref
                              (hc/domain-hash :evidence-record
                                              (get-in resolved [:submission-basis :body :certificate])))
            execution-root (hash-ref/sha256-ref
                            (hc/domain-hash :evidence-record
                                            (get-in resolved [:submission-basis :body :execution-evidence])))]
        (cond
          (not (map? pa-body))
          (result :prf.resubmission.acceptance/results-binding-v1
                  :results :malformed)
          (nil? results-verifier)
          (result :prf.resubmission.acceptance/results-binding-v1
                  :results :semantically-invalid
                  :missing [:results/verifier-id])
          (not= certificate-root (:results/certificate-root results))
          (result :prf.resubmission.acceptance/results-binding-v1
                  :results :root-mismatch
                  :expected certificate-root
                  :observed (:results/certificate-root results))
          (not= execution-root (:results/execution-evidence-root results))
          (result :prf.resubmission.acceptance/results-binding-v1
                  :results :root-mismatch
                  :expected execution-root
                  :observed (:results/execution-evidence-root results))
          :else
          (let [pub-key (:publisher-policy-key publisher-policy)]
            (if (and (some? pub-key) (contains? authorized-keys pub-key))
              (result :prf.resubmission.acceptance/results-binding-v1
                      :results :verified)
              (result :prf.resubmission.acceptance/results-binding-v1
                      :results :root-mismatch
                      :expected authorized-keys
                      :observed pub-key))))))))

(def check-dispatch
  {:prf.resubmission.acceptance/submitted-bundle-integrity-v1 check-submitted-bundle
   :prf.resubmission.acceptance/submission-basis-integrity-v1 check-submission-basis
   :prf.resubmission.acceptance/submission-components-present-v1 check-submission-components
   :prf.resubmission.acceptance/submission-registry-integrity-v1 check-submission-registry
   :prf.resubmission.acceptance/authority-registries-verifiable-v1 check-authority-registries
   :prf.resubmission.acceptance/verifier-registry-selection-v1 check-verifier-registry-selection
   :prf.resubmission.acceptance/results-binding-v1 check-results-binding})

(defn finding-id [check-result]
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-acceptance-finding-v1
                   (select-keys check-result [:check/id :check/subject :check/status
                                              :expected :observed :missing
                                              :verifier-registry-status :publisher-authority-status
                                              :submission-registry-status]))))

(defn findings-for [check-results]
  (->> check-results
       (remove #(= :verified (:check/status %)))
       (mapv #(assoc (select-keys % [:check/id :check/subject :check/status
                                     :expected :observed :missing
                                     :verifier-registry-status :publisher-authority-status
                                     :submission-registry-status])
                     :finding/id (finding-id %)))))

(defn outcome-for [check-results]
  (if (every? #(= :verified (:check/status %)) check-results)
    :accepted
    :rejected))

(defn resolve-evaluation-basis
  "Resolve only configuration-authorized references. `resolver` is
   {:resolve-artifact (fn [root] body-or-nil)}; it is retrieval plumbing, not a
   source of acceptance semantics.

   For V3 configurations (which carry an authority basis), also resolves the
   three individual registries named by the authority basis:
   - verifier-registry
   - publisher-authority
   - submission-registry"
  [resolver configuration submitted-bundle-root]
  (let [definition-root (genesis/authorized-attempt-acceptance-definition-root configuration)
        authority-basis-root (genesis/authorized-attempt-acceptance-authority-basis-root configuration)
        resolve-artifact (:resolve-artifact resolver)
        definition (when (and definition-root resolve-artifact)
                     (resolve-artifact definition-root))
        authority-basis (when (and authority-basis-root resolve-artifact)
                          (resolve-artifact authority-basis-root))
        submitted-bundle (when resolve-artifact (resolve-artifact submitted-bundle-root))
        publisher-envelope-root (:publisher-envelope-hash submitted-bundle)
        publisher-envelope (when (and resolve-artifact publisher-envelope-root)
                             (resolve-artifact publisher-envelope-root))
        publisher-statement-root (:publisher-envelope/statement-root publisher-envelope)
        publisher-statement (when (and resolve-artifact publisher-statement-root)
                              (resolve-artifact publisher-statement-root))
        submission-registry-root (:registry-root submitted-bundle)
        submission-registry (when (and resolve-artifact submission-registry-root)
                              (resolve-artifact submission-registry-root))
        submission-basis-root (:submission-basis-root submitted-bundle)
        submission-basis (when (and resolve-artifact submission-basis-root)
                           (resolve-artifact submission-basis-root))
        authority-registries (when (and authority-basis resolve-artifact)
                               (authority-basis/resolve-registries resolver authority-basis))]
    {:configuration {:root (genesis/resubmission-chain-configuration-root configuration)
                     :body configuration}
     :attempt-acceptance-definition {:root definition-root :body definition}
     :attempt-acceptance-authority-basis {:root authority-basis-root :body authority-basis}
     :attempt-acceptance-authority-registries authority-registries
     :submitted-bundle {:root submitted-bundle-root :body submitted-bundle}
     :publisher-envelope {:root publisher-envelope-root :body publisher-envelope}
     :publisher-statement {:root publisher-statement-root :body publisher-statement}
     :certificate-subject
     (certificate-subject/build
      (hash-ref/sha256-ref (hc/domain-hash :evidence-record (:certificate submission-basis)))
      (get-in submission-basis [:results-artifact :results/certificate-root])
      submitted-bundle-root
      :prf.resubmission/certificate-v1)
     :execution-evidence-subject
     (execution-subject/build
      (hash-ref/sha256-ref (hc/domain-hash :evidence-record (:execution-evidence submission-basis)))
      (get-in submission-basis [:results-artifact :results/execution-evidence-root])
      submitted-bundle-root
      :prf.resubmission/execution-evidence-v1
      :prf.resubmission/execution-evidence-v1)
     :submission-registry {:root submission-registry-root :body submission-registry}
     :submission-basis {:root submission-basis-root :body submission-basis}}))

(defn evaluate
  "Pure deterministic evaluation. A definition not matching the configuration
   authorization, an invalid definition, or an unknown selected check fails
   closed by throwing rather than accepting caller-selected semantics.

   For V3 configurations (with an authority basis), additionally validates:
   - registry roots are valid sha256 references (registry verification)
   - the results verifier is authorized (verifier-registry selection)
   - results are bound to the publisher-authority (results binding)"
  [resolved definition]
  (when-not (valid-definition? definition)
    (throw (ex-info "invalid acceptance definition" {:reason :invalid-acceptance-definition})))
  (when-not (= (genesis/authorized-attempt-acceptance-definition-root
                (get-in resolved [:configuration :body]))
               (:attempt-acceptance-definition/root definition))
    (throw (ex-info "acceptance definition is not configuration-authorized"
                    {:reason :unauthorized-acceptance-definition})))
  (let [authority-basis (get-in resolved [:attempt-acceptance-authority-basis :body])
        authority-basis-root (get-in resolved [:attempt-acceptance-authority-basis :root])
        v3-config? (some? authority-basis-root)]
    (when v3-config?
      (when-not (authority-basis/valid? authority-basis)
        (throw (ex-info "invalid acceptance authority basis"
                        {:reason :invalid-acceptance-authority-basis})))
      (when-not (= (genesis/authorized-attempt-acceptance-authority-basis-root
                    (get-in resolved [:configuration :body]))
                   authority-basis-root)
        (throw (ex-info "acceptance authority basis is not configuration-authorized"
                        {:reason :unauthorized-acceptance-authority-basis}))))
    (when-not (= (:root (:attempt-acceptance-definition resolved))
                 (:attempt-acceptance-definition/root definition))
      (throw (ex-info "resolved acceptance definition root mismatch"
                      {:reason :acceptance-definition-root-mismatch})))
    (let [definition-check-ids (keep :check/id (:definition/checks definition))
          check-results
          (mapv (fn [check-id]
                  (let [check (get check-dispatch check-id)]
                    (when-not check
                      (throw (ex-info "unknown acceptance check" {:reason :unknown-check-id :check/id check-id})))
                    (check resolved)))
                definition-check-ids)
          findings (findings-for check-results)]
      {:artifact/schema evaluation-schema
       :evaluation/basis {:configuration/root (get-in resolved [:configuration :root])
                          :attempt-acceptance-definition/root (get-in resolved [:attempt-acceptance-definition :root])
                          :attempt-acceptance-authority-basis/root authority-basis-root
                          :submitted-bundle/root (get-in resolved [:submitted-bundle :root])
                          :submission-basis/root (get-in resolved [:submission-basis :root])}
       :evaluation/check-results check-results
       :evaluation/findings findings
       :evaluation/outcome (outcome-for check-results)})))

(def evaluation-basis-fields
  "Ordered identity fields of the acceptance-evaluation.v1 evaluation basis."
  [:configuration/root
   :attempt-acceptance-definition/root
   :attempt-acceptance-authority-basis/root
   :submitted-bundle/root
   :submission-basis/root])

(defn evaluation-projection [evaluation]
  (let [basis (:evaluation/basis evaluation)]
    {:artifact/schema (:artifact/schema evaluation)
     :evaluation/basis (hc/project-canonical-safe (select-keys basis evaluation-basis-fields))
     :evaluation/check-results (:evaluation/check-results evaluation)
     :evaluation/findings (:evaluation/findings evaluation)
     :evaluation/outcome (:evaluation/outcome evaluation)}))

(defn evaluation-root [evaluation]
  (hash-ref/sha256-ref (hc/domain-hash evaluation-domain (evaluation-projection evaluation))))

(defn build-evaluation [resolver configuration submitted-bundle-root]
  (let [resolved (resolve-evaluation-basis resolver configuration submitted-bundle-root)
        definition (get-in resolved [:attempt-acceptance-definition :body])
        evaluation (evaluate resolved definition)]
    (assoc evaluation :acceptance-evaluation/root (evaluation-root evaluation))))

(defn validate-acceptance-evaluation
  "Historically validate an evaluation using the configuration root retained in
   its basis. The resolver must provide :resolve-configuration; current chain
   state is deliberately not consulted."
  [resolver evaluation]
  (try
    (let [configuration-root (get-in evaluation [:evaluation/basis :configuration/root])
          configuration ((:resolve-configuration resolver) configuration-root)
          expected (build-evaluation resolver configuration
                                     (get-in evaluation [:evaluation/basis :submitted-bundle/root]))]
      {:valid? (= expected evaluation)
       :reason (if (= expected evaluation) :ok :evaluation-mismatch)})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e))})))

(defn validate-evaluation-current-for-admission
  "P0 admission fence: historical validity is separate from equality with the
   configuration root selected by the current admission authority context."
  [authority-context evaluation]
  (let [current-root (:authority/configuration-root authority-context)
        evaluation-root (get-in evaluation [:evaluation/basis :configuration/root])]
    {:valid? (= current-root evaluation-root)
     :reason (if (= current-root evaluation-root)
               :ok
               :evaluation-configuration-not-current)}))
