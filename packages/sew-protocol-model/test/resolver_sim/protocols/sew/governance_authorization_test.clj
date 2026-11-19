(ns resolver-sim.protocols.sew.governance-authorization-test
  "Governance-authorization provenance assurance and V1/V2 compatibility.

   V1 policy: \"governance-authorization.v1\" (basis :scenario-declared, no
   authentication-mode / configured-governance-address / address-bound? /
   registry-verified? fields). Retained only as an explicit legacy provenance
   shape; it cannot establish address-bound authentication.
   V2 policy: \"governance-authorization.v2\" — commits authentication-mode,
   configured-governance-address, actor-address, address-bound?, basis, source,
   registry-verified?. The committed value is the related-claims V2 hash, which
   recomputes the creator-provenance (including :authorization/assurance).
   No persisted V1 provenance artifacts are known to exist; V1 remains readable
   as legacy metadata but cannot be upgraded to address-bound status by attaching
   uncommitted fields."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.related-claims :as rc]))

(def members [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}])

(defn- v2-provenance
  "Canonical V2 creator-provenance for an address-bound grant."
  [& {:keys [authentication-mode configured-governance-address actor-address
             address-bound? basis source schema-version]
      :or {authentication-mode :address-bound
           configured-governance-address "0xGov"
           actor-address "0xGov"
           address-bound? true
           basis :scenario-configured-address-binding
           source :replay-context/agent-index
           schema-version "governance-authorization.v2"}}]
  {:authorization/schema-version schema-version
   :authorization/type :governance
   :authorization/basis basis
   :authorization/source source
   :authorization/authentication-mode authentication-mode
   :authorization/assurance authentication-mode
   :authorization/configured-governance-address configured-governance-address
   :authorization/actor-address actor-address
   :authorization/address-bound? address-bound?
   :authorization/registry-verified? false
   :actor/type :governance
   :actor/address configured-governance-address})

(deftest governance-authorization-v2-hash-sensitivity
  (testing "the committed (V2) hash changes when any assurance-relevant field changes"
    (let [base (v2-provenance)
          base-hash (rc/related-claims-hash members base)
          cases {:authentication-mode (v2-provenance :authentication-mode :role-declared
                                                     :address-bound? false)
                 :assurance (assoc (v2-provenance) :authorization/assurance :role-declared)
                 :configured-governance-address (v2-provenance :configured-governance-address "0xOther")
                 :actor-address (v2-provenance :actor-address "0xMallory")
                 :address-bound? (v2-provenance :address-bound? false)
                 :basis (v2-provenance :basis :scenario-declared-role)
                 :source (v2-provenance :source :repl-interactive-session)}]
      (doseq [[k prov] cases]
        (is (not= base-hash (rc/related-claims-hash members prov))
            (str "V2 committed hash must change when " (name k) " changes")))
      (is (= base-hash (rc/related-claims-hash members (v2-provenance)))
          "deterministic recomputation of the committed value"))))

(deftest v1-provenance-cannot-upgrade-to-address-bound
  (testing "a V1 provenance cannot be upgraded to address-bound/authenticated status via attached uncommitted metadata"
    (let [v1-prov {:authorization/schema-version "governance-authorization.v1"
                   :authorization/type :governance
                   :authorization/basis :scenario-declared
                   :authorization/check :with-governance-actor
                   :actor/address "0xGov"}
          v1-hash (rc/related-claims-hash members v1-prov)
          v2-hash (rc/related-claims-hash members (v2-provenance))
          record {:relationship/assurance :address-bound
                  :relationship/authenticated? true
                  :relationship/creator-provenance v1-prov
                  :relationship/hash v1-hash}]
      (is (not= v1-hash v2-hash) "V1 and V2 provenance produce different committed hashes")
      (is (false? (rc/authenticated-related-claims? record))
          "a V1-provenance record with an attached assurance flag is NOT authenticated — assurance is not in the committed provenance")
      (is (= (:relationship/hash record) (rc/related-claims-hash members v1-prov))
          "attaching V2 metadata outside the committed hash leaves the committed identity (and its V1 provenance) unchanged"))))
