(ns resolver-sim.claim-outcome
  "claim-outcome.v1 — first-class hashed evaluated-claim artifact.

   A claim is a committed assertion about a committed subject, supported by
   committed evidence. This namespace formalizes the *evaluated* claim result as
   a content-addressed artifact, distinct from:
     - the claim definition (claim.v1 registry, e.g. data/claims/*.edn), and
     - an attestation of the claim (resolver-sim.evidence.attestation*).

   The evaluated claim commits the claim definition root it was evaluated
   against, the committed subject it asserts about, the committed evidence roots
   that support it, the outcome, severity, and basis.

   This namespace does NOT own evaluation policy: which evaluator runs, how
   outcomes are decided, or how attestations bind to the outcome remain with the
   claim engine and attestation layers. It is framework-neutral and depends only
   on the hash infrastructure."
  (:require [resolver-sim.hash.algorithm :as halgo]
            [resolver-sim.hash.canonical :as hash]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:const claim-outcome-schema-version
  "claim-outcome.v1")

(def ^:const claim-outcome-hash-domain
  :claim-outcome-v1)

(def supported-claim-outcomes
  "Bounded vocabulary of evaluated-claim outcomes."
  #{:pass :fail :inconclusive :not-implemented :not-exercised})

;; ---------------------------------------------------------------------------
;; Keys
;; ---------------------------------------------------------------------------

(def schema-version-key :claim/schema-version)
(def definition-root-key :claim/definition-root)
(def subject-root-key :claim/subject-root)
(def evidence-roots-key :claim/evidence-roots)
(def outcome-key :claim/outcome)
(def severity-key :claim/severity)
(def basis-key :claim/basis)
(def hash-algorithm-key :claim/hash-algorithm)
(def hash-key :claim/hash)

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-claim-outcome!
  "Return `outcome` when it is a supported claim outcome, otherwise throw.
   Never silently coerces an unknown outcome."
  [outcome]
  (when-not (contains? supported-claim-outcomes outcome)
    (throw (ex-info "unsupported claim outcome"
                    {:type :unsupported-claim-outcome
                     :outcome outcome
                     :supported supported-claim-outcomes})))
  outcome)

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn claim-outcome-hash
  "Canonical content hash of a claim-outcome.v1 artifact. Hashes the artifact
   minus :claim/hash (the self-reference), committing schema version, claim
   definition root, subject root, sorted evidence roots, outcome, severity, and
   basis, plus the hash algorithm. Evidence roots are canonicalized by sorting,
   so their order is not significant. Rejects unsupported hash algorithms."
  ([artifact]
   (claim-outcome-hash artifact (get artifact hash-algorithm-key halgo/default-hash-algorithm)))
  ([artifact hash-algorithm]
   (let [algo (halgo/validate-hash-algorithm! hash-algorithm)
         preimage (-> artifact
                      (dissoc hash-key)
                      (update evidence-roots-key (fn [roots] (vec (sort (map str roots)))))
                      (assoc hash-algorithm-key algo))]
     (hash/domain-hash claim-outcome-hash-domain preimage))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(defn claim-outcome
  "Construct a claim-outcome.v1 artifact: a committed evaluated-claim assertion
   about `subject-root`, supported by `evidence-roots`, and committing the claim
   `definition-root`, `outcome`, `severity`, and optional `basis`. Returns a
   content-addressed artifact map with :claim/hash committed."
  [{:keys [definition-root subject-root evidence-roots outcome severity basis]}]
  (let [artifact (cond-> {schema-version-key claim-outcome-schema-version
                          definition-root-key definition-root
                          subject-root-key subject-root
                          evidence-roots-key (vec (map str (or evidence-roots [])))
                          outcome-key (validate-claim-outcome! outcome)
                          severity-key severity
                          hash-algorithm-key halgo/default-hash-algorithm}
                   basis (assoc basis-key basis))]
    (assoc artifact hash-key (claim-outcome-hash artifact))))
