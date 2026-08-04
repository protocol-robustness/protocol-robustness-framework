(ns resolver-sim.notebook-support.manifest.claims
  "Claim registry operations: load, validate, and query the falsifiable
  claim set from data/claims/sew-claims.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.notebook-support.manifest.schema :as schema]
            [resolver-sim.config.paths :as paths]))

(def ^:private default-path (paths/claims-file))

;; ── loading ───────────────────────────────────────────────────────────────────

(defn load-claims
  "Read and return claims from path (default: data/claims/sew-claims.edn).
  Invalid entries are logged and excluded — never throws."
  ([]      (load-claims default-path))
  ([path]
   (try
     (let [f (io/file path)]
       (if (.exists f)
         (->> (edn/read-string (slurp f))
              (filter (fn [c]
                        (if (schema/valid-claim? c)
                          true
                          (do (println (str "WARN: invalid claim " (:claim/id c)
                                            " — " (schema/explain-manifest c)))
                              false)))))
         (do (println (str "WARN: claims file not found: " path)) [])))
     (catch Exception e
       (println (str "WARN: could not load claims: " (.getMessage e)))
       []))))

;; ── queries ───────────────────────────────────────────────────────────────────

(defn claims-for-scenario
  "Return claims whose :validated-by list contains (or is a prefix-match of) scenario-path."
  [claims scenario-path]
  (filter (fn [c]
            (some #(str/includes? % scenario-path) (:validated-by c)))
          claims))

(defn unresolved-counterexamples
  "Return claims that have at least one open counterexample."
  [claims]
  (filter #(seq (:counterexamples %)) claims))

(defn by-status
  "Group claims by :status. Returns a map of status-keyword → [claim ...]."
  [claims]
  (group-by :status claims))

(defn not-evaluated
  "Return claims whose status is :not-evaluated."
  [claims]
  (filter #(= :not-evaluated (:status %)) claims))

(defn validated
  "Return claims whose status is :not-falsified."
  [claims]
  (filter #(= :not-falsified (:status %)) claims))

(defn status-summary
  "Return {:total N :not-falsified N :falsified N :not-evaluated N :inconclusive N}."
  [claims]
  (let [g (by-status claims)]
    {:total          (count claims)
     :not-falsified  (count (get g :not-falsified []))
     :falsified      (count (get g :falsified []))
     :not-evaluated  (count (get g :not-evaluated []))
     :inconclusive   (count (get g :inconclusive []))}))
