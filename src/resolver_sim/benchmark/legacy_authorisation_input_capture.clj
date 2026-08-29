(ns resolver-sim.benchmark.legacy-authorisation-input-capture
  "P1r0b exact historical governed-authority evaluator-input capture.
   A capture proves only byte/data identity of the raw input supplied to the(ns resolver-sim.benchmark.legacy-authorisation-input-capture)
   historical evaluator; it never claims that the input is authorised."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "governed-authority-legacy-input-capture.v1")
(def ^:const kind :legacy-exact-input-capture.v1)
(def ^:const domain :governed-authority-legacy-input-capture-v1)
(def fields #{:artifact/schema :authorisation-input/kind :authorisation-input/raw})

(defn capture-root [capture]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (dissoc capture :authorisation-input/root))))

(defn validate-capture [capture]
  (let [errors (cond-> []
                 (not (map? capture)) (conj "capture must be a map")
                 (and (map? capture) (not= schema (:artifact/schema capture))) (conj "invalid capture schema")
                 (and (map? capture) (not= kind (:authorisation-input/kind capture))) (conj "invalid capture kind")
                 (and (map? capture) (not= (conj fields :authorisation-input/root) (set (keys capture)))) (conj "capture has invalid shape")
                 (and (map? capture) (not (map? (:authorisation-input/raw capture)))) (conj "raw authorisation must be a map")
                 (and (map? capture) (not= (:authorisation-input/root capture) (capture-root capture))) (conj "capture root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-capture [raw-authorisation]
  (let [base {:artifact/schema schema :authorisation-input/kind kind
              :authorisation-input/raw raw-authorisation}
        capture (assoc base :authorisation-input/root (capture-root base))]
    (when-not (:valid? (validate-capture capture))
      (throw (ex-info "legacy authorisation input capture is invalid" (validate-capture capture))))
    capture))

(defn replay
  "Replay exact historical raw input through caller-selected existing evaluator.
   The evaluator is supplied by the caller only as execution plumbing; capture
   validation never treats it as a semantic identity."
  [capture evaluate]
  (when-not (:valid? (validate-capture capture))
    (throw (ex-info "legacy authorisation input capture is invalid" {:reason :invalid-legacy-input-capture})))
  (evaluate (:authorisation-input/raw capture)))
