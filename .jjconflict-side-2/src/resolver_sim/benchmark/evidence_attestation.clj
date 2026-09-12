(ns resolver-sim.benchmark.evidence-attestation
  "Producer attestation for benchmark final evidence.

  Conceptual separation this namespace enforces:

    final evidence/content root   integrity of the evidence CONTENT
                                  (benchmark/integrity :evidence/hash,
                                   scheme bundle-root.v2)

    producer attestation          an identified producer SIGNS that exact root
                                  (this namespace, detached artifact)

    attestation verification      verification of the producer CLAIM against
                                  the evidence actually presented

  Signing is NEVER part of the final-evidence content or its root: unsigned
  bundles remain first-class and are simply integrity-assured only. The
  attestation lives as a detached sibling artifact with schema
  benchmark-evidence-attestation.v1, reusing the repository's single wire
  convention (detached Ed25519 over \"payload-type\\npayload-hash\" via
  resolver-sim.evidence.finalization-signature).

  Anti-transplant binding: the signed subject binds

    :evidence/root              the committed content root (primary binder —
                                any other evidence has a different root)
    :evidence/commitment-version which scheme produced that root
    :benchmark/id               which benchmark declaration it attests

  Two runs producing byte-identical evidence share one root; transplanting an
  attestation between them is semantically void (same bytes), so no unique
  run-id participates. Transplanting onto DIFFERENT evidence fails root
  comparison at verification."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.evidence.finalization-signature :as envelope]))

(def ^:const schema-version "benchmark-evidence-attestation.v1")
(def ^:const payload-schema-version "benchmark-final-evidence.v1")
(def ^:const subject-kind "benchmark-final-evidence.v1")
(def ^:const payload-type
  "application/vnd.prf.benchmark-final-evidence.v1+json")

(defn profile
  "The envelope profile consumed by finalization-signature generic fns."
  []
  {:envelope-schema-version schema-version
   :payload-type payload-type
   :payload-schema-version payload-schema-version
   :subject-kind subject-kind})

(defn- sha256-ref
  [hex]
  (str "sha256:" hex))

(defn load-evidence!
  "Read a persisted final-evidence bundle and fail closed unless its committed
  :evidence/hash recomputes. Returns {:bundle b :verification v}."
  [evidence-path]
  (let [bundle (-> evidence-path io/file slurp read-string)
        verification (integrity/verify-bundle-hash bundle)]
    (when-not (:hash-ok? verification)
      (throw (ex-info "Refusing to process final evidence with broken integrity"
                      {:path (str evidence-path)
                       :reason (:reason verification)})))
    {:bundle bundle :verification verification}))

(defn build-attestation
  "Detached producer attestation for verified final evidence."
  ([bundle key-id private-key]
   (build-attestation bundle key-id private-key nil))
  ([bundle key-id private-key notes]
   (let [{:keys [hash-ok?]} (integrity/verify-bundle-hash bundle)]
     (when-not hash-ok?
       (throw (ex-info "Refusing to attest final evidence with broken integrity"
                       {:reason :evidence/integrity-invalid})))
     (let [root (:evidence/hash bundle)
           subject (merge {:kind subject-kind
                           :evidence/root (sha256-ref root)
                           :evidence/commitment-version
                           (or (:evidence/commitment-version bundle) "bundle-root.v2-default")
                           :benchmark/id (get-in bundle [:benchmark :id])}
                          (when notes {:notes notes}))]
       (envelope/build-envelope* (profile)
                                 (sha256-ref root)
                                 key-id private-key
                                 subject)))))

(defn verify-attestation
  "Full verification of `attestation` against `bundle`:

    1. bundle integrity recomputes (:integrity-ok?)
    2. envelope shape/profile validates under this schema
    3. signature verifies under `public-key`
    4. subject binds EXACTLY this bundle's root and identity
       (:transplant-safe?)

  Returns {:valid? bool :integrity-ok? bool :transplant-safe? bool ...};
  never throws for verification outcomes."
  [bundle attestation public-key]
  (try
    (let [verification (integrity/verify-bundle-hash bundle)
          integrity-ok? (:hash-ok? verification)
          shape (envelope/validate-envelope* (profile) attestation)
          root (:evidence/hash bundle)
          expected-ref (when root (sha256-ref root))
          subject-root-match?
          (and root
               (= expected-ref (get-in attestation [:payload :subject :evidence/root]))
               (= expected-ref (get-in attestation [:payload :payload-hash])))
          identity-match?
          (and (= subject-kind (get-in attestation [:payload :subject :kind]))
               (= (get-in bundle [:benchmark :id])
                  (get-in attestation [:payload :subject :benchmark/id])))
          transplant-safe? (boolean (and subject-root-match? identity-match?))
          crypto (when (:valid? shape)
                   (envelope/verify-envelope* (profile) attestation public-key))
          valid? (boolean (and integrity-ok?
                               (:valid? shape)
                               transplant-safe?
                               (and crypto (:valid? crypto))))]
      (cond-> {:valid? valid?
               :integrity-ok? integrity-ok?
               :transplant-safe? transplant-safe?
               :key-id (get-in attestation [:signature :key-id])}
        (not integrity-ok?) (assoc :reason (:reason verification))
        (not (:valid? shape)) (assoc :reason :malformed-envelope
                                     :errors (:errors shape))
        (and (:valid? shape) (not transplant-safe?)) (assoc :reason :subject-mismatch)
        (and (:valid? shape) crypto (not (:valid? crypto))) (assoc :reason (:reason crypto))
        valid? (assoc :scheme (:scheme verification))))
    (catch Exception e
      {:valid? false :reason :attestation-verification-error
       :detail (.getMessage e)})))

(defn load-attestation
  "Read an attestation artifact; nil when absent."
  [attestation-path]
  (let [f (io/file attestation-path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn write-attestation!
  "Persist a detached attestation next to its evidence (or an explicit path).
  Refuses to overwrite an existing DISTINCT attestation; writing the identical
  envelope again is idempotent.

  Persisted as EDN, not JSON: the subject carries Clojure keyword values
  (e.g. :benchmark/id) that a JSON round-trip would silently mangle."
  ([evidence-path bundle key-id private-key]
   (write-attestation! evidence-path bundle key-id private-key nil))
  ([evidence-path bundle key-id private-key notes]
   (let [attestation (build-attestation bundle key-id private-key notes)
         default-path (str evidence-path ".attestation.json")
         path (io/file default-path)]
     (when (.exists path)
       (let [existing (load-attestation path)]
         (when-not (= existing attestation)
           (throw (ex-info "Refusing to overwrite distinct evidence attestation"
                           {:reason :attestation-already-exists
                            :path (.getPath path)})))))
     (envelope/atomic-write! path (pr-str attestation))
     (.getPath path))))

