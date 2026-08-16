(ns resolver-sim.assurance.admission-fixed-point
  "Fixed-point and decision stability verification for custody artifacts.

  Demonstrates that:
  1. Custody artifacts canonically serialize and deserialize identically
  2. Admission decisions are stable under canonical transport
  3. Tampering is preserved (not washed away) by canonicalization

  The canonical fixed-point answers: \"does this object survive canonical
  transport unchanged?\"  Tamper detection answers: \"does this transported
  object still satisfy the commitments and admission semantics?\"

  Together they prove that canonicalization faithfully preserves ALL input,
  including tampering, allowing a verifier to deterministically reject."
  (:require [resolver-sim.assurance.custody :as custody]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.round-trip :as rt]))

;; ── Decision projection ──────────────────────────────────────────────────────
;;
;; An admission decision is projected to its authoritative semantics for
;; commitment and comparison.  Ordering must be canonical (sorted by string)
;; so that the decision-root is deterministic regardless of insertion order
;; or Clojure's set literal representation.

(defn- canonical-vec
  "Convert a collection to a canonical-order sorted vector."
  [coll]
  (vec (sort-by str coll)))

(defn decision-body
  "Project admission verification result to canonical decision form for
  commitment.  This is the authoritative representation used for the
  decision-root hash and for comparing decisions across fixed-point boundaries.

  Returns:
  {:decision/schema \"custody-admission-decision.v1\"
   :subject-root <string?> — artifact sequence root (nil for empty)
   :evidence-root <string> — ledger root of the evidence input
   :admitted? boolean
   :blocking-reasons [keyword...] — sorted by str
   :failed-check-ids [keyword...] — sorted by str}"
  [admitted? blocking-reasons failed-check-ids subject-root evidence-root]
  {:decision/schema "custody-admission-decision.v1"
   :subject-root subject-root
   :evidence-root evidence-root
   :admitted? admitted?
   :blocking-reasons (canonical-vec blocking-reasons)
   :failed-check-ids (canonical-vec failed-check-ids)})

(defn decision-root
  "Compute the commitment root of an admission decision body.

  H(\"custody-admission-decision.v1\", canonical_bytes(body))"
  [body]
  (hc/domain-hash "custody-admission-decision.v1" body))

(defn- run-closed-form-checks
  "Run held-custody-closed-form-checks and return {:checks [...] :error nil}
   or {:checks [...] :error ex-data} when verification throws (failed checks)."
  [artifacts]
  (try
    (let [checks (custody/held-custody-closed-form-checks artifacts)
          failed (filter #(= :fail (:status %)) checks)]
      {:checks checks
       :admitted? (empty? failed)
       :blocked? (seq failed)
       :error nil})
    (catch clojure.lang.ExceptionInfo e
      {:checks (:check-results (ex-data e))
       :admitted? false
       :blocked? true
       :error (dissoc (ex-data e) :check-results)})))

(defn verify-and-project
  "Run custody verification on artifacts and project to canonical
  decision form with commitment root.

  artifacts is a sequence of held-custody-artifact maps (as produced by
  custody/rebuild-held-custody-artifacts).

  evidence-input is the original held-adjustment vector (needed for ledger-root).

  Returns a decision projection map including :decision-root, :checks,
  :admitted?, :blocking-reasons, :subject-root, :evidence-root, :failed-check-ids."
  [artifacts evidence-input]
  (let [artifacts (vec artifacts)
        result (run-closed-form-checks artifacts)
        checks (:checks result)
        blocking (set (map :check/id
                           (filter #(= :fail (:status %)) checks)))
        subject-root (custody/artifact-sequence-root artifacts)
        evidence-root (custody/ledger-root evidence-input)
        body (decision-body (:admitted? result) blocking blocking
                            subject-root evidence-root)]
    (assoc body
           :decision-root (decision-root body)
           :checks checks)))

;; ── Admission fixed-point ────────────────────────────────────────────────────
;;
;; Verify that an admission decision is stable under canonical round-trip:
;;
;;   artifacts ──► verify ──► decision₁
;;     │
;;     │ canonical round-trip (artifacts)
;;     ▼
;;   decoded-artifacts ──► verify ──► decision₂
;;     │
;;     ▼
;;   decision₁ == decision₂
;;
;; Two properties:
;; 1. Canonical artifact fixed-point: bytes₁ == bytes₂ (serialization stable)
;; 2. Verification fixed-point: same decision projection after decode

(defn admission-fixed-point
  "Verify custody admission decision survives canonical round-trip.

  Two properties are tested:
  1. Canonical artifact fixed-point — the artifacts' canonical bytes are
     identical after encode→decode→encode.
  2. Verification fixed-point — re-verifying the decoded artifacts produces
     the exact same decision projection.

  This proves that canonicalization preserves everything faithfully:
  a tampered artifact is still a fixed point of serialization, but its
  verification result differs — demonstrating that canonicalization does NOT
  wash away tampering.

  Args:
    artifacts — a sequence of held-custody-artifact maps
    evidence-input — the original held-adjustment vector (for ledger root)

  Returns:
  {:canonical-fixed-point? boolean
   :verification-fixed-point? boolean
   :decision-root-consistent? boolean
   :holds? boolean
   :original map
   :decoded map
   :violations [...]}"
  [artifacts evidence-input]
  (let [artifacts (vec artifacts)
        original-proj (verify-and-project artifacts evidence-input)
        round-trip (rt/canonical-round-trip artifacts)
        canonical-ok? (:valid? round-trip)
        decoded-artifacts (when canonical-ok? (:value round-trip))
        decoded-proj (when decoded-artifacts
                       (verify-and-project decoded-artifacts evidence-input))
        bytes-identical? (when (and canonical-ok? decoded-artifacts)
                           (java.util.Arrays/equals
                            (hc/canonical-bytes artifacts)
                            (hc/canonical-bytes decoded-artifacts)))
        projection-match? (when (and canonical-ok? decoded-proj)
                            (= (dissoc original-proj :checks :decision-root)
                               (dissoc decoded-proj :checks :decision-root)))]
    {:canonical-fixed-point? (and canonical-ok? bytes-identical?)
     :verification-fixed-point? (boolean projection-match?)
     :decision-root-consistent? (= (:decision-root original-proj)
                                   (when decoded-proj (:decision-root decoded-proj)))
     :holds? (and canonical-ok? bytes-identical? projection-match?)
     :original original-proj
     :decoded decoded-proj
     :violations (vec (cond-> []
                        (not canonical-ok?)
                        (conj {:kind ::canonical-round-trip-failed
                               :issues (:issues round-trip)})
                        (and canonical-ok? (not bytes-identical?))
                        (conj {:kind ::bytes-mismatch})
                        (and canonical-ok? (not projection-match?))
                        (conj {:kind ::projection-mismatch
                               :original (dissoc original-proj :checks)
                               :decoded (when decoded-proj (dissoc decoded-proj :checks))})))}))

(defn decision-stability-report
  "Build a presentation-ready report for the notebook showing the full
  decision stability analysis: artifact integrity, verification result,
  and fixed-point verification.

  Args:
    artifacts — a sequence of held-custody-artifact maps
    evidence-input — the original held-adjustment vector (for ledger root)

  Returns:
  {:subject-root string?
   :evidence-root string
   :admitted? boolean
   :checks [...]
   :blocking-reasons [keyword...]
   :failed-check-ids [keyword...]
   :decision-root string
   :fixed-point map}"
  [artifacts evidence-input]
  (let [proj (verify-and-project artifacts evidence-input)
        fp (admission-fixed-point artifacts evidence-input)]
    (-> proj
        (assoc :fixed-point fp)
        (dissoc :checks))))