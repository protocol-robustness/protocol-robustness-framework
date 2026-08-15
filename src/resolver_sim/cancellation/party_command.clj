(ns resolver-sim.cancellation.party-command
  "Signed command for the protocol party-cancellation authority mode."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as signed]))

(def schema-version "sew-party-cancellation-command.v1")
(def decision-domain "SEW_PARTY_CANCELLATION_COMMAND_V1")
(def authority-role :protocol-party-cancellation)

(defn command-root [command]
  (hash-ref/sha256-ref (hc/domain-hash decision-domain (dissoc command :command/root :signature))))
(defn command-errors [command]
  (vec (remove nil?
               [(when-not (map? command) :command/not-a-map)
                (when (and (map? command) (not= schema-version (:command/schema command))) :command/unsupported-schema)
                (when (and (map? command) (not= :cancel (:command/action command))) :command/unsupported-action)
                (when (and (map? command) (not (some? (:command/principal command)))) :command/missing-principal)
                (when (and (map? command) (not (hash-ref/valid-sha256-ref? (:operation/root command)))) :command/invalid-operation-root)
                (when (and (map? command) (not= (:command/root command) (command-root command))) :command/root-mismatch)])))
(defn valid-command? [command] (empty? (command-errors command)))
(defn verify-command [command trust-policy key->principal]
  (let [shape-errors (command-errors command)
        verified (if (empty? shape-errors)
                   (signed/verify-envelope command decision-domain trust-policy authority-role)
                   {:valid? false :reason :invalid-command-shape :errors shape-errors})
        key-id (:key-id verified)
        bound-principal (get key->principal key-id)]
    (cond
      (not (:valid? verified)) verified
      (nil? bound-principal) {:valid? false :reason :unbound-key-principal :key-id key-id}
      (not= bound-principal (:command/principal command)) {:valid? false :reason :key-principal-mismatch :key-id key-id}
      :else {:valid? true :key-id key-id :principal bound-principal})))
