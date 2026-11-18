(ns resolver-sim.protocols.sew.dispute-resolution-terminal-envelope
  "External immutable terminal binding for a derived dispute-resolution closure."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema :prf/dispute-resolution-terminal-envelope.v1)
(def ^:const domain :prf-dispute-resolution-terminal-envelope-v1)
(def ^:private root-key :terminal-envelope/hash)
(def ^:private required-fields
  #{:artifact/schema :terminal-reservation/hash :dispute-resolution/closure-root})

(defn envelope-payload [envelope] (dissoc envelope root-key))

(defn valid-envelope? [envelope]
  (and (map? envelope)
       (= (set (keys envelope)) (conj required-fields root-key))
       (= schema (:artifact/schema envelope))
       (every? #(ref/valid-sha256-ref? (get envelope %))
               [:terminal-reservation/hash :dispute-resolution/closure-root root-key])
       (= (get envelope root-key)
          (ref/sha256-ref
           (hc/domain-hash domain (hc/project-canonical-safe (envelope-payload envelope)))))))

(defn build-envelope [terminal-hash closure]
  (let [base {:artifact/schema schema
              :terminal-reservation/hash terminal-hash
              :dispute-resolution/closure-root (:closure/root closure)}
        envelope (assoc base root-key
                        (ref/sha256-ref (hc/domain-hash domain
                                                        (hc/project-canonical-safe base))))]
    (when-not (and (ref/valid-sha256-ref? (:closure/root closure)) (valid-envelope? envelope))
      (throw (ex-info "Invalid dispute-resolution terminal envelope"
                      {:reason :invalid-terminal-envelope})))
    envelope))
