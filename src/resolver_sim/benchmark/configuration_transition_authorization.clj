(ns resolver-sim.benchmark.configuration-transition-authorization
  "C3a: frozen predecessor-state evidence that governance authorised one exact
  chain-configuration transition. This namespace constructs and verifies evidence
 (ns resolver-sim.benchmark.configuration-transition-authorization) only; it deliberately does not activate a configuration head."
  (:require [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "configuration-transition-authorization-evidence.v1")
(def ^:const domain :configuration-transition-authorization-evidence-v1)

(def fields
  #{:artifact/schema
    :predecessor-authoritative-state/root
    :predecessor-configuration/root
    :predecessor-configuration-head/root
    :predecessor-governance/root
    :authorization-subject/kind
    :authorization-subject/root
    :configuration-transition/root
    :resolved-review-authority-context/root
    :authority-evaluation-basis/root
    :authority-report/root})

(defn- root [evidence]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc evidence :configuration-transition-authorization-evidence/root)))))

(defn- closed-rooted? [evidence]
  (and (map? evidence)
       (= schema (:artifact/schema evidence))
       (= fields (set (keys (dissoc evidence :configuration-transition-authorization-evidence/root))))
       (every? #(ref/valid-sha256-ref? (get evidence %))
               (disj fields :artifact/schema :authorization-subject/kind))
       (= :configuration-transition (:authorization-subject/kind evidence))
       (= (:authorization-subject/root evidence) (:configuration-transition/root evidence))
       (or (not (contains? evidence :configuration-transition-authorization-evidence/root))
           (= (:configuration-transition-authorization-evidence/root evidence) (root evidence)))))

(defn verify-evidence
  "Recompute C3a evidence from frozen predecessor material. `witness` contains
  the rooted artifact plus uncommitted verifier inputs; those inputs never enter
  the evidence projection except through independently recomputed roots."
  [{:keys [evidence predecessor-envelope predecessor-head-state predecessor-material configuration-transition authorisation]}]
  (try
    (let [v2? (= state/envelope-v2-schema (:artifact/schema predecessor-envelope))
          envelope (if v2?
                     (state/build-envelope-v2 predecessor-envelope predecessor-head-state)
                     (state/build-envelope predecessor-envelope))
          envelope-root (:authoritative-state-envelope/root envelope)
          _ (if v2?
              (state/new-store-v2 envelope predecessor-head-state predecessor-material)
              (state/new-store envelope predecessor-material))
          transition-root (genesis/chain-configuration-transition-root configuration-transition)
          report (state/evaluate-authority-with-frozen-material
                  {:authorisation authorisation
                   :review-round (:authority-material/review-round predecessor-material)
                   :review-governance (:authority-material/review-governance predecessor-material)
                   :position-time-index (:authority-material/position-time-index predecessor-material)
                   :signer-key-set (:authority-material/signer-key-set predecessor-material)})
          report-root (authority/authority-report-root report)
          evaluation-basis-root (:authority-evaluation-basis/root
                                 (state/evaluation-basis
                                  {:resolved-review-authority-context/root
                                   (:resolved-review-authority-context/root evidence)
                                   :review-round/root (:review-round/root predecessor-material)
                                   :review-governance/root (:review-governance/root predecessor-material)
                                   :position-time-basis/root (:position-time-basis/root predecessor-material)
                                   :position-time-index/root (:position-time-index/root predecessor-material)
                                   :signer-key-set/root (:signer-key-set/root predecessor-material)}))]
      {:valid? (and (closed-rooted? evidence)
                    (= envelope-root (:predecessor-authoritative-state/root evidence))
                    (= (:chain-configuration/root envelope) (:predecessor-configuration/root evidence))
                    (= (:configuration-head/root envelope) (:predecessor-configuration-head/root evidence))
                    (= (:review-governance/root envelope) (:predecessor-governance/root evidence))
                    (= (:chain-configuration/root predecessor-material) (:predecessor-configuration/root evidence))
                    (= (:review-governance/root predecessor-material) (:predecessor-governance/root evidence))
                    (= transition-root (:configuration-transition/root evidence))
                    (= (:configuration/parent-root configuration-transition) (:predecessor-configuration/root evidence))
                    (= (:configuration-transition/root evidence)
                       (get-in authorisation [:authorisation/target :target/proposed-content-root]))
                    (= report-root (:authority-report/root evidence))
                    (= :authorised (:authority-status report))
                    (= evaluation-basis-root (:authority-evaluation-basis/root evidence)))
       :report report})
    (catch Exception e
      {:valid? false :reason :configuration-transition-authorization-evidence-invalid
       :detail (.getMessage e)})))

(defn build-evidence-candidate
  "Construct a rooted C3a candidate from frozen predecessor inputs. This is not
  an admission API: callers must pass the result through `verify-evidence`.
  Production construction uses `build-verified-evidence`."
  [{:keys [predecessor-envelope predecessor-head-state predecessor-material configuration-transition authorisation
           resolved-review-authority-context-root]}]
  (let [envelope (if (= state/envelope-v2-schema (:artifact/schema predecessor-envelope))
                   (state/build-envelope-v2 predecessor-envelope predecessor-head-state)
                   (state/build-envelope predecessor-envelope))
        transition-root (genesis/chain-configuration-transition-root configuration-transition)
        report (state/evaluate-authority-with-frozen-material
                {:authorisation authorisation
                 :review-round (:authority-material/review-round predecessor-material)
                 :review-governance (:authority-material/review-governance predecessor-material)
                 :position-time-index (:authority-material/position-time-index predecessor-material)
                 :signer-key-set (:authority-material/signer-key-set predecessor-material)})
        evaluation-basis (state/evaluation-basis
                          {:resolved-review-authority-context/root resolved-review-authority-context-root
                           :review-round/root (:review-round/root predecessor-material)
                           :review-governance/root (:review-governance/root predecessor-material)
                           :position-time-basis/root (:position-time-basis/root predecessor-material)
                           :position-time-index/root (:position-time-index/root predecessor-material)
                           :signer-key-set/root (:signer-key-set/root predecessor-material)})
        base {:artifact/schema schema
              :predecessor-authoritative-state/root (:authoritative-state-envelope/root envelope)
              :predecessor-configuration/root (:chain-configuration/root envelope)
              :predecessor-configuration-head/root (:configuration-head/root envelope)
              :predecessor-governance/root (:review-governance/root envelope)
              :authorization-subject/kind :configuration-transition
              :authorization-subject/root transition-root
              :configuration-transition/root transition-root
              :resolved-review-authority-context/root resolved-review-authority-context-root
              :authority-evaluation-basis/root (:authority-evaluation-basis/root evaluation-basis)
              :authority-report/root (authority/authority-report-root report)}]
    (assoc base :configuration-transition-authorization-evidence/root (root base))))

(defn build-verified-evidence
  "Construct and independently verify C3a evidence. No public production path
  returns a configuration-transition authorization artifact until predecessor
  authority has been recomputed for the exact typed transition subject."
  [witness]
  (let [evidence (build-evidence-candidate witness)
        verification (verify-evidence (assoc witness :evidence evidence))]
    (when-not (:valid? verification)
      (throw (ex-info "configuration-transition authorization evidence is invalid"
                      {:type :configuration-transition-authorization-evidence/invalid
                       :verification verification})))
    evidence))
