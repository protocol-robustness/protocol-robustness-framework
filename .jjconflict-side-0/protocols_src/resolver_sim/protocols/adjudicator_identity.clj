(ns resolver-sim.protocols.adjudicator-identity
  "Protocol-neutral, identity-only adjudicator projection."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "prf/adjudicator-identity.v1")
(def domain :prf-adjudicator-identity-v1)
(def ^:private root-key :adjudicator/root)
(def ^:private fields #{:artifact/schema :adjudicator/type :adjudicator/principal :protocol-instance/root})
(defn build-adjudicator-identity [{:keys [adjudicator/type adjudicator/principal protocol-instance/root] :as x}]
  (when-not (and (keyword? type) (string? principal) (seq principal) (ref/valid-sha256-ref? root))
    (throw (ex-info "Invalid adjudicator identity" {:type :invalid-adjudicator-identity})))
  (let [base {:artifact/schema schema :adjudicator/type type :adjudicator/principal principal :protocol-instance/root root}]
    (assoc base root-key (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe base))))))
(defn validate-adjudicator-identity [x]
  (let [expected (when (and (map? x) (= fields (disj (set (keys x)) root-key)))
                   (try (build-adjudicator-identity x) (catch Exception _ nil)))]
    {:valid? (= x expected)}))
