(ns resolver-sim.protocols.sew.terminal-evidence-publication
  "Sew-local immutable terminal publication and exact reachability mapping."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def required-roles #{:staged-capture/hash :terminal-snapshot/hash :outcome-manifest/hash
                      :consumption-receipt/hash :execution-evidence/hash})
(def optional-role :effect-correlation/hash)
(def allowed-roles (conj required-roles optional-role))

(defn- publication-hash [x]
  (str "sha256:" (hc/domain-hash :terminal-evidence-publication
                                  (dissoc x :publication/hash))))

(defn valid-publication? [x]
  (let [objects (:published/objects x)
        outcome (:consumption/effect-outcome x)
        correlation? (contains? objects optional-role)
        required (if (contains? #{:produced :reversed} outcome)
                   (conj required-roles optional-role) required-roles)]
    (and (map? x) (= :terminal-evidence-publication (:artifact/type x))
         (= 1 (:artifact/version x)) (map? objects)
         (= required (set (keys objects)))
         (every? ref/valid-sha256-ref? (vals objects))
         (ref/valid-sha256-ref? (:terminal-reservation/hash x))
         (string? (:public-authorisation/id x))
         (boolean (re-matches #"sha256:[0-9a-f]{64}" (:public-authorisation/scope-hash x)))
         (contains? #{:not-produced :produced :reversed} outcome)
         (or (not= outcome :not-produced) (not correlation?))
         (= (:publication/hash x) (publication-hash x)))))

(defn build-publication [fields]
  (let [base (assoc fields :artifact/type :terminal-evidence-publication :artifact/version 1)
        x (assoc base :publication/hash (publication-hash base))]
    (when-not (valid-publication? x)
      (throw (ex-info "Invalid terminal evidence publication" {:reason :invalid-publication})))
    x))

(defn publish-terminal! [mapping terminal-hash publication]
  (when-not (and (instance? clojure.lang.IAtom mapping) (valid-publication? publication)
                 (= terminal-hash (:terminal-reservation/hash publication)))
    (throw (ex-info "Invalid terminal publication request" {:reason :invalid-publication-request})))
  (let [target (:publication/hash publication)]
    (loop [] (let [m @mapping existing (get m terminal-hash)]
               (cond (= existing target) {:published? true :mode :resumed :publication-hash target}
                     existing {:published? false :reason :publication-conflict :existing existing}
                     (compare-and-set! mapping m (assoc m terminal-hash target))
                     {:published? true :mode :new :publication-hash target}
                     :else (recur))))))
