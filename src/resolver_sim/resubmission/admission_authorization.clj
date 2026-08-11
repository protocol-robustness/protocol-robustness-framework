(ns resolver-sim.resubmission.admission-authorization
  "Domain-separated signer-v2 authorization evidence for fenced admission.
   This is intentionally independent from legacy `resubmission issue`, which
   attests an already committed transaction ordering."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as sed]))

(def schema "resubmission-admission-authorization.v2")
(def domain "prf.resubmission-admission-authorization.v2")

(defn unsigned-projection [evidence]
  ;; The signature envelope is attached after both identity and signing bytes
  ;; are formed, matching the legacy receipt identity convention.
  (dissoc evidence :authorization/evidence-root :authorization/signature))

(defn evidence-root [evidence]
  (hash-ref/sha256-ref (hc/domain-hash domain (unsigned-projection evidence))) )

(defn build-unsigned
  "Construct evidence from exactly the reservation-bound signing payload."
  [payload key-id]
  {:authorization/schema schema
   :authorization/purpose :resubmission/finalization
   :authorization/payload-root (:signing/payload-root payload)
   :authorization/partition-key (:signing/partition-key payload)
   :authorization/reservation-id (:signing/reservation-id payload)
   :authorization/fence (:signing/fence payload)
   :authorization/expected-state-version (:signing/expected-state-version payload)
   :authorization/candidate-root (:signing/candidate-root payload)
   :authorization/validation-root (:signing/validation-root payload)
   :authorization/proposed-ordering-root (:signing/proposed-ordering-root payload)
   :authorization/key-id key-id})

(defn sign [payload private-key key-id]
  (let [unsigned (build-unsigned payload key-id)
        bytes (hc/canonical-bytes (unsigned-projection unsigned))
        signed (assoc unsigned :authorization/signature
                      {:signature/algorithm :ed25519
                       :signature (sed/ed25519-sign-bytes bytes private-key)})]
    (assoc signed :authorization/evidence-root (evidence-root signed))))

(defn verify
  "Verify the evidence is structurally bound to this exact payload and signer."
  [payload evidence public-hex]
  (let [sig (:authorization/signature evidence)
        expected (build-unsigned payload (:authorization/key-id evidence))]
    (and (= schema (:authorization/schema evidence))
         (= :resubmission/finalization (:authorization/purpose evidence))
         (= (:authorization/evidence-root evidence) (evidence-root evidence))
         (= (dissoc evidence :authorization/signature :authorization/evidence-root)
            expected)
         (= :ed25519 (:signature/algorithm sig))
         (sed/ed25519-verify-bytes
          (hc/canonical-bytes (unsigned-projection evidence))
          (:signature sig) public-hex))))
