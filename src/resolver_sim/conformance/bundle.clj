(ns resolver-sim.conformance.bundle
  "Portable conformance bundle (G5c).

   A closed artifact containing everything required to verify a claim:

     profile, environment, subject-identities, plan, validation/capability/
     execution receipts, reconciliation, coverage, exclusions, claim.

   verify must NEVER regenerate or repair, never resolve missing receipts by
   running domain code, never mutate the bundle, fail closed on external
   references, and derive the claim INDEPENDENTLY from the bundled evidence and
   compare it with the supplied claim.

   build and verify are separate surfaces: build assembles, verify only checks."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.claim :as claim]
            [resolver-sim.conformance.reconciliation :as rec]))

(def bundle-schema-version "conformance.bundle/v1")

(def supported-canonicalisation-ids
  #{:prf-canonical-edn.v1 "prf-canonical-edn.v1"
    :canonical-json-sha256.v1 "canonical-json-sha256.v1"})

(defn bundle-root
  "Content root of a bundle's committed fields (excluding :bundle/root and
   :bundle/index).

   The committed value is projected to canonical-safe form (sets → sorted
   vectors, ratios → exact form) before hashing: a bundle carries set-valued
   fields (e.g. subject-identities, coverage, exclusions), which the strict
   canonical encoder rejects. Projection is deterministic."
  [bundle]
  (hc/domain-hash "conformance.bundle.v1"
                  (hc/project-committable-content
                   (dissoc bundle :bundle/root :bundle/index))))

(defn build-bundle
  "Assemble a conformance bundle.  Does NOT verify."
  [{:keys [profile environment subject-identities plan
           validation-receipts capability-receipts execution-receipts
           reconciliation coverage exclusions claim]}]
  (let [base {:bundle/schema-version bundle-schema-version
              :bundle/id :conformance.bundle/default
              :profile profile
              :environment environment
              :subject-identities (vec subject-identities)
              :plan plan
              :validation-receipts (vec validation-receipts)
              :capability-receipts (vec capability-receipts)
              :execution-receipts (vec execution-receipts)
              :reconciliation reconciliation
              :coverage coverage
              :exclusions (vec exclusions)
              :claim claim
              :bundle/index [:profile :environment :plan :reconciliation
                             :coverage :claim]}]
    (assoc base :bundle/root (bundle-root base))))

(defn- recompute-issues
  "Every embedded verdict artifact must recompute exactly under its committed
   inputs.  A reconciliation without a root is not reproducible."
  [{:keys [reconciliation]}]
  (cond-> []
    (nil? (:reconciliation/root reconciliation))
    (conj {:issue/code :reconciliation-not-reproducible
           :issue/details {:reason :missing-reconciliation-root}})
    (and (some? (:reconciliation/root reconciliation))
         (not= (:reconciliation/root reconciliation)
               (rec/reconciliation-root reconciliation)))
    (conj {:issue/code :reconciliation-not-reproducible
           :issue/details {:expected (rec/reconciliation-root reconciliation)}})))

(defn- root-agreement-issues
  "Roots must agree across the verdict envelopes."
  [{:keys [plan reconciliation coverage claim]}]
  (let [plan-root (:plan/root plan)
        recon-plan-root (:plan/root reconciliation)
        env-plan (:environment/root plan)
        env-recon (:environment/root reconciliation)
        env-cov (:environment/root coverage)]
    (cond-> []
      (and plan-root recon-plan-root (not= plan-root recon-plan-root))
      (conj {:issue/code :plan-root-disagreement
             :issue/details {:plan plan-root :reconciliation recon-plan-root}})
      (and env-plan env-recon (not= env-plan env-recon))
      (conj {:issue/code :environment-root-disagreement
             :issue/details {:plan env-plan :reconciliation env-recon}})
      (and env-recon env-cov (not= env-recon env-cov))
      (conj {:issue/code :environment-root-disagreement
             :issue/details {:reconciliation env-recon :coverage env-cov}})
      (and claim (:reconciliation/root claim) (:reconciliation/root reconciliation)
           (not= (:reconciliation/root claim) (:reconciliation/root reconciliation)))
      (conj {:issue/code :claim-not-bound-to-reconciliation
             :issue/details {:claim (:reconciliation/root claim)
                             :reconciliation (:reconciliation/root reconciliation)}}))))

(defn- unexpected-receipt-issues
  "Spec §11: adding unexpected evidence MUST NOT improve claimability.  Every
   receipt in the bundle must be covered by a declared plan step; with a plan
   that declares no steps, any receipt is unexpected."
  [{:keys [plan validation-receipts capability-receipts execution-receipts]}]
  (let [plan-step-ids (set (map :step/id (:steps plan)))
        receipts (concat validation-receipts capability-receipts execution-receipts)
        unexpected (filter (fn [r] (not (contains? plan-step-ids (:step/id r))))
                           receipts)]
    (mapv (fn [r] {:issue/code :unexpected-receipt
                   :issue/details {:step/id (:step/id r)
                                   :plan-steps (vec (sort plan-step-ids))}})
          unexpected)))

(defn- key->json-name
  "Keyword keys serialise to their namespace-qualified name WITHOUT the leading
   colon, so the canonical JSON matches the Python verifier."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn- sort-keys
  "Recursively sort map keys for deterministic canonical JSON."
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [(key->json-name k) (sort-keys v)])) x)
    (vector? x) (mapv sort-keys x)
    (seq? x) (mapv sort-keys x)
    :else x))

(defn canonical-json-str
  "Deterministic canonical JSON serialization (sorted keys) shared with the
   Python bundle verifier for cross-language parity.  Un-escapes the Java
   `\\/` slash escaping so bytes match a standard JSON serializer."
  [x]
  (str/replace (json/write-str (sort-keys x)) "\\/" "/"))

(defn canonical-json-root
  "sha256 (hex) of the canonical JSON serialization of x."
  [x]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes (canonical-json-str x) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) digest))))

(def claim-parity-keys
  "The claim fields that carry semantic content and enter the cross-language
   parity root.  Informational metadata (:claim/scope, :claim/does-not-establish)
   is intentionally excluded: it is a fixed property of every claim and must not
   change the bytes an independent verifier hashes."
  [:evaluation/mode :claim/class :claim/status :reconciliation/root :environment/root])

(defn- parity-claim [claim] (select-keys claim claim-parity-keys))

(defn derive-claim-from-bundle
  "Independently derive the claim from the bundled evidence ONLY — never from
   the supplied claim.  Returns a claim map or nil when the evidence does not
   establish a claim."
  [{:keys [reconciliation coverage plan]}]
  (let [mode (or (get-in plan [:claim/mode]) :attested)
        ok? (and (= :pass (:reconciliation/status reconciliation))
                 (:coverage/complete? coverage))
        claim-class (claim/derive-claim mode ok? false)
        permitted (claim/permitted-claims-for-mode mode)]
    (when (and ok? (contains? permitted claim-class))
      (let [claim (merge {:evaluation/mode mode
                          :claim/class claim-class
                          :claim/status :pass
                          :reconciliation/root (:reconciliation/root reconciliation)
                          :environment/root (or (:environment/root reconciliation)
                                                (:environment/root coverage))}
                         claim/claim-scope-metadata)]
        (assoc claim :claim/json-root (canonical-json-root (parity-claim claim)))))))

(defn- normalize-envelope
  "Normalize envelope fields that serialize to strings in the portable JSON
   form so a round-tripped bundle verifies identically to its in-memory form.
   Does not alter or regenerate any evidence, and never materializes an absent
   envelope."
  [bundle]
  (-> bundle
      (update :reconciliation
              (fn [r] (if (and (map? r) (string? (:reconciliation/status r)))
                        (update r :reconciliation/status keyword)
                        r)))
      (update :claim
              (fn [c] (if (map? c)
                        (-> c
                            (update :evaluation/mode
                                    (fn [s] (if (string? s) (keyword s) s)))
                            (update :claim/class
                                    (fn [s] (if (string? s) (keyword s) s)))
                            (update :claim/status
                                    (fn [s] (if (string? s) (keyword s) s))))
                        c)))))

(defn verify-bundle
  "Verify a conformance bundle.  read-only: never regenerates or mutates.

   Returns {:status :pass|:rejected|:unsupported-version :claimable? bool
            :issues [...] :derived-claim ...}."
  [bundle]
  (let [bundle (normalize-envelope bundle)
        version (:bundle/schema-version bundle)
        version-issue (when-not (= bundle-schema-version version)
                        {:issue/code :unsupported-bundle-version
                         :issue/details {:schema-version version}})
        canonicalisation-id (get-in bundle [:environment :environment/committed :canonicalisation/id])
        canonicalisation-issue (when (and (some? canonicalisation-id)
                                          (not (contains? supported-canonicalisation-ids
                                                          canonicalisation-id)))
                                 {:issue/code :unsupported-canonicalisation
                                  :issue/details {:canonicalisation/id canonicalisation-id}})
        recompute (recompute-issues bundle)
        agreement (root-agreement-issues bundle)
        base-issues (into [] (concat (when version-issue [version-issue])
                                     (when canonicalisation-issue [canonicalisation-issue])
                                     recompute agreement (unexpected-receipt-issues bundle)))
        derived (when (empty? base-issues) (derive-claim-from-bundle bundle))
        supplied (:claim bundle)
        supplied-core (parity-claim (dissoc supplied :claim/json-root))
        derived-core (parity-claim (dissoc derived :claim/json-root))
        ;; the supplied claim's json-root (if present) must equal the canonical
        ;; root of the derived claim — this is the cross-language parity anchor
        json-root-issue (when (and supplied (:claim/json-root supplied) derived)
                          (when-not (= (:claim/json-root supplied)
                                       (canonical-json-root (parity-claim derived)))
                            {:issue/code :claim-json-root-mismatch
                             :issue/details {:supplied (:claim/json-root supplied)
                                             :derived (canonical-json-root (parity-claim derived))}}))
        derived-issue (when (and derived supplied (not= derived-core supplied-core))
                        {:issue/code :derived-claim-mismatch
                         :issue/details {:derived derived-core :supplied supplied-core}})
        derived-issue (or derived-issue json-root-issue)
        issues (into base-issues (when derived-issue [derived-issue]))]
    {:status (cond
               (not= bundle-schema-version version) :unsupported-version
               (and (empty? issues) (some? derived)) :pass
               :else :rejected)
     :claimable? (and (empty? issues) (some? derived))
     :issues issues
     :derived-claim derived}))
