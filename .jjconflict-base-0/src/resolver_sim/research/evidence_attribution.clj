(ns resolver-sim.research.evidence-attribution
  "Declared nominal researcher/evidence attribution, not identity authentication,
   acceptance, independence or voting. Disclosure never erases internal lineage."
  (:require [resolver-sim.benchmark.researcher-axis :as axis]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def schema "researcher-evidence-attribution.v1")
(defn attribution-root [artifact]
  (reference/sha256-ref
   (canonical/domain-hash :researcher-evidence-attribution-v1
                          (dissoc artifact :researcher-evidence-attribution/root))))

(defn project-attribution
  [researcher-axis coordinate {:keys [subject evidence-root relation disclosure] :as declaration}]
  (axis/researcher-member researcher-axis coordinate)
  (when-not (and (= #{:subject :evidence-root :relation :disclosure} (set (keys declaration)))
                 (forensic/valid-subject? subject)
                 (reference/valid-sha256-ref? evidence-root)
                 (contains? #{:produced :reproduced :reported-counterexample :dissented} relation)
                 (contains? #{:identified :pseudonymous :anonymous :undisclosed} disclosure))
    (throw (ex-info "Invalid evidence attribution declaration" {})))
  (let [body {:artifact/schema schema :attribution/coordinate coordinate
              :attribution/subject subject :attribution/evidence-root evidence-root
              :attribution/relation relation :attribution/disclosure disclosure
              :attribution/epistemic-role :declared
              :attribution/meaning :does-not-establish-authentication-independence-or-authority}]
    (assoc body :researcher-evidence-attribution/root (attribution-root body))))

(defn validate-attribution [artifact researcher-axis]
  (when-not (= artifact
               (project-attribution researcher-axis (:attribution/coordinate artifact)
                                    {:subject (:attribution/subject artifact)
                                     :evidence-root (:attribution/evidence-root artifact)
                                     :relation (:attribution/relation artifact)
                                     :disclosure (:attribution/disclosure artifact)}))
    (throw (ex-info "Detached evidence attribution" {})))
  artifact)

(defn evidence-node [artifact researcher-axis]
  (validate-attribution artifact researcher-axis)
  (forensic/build-forensic-evidence-node
   {:subject {:subject/kind :research/evidence-attribution
              :subject/root (:researcher-evidence-attribution/root artifact)}
    :role :declared :visibility :restricted
    :causal-parent-roots (vec (distinct [(:axis/root researcher-axis)
                                         (get-in artifact [:attribution/subject :subject/root])
                                         (:attribution/evidence-root artifact)]))}))
