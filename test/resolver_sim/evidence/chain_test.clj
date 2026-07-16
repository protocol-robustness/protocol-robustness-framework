(ns resolver-sim.evidence.chain-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.util.evidence :as ev]))

(def ^:private sample-attribution
  {:ctx/run-id "test-run-1"
   :ctx/scenario-id "test-scenario"
   :ctx/step 1
   :ctx/event-id "evt-001"})

(defn- make-sample-evidence [n]
  (ev/make-evidence-record
   {:artifact-kind :transition
    :step 1
    :block-time 1000
    :before {:counter (dec n)}
    :after {:counter n}
    :action {:type :increment :n n}
    :result {:ok true}
    :attribution sample-attribution}))

;; ── Registry lifecycle ────────────────────────────────────────────────────

(deftest reset-registry-clears-state
  (chain/reset-registry! :run-id "test")
  (let [status (chain/registry-status)]
    (is (zero? (:evidence-count status)))
    (is (= "test" (:run-id status)))))

(deftest register-evidence-adds-to-registry
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        eh (chain/register-evidence! ev1)]
    (is (string? eh))
    (is (= (:evidence-hash ev1) eh))
    (is (= 1 (:evidence-count (chain/registry-status))))))

(deftest register-evidence-idempotent
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev1)
    (is (= 1 (:evidence-count (chain/registry-status))))))

(deftest register-evidence-concurrent-idempotent
  (chain/reset-registry!)
  (let [evidence (make-sample-evidence 1)
        futures (repeatedly 10 #(future (chain/register-evidence! evidence)))]
    (doseq [f futures] @f)
    (is (= 1 (:evidence-count (chain/registry-status)))
        "Concurrent duplicate calls should not create duplicates")))

(deftest register-multiple-evidences
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        ev2 (make-sample-evidence 2)
        ev3 (make-sample-evidence 3)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev2)
    (chain/register-evidence! ev3)
    (is (= 3 (:evidence-count (chain/registry-status))))))

;; ── Build registry ────────────────────────────────────────────────────────

(deftest build-registry-produces-self-hashed-map
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry :run-id "test-run")]
    (is (= "evidence-registry.v1" (:schema-version registry)))
    (is (= "test-run" (:run-id registry)))
    (is (string? (:generated-at registry)))
    (is (= 2 (:evidence-count registry)))
    (is (= 2 (count (:evidence-hashes registry))))
    (is (= 2 (count (:artifacts registry))))
    (is (string? (:registry-hash registry)))))

(deftest build-registry-registry-hash-commits-to-content
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [reg1 (chain/build-registry :run-id "a")]
    (chain/register-evidence! (make-sample-evidence 2))
    (let [reg2 (chain/build-registry :run-id "a")]
      (is (not= (:registry-hash reg1) (:registry-hash reg2))
          "Adding evidence changes registry hash"))))

(deftest build-registry-run-id-changes-hash
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [reg-a (chain/build-registry :run-id "run-a")
        reg-b (chain/build-registry :run-id "run-b")]
    (is (not= (:registry-hash reg-a) (:registry-hash reg-b)))))

;; ── Artifact entries ──────────────────────────────────────────────────────

(deftest artifact-entries-contain-evidence-hash
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (let [registry (chain/build-registry)
          entry (first (:artifacts registry))]
      (is (= (:evidence-hash ev) (:evidence-hash entry)))
      (is (= (:context-hash ev) (:context-hash entry)))
      (is (= (:before-hash ev) (:before-hash entry)))
      (is (= (:after-hash ev) (:after-hash entry)))
      (is (= (:action-hash ev) (:action-hash entry)))
      (is (= (:result-hash ev) (:result-hash entry)))
      (is (= (name (:artifact-kind ev)) (:artifact-kind entry))))))

(deftest artifact-entries-have-consistent-ids
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        ids (map :id (:artifacts registry))]
    (is (= 2 (count ids)))
    (is (apply distinct? ids))
    (is (every? #(re-matches #"evidence-[a-f0-9]+" %) ids))))

;; ── Verification ──────────────────────────────────────────────────────────

(deftest verify-registry-hash-valid
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        result (chain/verify-registry-hash registry)]
    (is (:valid result))))

(deftest verify-registry-hash-invalid-when-tampered
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [registry (chain/build-registry)
        tampered (assoc registry :evidence-count 999)
        result (chain/verify-registry-hash tampered)]
    (is (not (:valid result)))))

(deftest verify-evidence-in-registry-present
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (let [registry (chain/build-registry)
          result (chain/verify-evidence-in-registry registry (:evidence-hash ev))]
      (is (:present result))
      (is (= (:evidence-hash ev) (get-in result [:entry :evidence-hash]))))))

(deftest verify-evidence-in-registry-absent
  (chain/reset-registry!)
  (let [registry (chain/build-registry)
        result (chain/verify-evidence-in-registry registry "nonexistent-hash")]
    (is (not (:present result)))))

(deftest evidence-chain-integrity-intact
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        result (chain/evidence-chain-integrity registry)]
    (is (:registry-hash-valid result))
    (is (:all-hashes-registered result))
    (is (:chain-intact result))
    (is (= 2 (:artifact-count result)))))

;; ── Serialization ─────────────────────────────────────────────────────────

(deftest registry-round-trip-json
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [registry (chain/build-registry)
        json-str (chain/registry->json registry)
        re-read (json/read-str json-str)]
    (is (string? json-str))
    (is (= (:schema-version registry) (get re-read "schema-version")))
    (is (= (:run-id registry) (get re-read "run-id")))
    (is (= (:registry-hash registry) (get re-read "registry-hash")))
    (is (= 1 (count (get re-read "artifacts"))))))

;; ── Integration with dispatcher ───────────────────────────────────────────

(deftest dispatcher-registers-evidence-automatically
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (is (= 1 (:evidence-count (chain/registry-status))))
    (let [registry (chain/build-registry)
          entry (first (:artifacts registry))]
      (is (= (:evidence-hash ev) (:evidence-hash entry))))))

;; ── Phase 3: Artifact Registry Integration ───────────────────────────────

(deftest compute-file-sha256-returns-hex
  (chain/reset-registry!)
  (let [registry (chain/build-registry)
        path (chain/write-registry! registry)
        sha (chain/compute-file-sha256 path)]
    (is (string? sha))
    (is (= 64 (count sha)))
    (is (re-matches #"[a-f0-9]+" sha))))

(deftest finalize-and-write-produces-registry-and-entry
  (chain/reset-registry! :run-id "phase3-test")
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [{:keys [registry artifact-entry path]} (chain/finalize-and-write! :run-id "phase3-test")]
    (is (map? registry))
    (is (= "evidence-registry.v1" (:schema-version registry)))
    (is (string? (:registry-hash registry)))
    (is (= 2 (:evidence-count registry)))
    (is (string? path))
    (is (.exists (java.io.File. path)))
    ;; artifact entry for test-artifacts.json
    (is (map? artifact-entry))
    (is (= "evidence-registry" (:id artifact-entry)))
    (is (= "evidence-registry" (:kind artifact-entry)))
    (is (string? (:sha256 artifact-entry)))
    (is (string? (:path artifact-entry)))
    (is (= (:registry-hash registry) (:registry-hash artifact-entry)))
    (is (= (:evidence-count registry) (:evidence-count artifact-entry)))))

(deftest finalize-and-write-file-is-valid-json
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [{:keys [path registry]} (chain/finalize-and-write! :run-id "json-test")
        re-read (json/read-str (slurp path))]
    (is (= (:schema-version registry) (get re-read "schema-version")))
    (is (= (:registry-hash registry) (get re-read "registry-hash")))
    (is (= 1 (get re-read "evidence-count")))
    (is (= 1 (count (get re-read "artifacts"))))))

(deftest registry-artifact-entry-nil-when-no-file
  (chain/reset-registry!)
  (let [path (str (evcfg/artifact-dir) "/" chain/evidence-registry-filename)
        f (java.io.File. path)]
    (when (.exists f) (.delete f)))
  (let [registry (chain/build-registry)
        entry (chain/registry-artifact-entry registry)]
    (is (nil? entry) "No artifact entry when evidence-registry.json doesn't exist")))

(deftest registry-artifact-entry-produces-entry-when-file-exists
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [{:keys [registry]} (chain/finalize-and-write!)
        entry (chain/registry-artifact-entry registry)]
    (is (map? entry))
    (is (string? (:sha256 entry)))
    (is (= "evidence-registry" (:id entry)))))

(deftest full-chain-from-evidence-to-artifact-entry
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        ev2 (make-sample-evidence 2)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev2)
    (let [{:keys [registry artifact-entry]} (chain/finalize-and-write! :run-id "full-chain")
          integrity (chain/evidence-chain-integrity registry)]
      ;; Evidence chain is intact
      (is (:chain-intact integrity))
      ;; Registry hash is included in the artifact entry
      (is (= (:registry-hash registry) (:registry-hash artifact-entry)))
      ;; Evidence hashes are registered
      (is (some #(= (:evidence-hash ev1) %) (:evidence-hashes registry)))
      (is (some #(= (:evidence-hash ev2) %) (:evidence-hashes registry)))
      ;; Artifact entry sha256 matches file hash
      (let [file-sha (chain/compute-file-sha256 (:path artifact-entry))]
        (is (= file-sha (:sha256 artifact-entry)))))))

;; ── Source Provenance in Cursor ───────────────────────────────────────────

(def ^:private mock-snapshot
  {:cursor/scope :targeted-evidence
   :cursor/final-seq 5
   :cursor/final-self-hash "abcd1234efgh5678"
   :cursor/total-captured 5})

(def ^:private mock-source
  {:git-commit-sha "deadbeef"
   :source/hash "cafebabe"
   :source/hash-algorithm "source-tree-hash.v1.path-content-sha256"
   :source/hash-roots ["src" "protocols_src" "benchmarks"]
   :code-hash "cafebabe"
   :deps-hash "d1e2f3"
   :input-hash "a1b2c3"
   :dirty? false})

(deftest enrich-cursor-data-includes-source-when-provided
  (let [result (chain/enrich-cursor-data mock-snapshot mock-source)]
    (is (contains? result :cursor/source))
    (is (= "deadbeef" (get-in result [:cursor/source :git-commit-sha])))
    (is (= "cafebabe" (get-in result [:cursor/source :source/hash])))
    (is (= "source-tree-hash.v1.path-content-sha256"
           (get-in result [:cursor/source :source/hash-algorithm])))
    (is (= ["src" "protocols_src" "benchmarks"]
           (get-in result [:cursor/source :source/hash-roots])))
    (is (= "cafebabe" (get-in result [:cursor/source :code-hash])))
    (is (= "d1e2f3" (get-in result [:cursor/source :deps-hash])))
    (is (= "a1b2c3" (get-in result [:cursor/source :input-hash])))
    (is (false? (get-in result [:cursor/source :dirty?])))))

(deftest enrich-cursor-data-includes-all-dimension-keys
  (let [result (chain/enrich-cursor-data mock-snapshot mock-source)
        src (:cursor/source result)]
    (is (contains? src :git-commit-sha))
    (is (contains? src :source/hash))
    (is (contains? src :source/hash-algorithm))
    (is (contains? src :source/hash-roots))
    (is (contains? src :code-hash))
    (is (contains? src :deps-hash))
    (is (contains? src :input-hash))
    (is (contains? src :dirty?))))

(deftest enrich-cursor-data-omits-source-when-nil
  (let [result (chain/enrich-cursor-data mock-snapshot nil)]
    (is (not (contains? result :cursor/source)))))

(deftest enrich-cursor-data-preserves-snapshot-fields
  (let [result (chain/enrich-cursor-data mock-snapshot mock-source)]
    (is (= :targeted-evidence (:cursor/scope result)))
    (is (= 5 (:cursor/final-seq result)))
    (is (= "abcd1234efgh5678" (:cursor/final-self-hash result)))
    (is (= 5 (:cursor/total-captured result)))))

(deftest cursor-content-hash-changes-when-source-added
  (let [no-source (chain/enrich-cursor-data mock-snapshot nil)
        with-source (chain/enrich-cursor-data mock-snapshot mock-source)
        h1 (hc/hash-with-intent {:hash/intent :evidence-chain} no-source)
        h2 (hc/hash-with-intent {:hash/intent :evidence-chain} with-source)]
    (is (not= h1 h2) "Adding source provenance changes cursor content hash")))

(deftest cursor-content-hash-changes-when-source-content-changes
  (let [src-a (chain/enrich-cursor-data mock-snapshot
                                        (assoc mock-source :git-commit-sha "aaa" :code-hash "bbb"))
        src-b (chain/enrich-cursor-data mock-snapshot
                                        (assoc mock-source :git-commit-sha "ccc" :code-hash "ddd"))
        h1 (hc/hash-with-intent {:hash/intent :evidence-chain} src-a)
        h2 (hc/hash-with-intent {:hash/intent :evidence-chain} src-b)]
    (is (not= h1 h2) "Different source values produce different cursor hashes")))

(deftest cursor-content-hash-changes-when-dirty-flag-changes
  (let [clean (chain/enrich-cursor-data mock-snapshot (assoc mock-source :dirty? false))
        dirty (chain/enrich-cursor-data mock-snapshot (assoc mock-source :dirty? true))
        h1 (hc/hash-with-intent {:hash/intent :evidence-chain} clean)
        h2 (hc/hash-with-intent {:hash/intent :evidence-chain} dirty)]
    (is (not= h1 h2) "dirty? flag changes cursor content hash")))

(deftest cursor-content-hash-changes-when-diff-hash-added
  (let [no-diff (chain/enrich-cursor-data mock-snapshot (assoc mock-source :dirty? true))
        with-diff (chain/enrich-cursor-data mock-snapshot
                                            (assoc mock-source :dirty? true) "diffhash123")
        h1 (hc/hash-with-intent {:hash/intent :evidence-chain} no-diff)
        h2 (hc/hash-with-intent {:hash/intent :evidence-chain} with-diff)]
    (is (not= h1 h2) "dirty-diff-hash changes cursor content hash")
    (is (= "diffhash123" (get-in with-diff [:cursor/source :dirty-diff-hash])))))

(deftest final-evidence-self-hash-independent-of-cursor-source
  (chain/reset-chain-cursor!)
  (chain/reset-registry!)
  (let [evidence-content {:action {:type :increment :n 1}
                          :after {:counter 1}
                          :before {:counter 0}
                          :result {:ok true}
                          :step 1
                          :block-time 1000}
        raw-ev (ev/make-evidence-record
                {:artifact-kind :transition
                 :step 1
                 :block-time 1000
                 :before {:counter 0}
                 :after {:counter 1}
                 :action {:type :increment :n 1}
                 :result {:ok true}
                 :attribution {:ctx/run-id "source-test"
                               :ctx/scenario-id "sc"
                               :ctx/step 1
                               :ctx/event-id "evt-001"}})
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-content} evidence-content)
        ev (-> raw-ev
               (assoc :evidence/hash evidence-hash)
               (chain/inject-chain-fields))]
    (chain/register-evidence! ev)
    (let [snap (chain/cursor-snapshot)]
      (is (some? snap))
      (is (= (:cursor/final-self-hash snap) evidence-hash)
          "final-self-hash is the hash of the chained evidence"))
    (is (= 64 (count evidence-hash)) "SHA-256 hex is 64 chars")
    (let [recomputed (hc/hash-with-intent {:hash/intent :evidence-content} evidence-content)]
      (is (= evidence-hash recomputed) "Evidence hash deterministic from content, independent of cursor"))))

(deftest source-provenance-dirty-flag-explicit
  (let [result (chain/enrich-cursor-data mock-snapshot (assoc mock-source :dirty? true))]
    (is (true? (get-in result [:cursor/source :dirty?]))))
  (let [result (chain/enrich-cursor-data mock-snapshot (assoc mock-source :dirty? false))]
    (is (false? (get-in result [:cursor/source :dirty?])))))

(deftest source-provenance-absent-represents-unknown
  (let [result (chain/enrich-cursor-data mock-snapshot nil)]
    (is (not (contains? result :cursor/source))
        "Nil source-provenance (no VCS root) means no :cursor/source key — unknown source")))

(deftest cursor-file-envelope-contains-dimension-mirrors
  (chain/reset-registry!)
  (chain/reset-chain-cursor!)
  (let [ev (-> (ev/make-evidence-record
                {:artifact-kind :transition
                 :step 1 :block-time 1000
                 :before {:counter 0} :after {:counter 1}
                 :action {:type :increment :n 1} :result {:ok true}
                 :attribution {:ctx/run-id "mirror-test"
                               :ctx/scenario-id "sc" :ctx/step 1
                               :ctx/event-id "evt-001"}})
               (assoc :evidence/hash "test-hash-mirror-001")
               (chain/inject-chain-fields))]
    (chain/register-evidence! ev)
    (let [path (chain/write-chain-cursor-final! :dir (str (evcfg/artifact-dir))
                                                :run-id "test-mirror-run"
                                                :allow-dirty? true)
          content (when path (json/read-str (slurp path)))]
      (when (and content (string? (get content "code-hash")))
        (is (string? (get content "source/hash")))
        (is (string? (get content "source/hash-algorithm")))
        (is (vector? (get content "source/hash-roots")))
        (is (string? (get content "code-hash")))
        (is (string? (get content "deps-hash")))
        (is (string? (get content "input-hash"))))))
  (chain/reset-registry!))

;; ── Dirty Policy — tested through pure enrich-cursor-data ─────────────────

(deftest enrich-cursor-data-accepts-dirty-diff-hash
  (let [source (assoc mock-source :dirty? true)
        result (chain/enrich-cursor-data mock-snapshot source "abc123diff")]
    (is (= "abc123diff" (get-in result [:cursor/source :dirty-diff-hash])))
    (is (true? (get-in result [:cursor/source :dirty?])))
    (is (= "deadbeef" (get-in result [:cursor/source :git-commit-sha])))))

(deftest enrich-cursor-data-omits-dirty-diff-hash-when-not-override
  (let [source (assoc mock-source :dirty? true)
        result (chain/enrich-cursor-data mock-snapshot source nil)]
    (is (not (contains? (get result :cursor/source) :dirty-diff-hash))
        "no :dirty-diff-hash without explicit override")))

(deftest cursor-data-contains-source-with-run-config-hash
  (let [enriched (chain/enrich-cursor-data mock-snapshot (assoc mock-source :run-config-hash "run-conf-001"))]
    (is (= "run-conf-001" (get-in enriched [:cursor/source :run-config-hash]))))
  (let [no-run-hash (chain/enrich-cursor-data mock-snapshot mock-source)]
    (is (not (contains? (get no-run-hash :cursor/source) :run-config-hash)))))

;; ── Signature rejection ────────────────────────────────────────────────────

(deftest registry-verification-rejects-an-invalid-signature
  (let [registry {:registry-hash "a-valid-looking-hash"}
        signature {:signature "tampered-signature" :signer "test-key"}
        result (with-redefs [signing/verify-signature (fn [& _] false)]
                 (chain/verify-registry-signature registry signature))]
    (is (false? (:valid result)))
    (is (= :invalid-signature (:reason result)))))

(deftest cursor-verification-rejects-an-invalid-signature
  (let [base {:cursor/scope :targeted-evidence
              :cursor/final-seq 1
              :cursor/final-self-hash "evidence-hash"
              :cursor/total-captured 1}
        signed-hash (hc/hash-with-intent {:hash/intent :evidence-chain} base)
        cursor (assoc base
                      :cursor/signed-hash signed-hash
                      :cursor/forensic {:cursor/signature "tampered-signature"
                                        :cursor/signer "test-key"})
        result (with-redefs [signing/verify-signature (fn [& _] false)]
                 (chain/verify-cursor-signature cursor))]
    (is (false? (:valid result)))
    (is (= :invalid-signature (:reason result)))))
