(ns resolver-sim.benchmark.governed-authority-derived-finalization
  "AUTH-STATE-AFTER-FINALIZATION — versioned K2 → derived-state bridge.

   A new additive authoritative finalization entry point that does NOT mutate
   the frozen K2 finalizers. It derives the exact successor from canonical
   transition semantics (T is a protocol constant, never an input) and commits
   a governed-authority-derived-result-receipt.v1 that explicitly claims the
   successor was mechanically derived from exact K2-authorised O.

   The caller supplies only semantic identities (fence/root,
   expected-predecessor/root); O, T, and every successor body are resolved or
   derived internally. Report bodies are retained at fence issuance
   (AUTH-DERIVED-EVIDENCE-CLOSURE), so the normal authoritative path needs zero
   caller-supplied bodies."
  (:require [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-transition :as gt]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.configuration-head :as config-head]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def derived-receipt-schema "governed-authority-derived-result-receipt.v1")

;; ── Derived result receipt (new artifact identity, not a marker on the old) ──

(def derived-receipt-fields
  "The derived receipt commits the reproducible derivation identity, notably
   :governed-authority-content-transition/root — the canonical bridge joining
   authorization and state-after. It never uses the old receipt schema."
  #{:artifact/schema
    :pre-authoritative-state-envelope/root   ; S0
    :post-authoritative-state-envelope/root  ; S1
    :authority-report/root                   ; R
    :authoritative-target/root               ; O
    :transition-definition/root              ; T
    :governed-authority-content-transition/root ; CT
    :successor-material/root                 ; M1 (execution/state-root)
    :configuration-head/root                 ; H1
    :publication/sequence
    :publication/predecessor-root})

(defn derived-receipt
  "Build the closed derived result receipt. `derived` is the output of
   gt/derive-governed-successor; `CT` is the content-transition root;
   `S0` is the predecessor envelope; `report-root` is the K2 authority report."
  [S0 derived CT report-root]
  (let [body {:artifact/schema derived-receipt-schema
              :pre-authoritative-state-envelope/root (:authoritative-state-envelope/root S0)
              :post-authoritative-state-envelope/root (:successor/root derived)
              :authority-report/root report-root
              :authoritative-target/root (:authorised-target/root CT)
              :transition-definition/root (:transition-definition/root CT)
              :governed-authority-content-transition/root CT
              :successor-material/root (:state-after/root derived)
              :configuration-head/root (:configuration-head/root CT)
              :publication/sequence (:publication/sequence (:successor-envelope derived))
              :publication/predecessor-root (:authoritative-state-envelope/root S0)}
        candidate (assoc body :governed-authority-derived-result-receipt/root
                         (ref/sha256-ref
                          (hc/domain-hash :governed-authority-derived-result-receipt-v1
                                          (hc/project-canonical-safe body))))]
    candidate))

(defn derived-receipt? [r]
  (and (map? r) (= derived-receipt-schema (:artifact/schema r))))

;; ── O recovery from the retained report body ─────────────────────────────────

(defn- resolve-report
  "Recover the exact report body from the store by its root
   (AUTH-DERIVED-EVIDENCE-CLOSURE): recompute the report root and require it to
   equal the committed root. Caller-delivered bodies are NOT used on this path."
  [snapshot report-root]
  (let [report (get-in snapshot [:authority-reports report-root])]
    (when-not (and report
                   (= report-root (authority/authority-report-root report)))
      (throw (ex-info "authority report body unavailable or unverified"
                      {:reason :authority-report/body-unavailable})))
    report))

(defn- material-valid?
  "Committed G/KS/RR material roots recompute from the retained bodies."
  [material]
  (let [gov (:authority-material/review-governance material)
        ks  (:authority-material/signer-key-set material)
        round (:authority-material/review-round material)]
    (and (= (:review-governance/root material) (governance/governance-root gov))
         (= (:signer-key-set/root material) (state/signer-key-set-root ks))
         (= (:review-round/root material) (state/review-round-material-root round)))))

(defn- resolve-predecessor-bundle
  "Root-driven predecessor resolution from authoritative state. Never trusts
   caller-supplied bodies."
  [snapshot]
  (let [head (:head snapshot)
        envelope (get-in snapshot [:envelopes head])
        state-root (:execution/state-root envelope)
        material (get-in snapshot [:material state-root])
        config-head-root (:configuration-head/root envelope)
        head-state (get-in snapshot [:configuration-head-states config-head-root])
        config-root (:chain-configuration/root envelope)
        config-body (get-in snapshot [:chain-configurations config-root])]
    (when-not (and envelope material head-state config-body)
      (throw (ex-info "predecessor bundle incomplete"
                      {:reason :authority/predecessor-unavailable})))
    (when-not (= config-head-root (config-head/head-state-root head-state))
      (throw (ex-info "config-head root mismatch"
                      {:reason :authority/predecessor-roots-mismatch})))
    (when-not (= config-root (genesis/chain-configuration-root config-body))
      (throw (ex-info "config root mismatch"
                      {:reason :authority/predecessor-roots-mismatch})))
    (when-not (material-valid? material)
      (throw (ex-info "predecessor material roots invalid"
                      {:reason :authority/material-invalid})))
    {:envelope envelope :material material :configuration/head head-state
     :config {:verifier-registry/root (:verifier-registry/root config-body)}
     :config-body config-body}))

;; ── Derived finalization (atomic, CAS) ───────────────────────────────────────

(defn- resolve-availability-config
  "Resolve the exact new chain-configuration body per
   AUTH-DERIVED-EVIDENCE-CLOSURE: prefer a retained body within authoritative
   state; else accept an availability transport body verified against the
   authoritative committed root O; else fail closed."
  [snapshot O availability]
  (or (get-in snapshot [:chain-configurations (:proposed-content-root O)])
      (let [body (:chain-configuration/body availability)]
        (when (and body
                   (= (:proposed-content-root O)
                      (genesis/chain-configuration-root body)))
          body))))

(defn finalise-under-authority-fence-derived!
  "Authoritative derived finalization. The caller supplies only the fence id and
   the expected predecessor envelope root; O, T, and every successor value are
   resolved or derived internally. An optional `availability` transport may carry
   root-verified bodies (e.g. a not-yet-retained proposed chain-configuration);
   it is never a semantic argument. The successor must equal
   derive-governed-successor(T,S0,O); a caller-selected successor is impossible
   here because no successor value is an input. On CAS failure the derived
   successor is discarded and re-derived from the new state."
  ([store fence-id expected-predecessor-root]
   (finalise-under-authority-fence-derived! store fence-id expected-predecessor-root nil))
  ([store fence-id expected-predecessor-root availability]
   (loop []
     (let [current @(.state store)
           record (get-in current [:issued-fences fence-id])]
       (cond
         (nil? record) {:finalised? false :reason :unknown-fence}
         (= :consumed (:status record)) (:result record)
         (not= :authorised (:authority-status record)) {:finalised? false :reason :authority-report-not-authorised}
         (not= (:head current) expected-predecessor-root) {:finalised? false :reason :state-not-at-required-head}
         (not= expected-predecessor-root (:authority-state-envelope/root record)) {:finalised? false :reason :fence-predecessor-mismatch}
         :else
         (let [built (try
                       (let [report (resolve-report current (:authority-report/root record))
                             O {:proposed-content-root (:authoritative-target-root report)
                                :new-config-body (resolve-availability-config current
                                                                              {:proposed-content-root (:authoritative-target-root report)}
                                                                              availability)}
                             S0-bundle (resolve-predecessor-bundle current)
                            ;; T is the canonical protocol constant — never an input.
                             derived (gt/derive-governed-successor gt/transition-definition-root S0-bundle O)
                             _ (when-not (and (ref/valid-sha256-ref? (:successor/root derived))
                                              (ref/valid-sha256-ref? (:state-after/root derived)))
                                 (throw (ex-info "derived successor invalid" {:reason :state-after/derivation-invalid})))
                             CT (gt/content-transition derived S0-bundle O)
                             binding (gt/build-authoritative-transition-binding
                                      (:execution/state-root (:envelope S0-bundle)) derived
                                      (:resolved-review-authority-context/root record)
                                      (:proposed-content-root O))
                             receipt (derived-receipt (:envelope S0-bundle) derived (:governed-authority-content-transition/root CT)
                                                      (:authority-report/root record))
                             envelope (:successor-envelope derived)
                             material (:successor-material derived)
                             root (:successor/root derived)
                             receipt-root (:governed-authority-derived-result-receipt/root receipt)
                             next (-> current
                                      (assoc :head root)
                                      (assoc-in [:envelopes root] envelope)
                                      (assoc-in [:material (:execution/state-root envelope)] material)
                                      (assoc-in [:configuration-head-states (:configuration-head/root envelope)]
                                                (:successor-config-head derived))
                                      (assoc-in [:chain-configurations (:chain-configuration/root envelope)]
                                                (:new-config-body O))
                                      (assoc-in [:authority-bindings root] binding)
                                      (assoc-in [:governed-authority-derived-result-receipts receipt-root] receipt)
                                      (assoc-in [:governed-authority-derived-result-receipt-by-binding
                                                 (:governed-authority-transition-binding/root binding)] receipt-root)
                                      (assoc-in [:issued-fences fence-id]
                                                (assoc record :status :consumed
                                                       :transition-binding/root (:governed-authority-transition-binding/root binding)
                                                       :successor-envelope/root root
                                                       :governed-authority-derived-result-receipt/root receipt-root
                                                       :result {:finalised? true :envelope envelope
                                                                :authority-binding binding
                                                                :governed-authority-derived-result-receipt receipt})))]
                         {:ok true :next next :envelope envelope :binding binding :receipt receipt})
                       (catch clojure.lang.ExceptionInfo e
                         {:ok false :reason (get-in (ex-data e) [:reason] :derivation-failed)}))]
           (if (:ok built)
             (if (compare-and-set! (.state store) current (:next built))
               {:finalised? true :envelope (:envelope built)
                :authority-binding (:binding built)
                :governed-authority-derived-result-receipt (:receipt built)}
               (recur))
             {:finalised? false :reason (:reason built)})))))))

;; ── High-assurance admission (structural, by artifact kind) ──────────────────

(defn high-assurance-derived-finalization?
  "A finalization result is high-assurance iff it carries a
   governed-authority-derived-result-receipt.v1 — the legacy receipt kind is
   rejected by artifact identity, not by convention (LEGACY-NON-UPGRADE)."
  [result]
  (and (map? result)
       (derived-receipt? (:governed-authority-derived-result-receipt result))))