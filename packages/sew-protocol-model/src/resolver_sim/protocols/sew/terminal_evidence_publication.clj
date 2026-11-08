(ns resolver-sim.protocols.sew.terminal-evidence-publication
  "SEW-local immutable terminal publication and exact reachability mapping."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.dispute-resolution-closure :as closure]
            [resolver-sim.protocols.sew.dispute-resolution-terminal-envelope :as envelope]))

(def required-roles #{:staged-capture/hash :terminal-snapshot/hash :outcome-manifest/hash
                      :consumption-receipt/hash :execution-evidence/hash})
(def optional-role :effect-correlation/hash)
(def allowed-roles (conj required-roles optional-role))

(defn- publication-hash [x]
  (str "sha256:" (hc/domain-hash :terminal-evidence-publication
                                 (dissoc x :publication/hash))))

(defn valid-publication?
  [x]
  (let [objects (:published/objects x)
        outcome (:consumption/effect-outcome x)
        correlation? (contains? objects optional-role)
        required (if (contains? #{:produced :reversed} outcome)
                   (conj required-roles optional-role)
                   required-roles)
        version (:artifact/version x)
        envelope-hash (:terminal-envelope/hash x)]
    (and (map? x)
         (= :terminal-evidence-publication (:artifact/type x))
         (contains? #{1 2} version)
         (if (= 2 version)
           (ref/valid-sha256-ref? envelope-hash)
           (nil? envelope-hash))
         (map? objects)
         (= required (set (keys objects)))
         (every? ref/valid-sha256-ref? (vals objects))
         (ref/valid-sha256-ref? (:terminal-reservation/hash x))
         (string? (:public-authorisation/id x))
         (boolean (re-matches #"sha256:[0-9a-f]{64}" (:public-authorisation/scope-hash x)))
         (contains? #{:not-produced :produced :reversed} outcome)
         (or (not= outcome :not-produced) (not correlation?))
         (= (:publication/hash x) (publication-hash x)))))

(defn build-publication
  [fields]
  (let [base (assoc fields :artifact/type :terminal-evidence-publication :artifact/version 1)
        x (assoc base :publication/hash (publication-hash base))]
    (when-not (valid-publication? x)
      (throw (ex-info "Invalid terminal evidence publication" {:reason :invalid-publication})))
    x))

(defn build-publication-v2
  "Build a V2 publication that commits the immutable terminal envelope.
   The envelope, rather than this publication, remains the sole commitment to
   the dispute-resolution closure root."
  [fields terminal-envelope]
  (let [base (assoc fields
                    :artifact/type :terminal-evidence-publication
                    :artifact/version 2
                    :terminal-envelope/hash (:terminal-envelope/hash terminal-envelope))
        x (assoc base :publication/hash (publication-hash base))]
    (when-not (and (envelope/valid-envelope? terminal-envelope)
                   (= (:terminal-reservation/hash x)
                      (:terminal-reservation/hash terminal-envelope))
                   (valid-publication? x))
      (throw (ex-info "Invalid terminal evidence publication V2"
                      {:reason :invalid-publication-v2})))
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

(defn resolve-published-terminal
  "Follow the discovery-only reservation mapping through immutable artifacts.
   `artifact-resolver` receives a SHA-256 reference and returns its artifact."
  [mapping terminal-hash artifact-resolver]
  (let [publication-hash (get @mapping terminal-hash)
        publication (when publication-hash (artifact-resolver publication-hash))
        terminal-envelope (when publication
                            (artifact-resolver (:terminal-envelope/hash publication)))
        dispute-closure (when terminal-envelope
                          (artifact-resolver (:dispute-resolution/closure-root terminal-envelope)))]
    {:publication-hash publication-hash
     :publication publication
     :terminal-envelope terminal-envelope
     :dispute-closure dispute-closure}))

(defn validate-publication-against-terminal
  "Verify the V2 publication-to-envelope-to-closure chain from immutable
   artifacts. `closure-inputs` are the retained realized transition facts used
   to independently reconstruct the closure."
  [publication terminal-envelope dispute-closure closure-inputs]
  (let [closure-validation
        (when (and dispute-closure closure-inputs)
          (closure/validate-closure-against-transition dispute-closure closure-inputs))
        errors (cond-> []
                 (not (valid-publication? publication))
                 (conj :invalid-terminal-evidence-publication)
                 (not= 2 (:artifact/version publication))
                 (conj :terminal-envelope-binding-required)
                 (not (envelope/valid-envelope? terminal-envelope))
                 (conj :invalid-terminal-envelope)
                 (not= (:terminal-envelope/hash publication)
                       (:terminal-envelope/hash terminal-envelope))
                 (conj :terminal-envelope-mismatch)
                 (not= (:terminal-reservation/hash publication)
                       (:terminal-reservation/hash terminal-envelope))
                 (conj :terminal-reservation-mismatch)
                 (not= (:dispute-resolution/closure-root terminal-envelope)
                       (:closure/root dispute-closure))
                 (conj :dispute-resolution-closure-mismatch)
                 (nil? closure-validation)
                 (conj :missing-closure-reconstruction-inputs)
                 (and closure-validation (not (:valid? closure-validation)))
                 (into (:errors closure-validation)))]
    {:valid? (empty? errors) :errors errors}))

(defn publish-authenticated-terminal!
  "Publish an authenticated release/refund terminal only through a V2 immutable
   publication that binds the terminal envelope and its derived closure root."
  [mapping terminal-hash dispute-closure envelope-binding publication]
  (when-not (and (= 2 (:artifact/version publication))
                 (= (:terminal-envelope/hash publication)
                    (:terminal-envelope/hash envelope-binding))
                 (ref/valid-sha256-ref? (:closure/root dispute-closure))
                 (envelope/valid-envelope? envelope-binding)
                 (= terminal-hash (:terminal-reservation/hash envelope-binding))
                 (= (:closure/root dispute-closure)
                    (:dispute-resolution/closure-root envelope-binding)))
    (throw (ex-info "Authenticated terminal publication lacks a matching closure envelope"
                    {:reason :missing-or-mismatched-dispute-resolution-closure})))
  (publish-terminal! mapping terminal-hash publication))
