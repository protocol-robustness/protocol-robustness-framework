(ns resolver-sim.commands.protocol-finality
  "Read-only finality adapters over an explicitly supplied SEW world."
  (:require [resolver-sim.commands.document :as doc]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.financial.finality :as financial]
            [resolver-sim.protocols.sew.dispute-ruling-finality :as ruling]))

(defn- validate-request! [request verify?]
  (let [kind (:kind request :financial)
        required (cond-> #{:protocol :world :workflow-id}
                   verify? (conj :finality)
                   (= kind :ruling) (conj :ruling :authentication-verification))]
    (doc/closed! request required (cond-> #{:kind}
                                   (not verify?) (conj :ruling :authentication-verification)))
    (doc/require! (= :sew (:protocol request)) "Only :protocol :sew is supported")
    (doc/require! (contains? #{:financial :ruling} kind) "Unsupported finality kind")
    (doc/require! (and (map? (:world request))
                       (integer? (:workflow-id request))
                       (not (neg? (:workflow-id request)))
                       (types/valid-workflow-id? (:world request) (:workflow-id request)))
                  "World must contain the requested integer workflow-id")
    (when (= kind :financial)
      (doc/require! (not-any? #(contains? request %) [:ruling :authentication-verification])
                    "Ruling evidence requires :kind :ruling"))
    kind))

(defn- derive-result [{:keys [world workflow-id authentication-verification] :as request} kind]
  (if (= kind :financial)
    (financial/combine-finality world workflow-id)
    (ruling/derive-finality world workflow-id (:ruling request) authentication-verification)))

(defn inspect [opts]
  (doc/execute opts
    (fn [request]
      (let [kind (validate-request! request false)
            finality (derive-result request kind)]
        (doc/emit opts {:schema-version "protocol-finality-inspection.v1"
                        :protocol :sew :kind kind :workflow-id (:workflow-id request)
                        :authority :supplied-world
                        :chain-evidence :assumed-by-replay
                        :eligible? (some? finality) :finality finality}
                  (if (some? finality) 0 1))))))

(defn verify [opts]
  (doc/execute opts
    (fn [{:keys [world workflow-id authentication-verification finality] :as request}]
      (let [kind (validate-request! request true)
            result (if (= kind :financial)
                     (let [expected (derive-result request kind)]
                       {:valid? (= expected finality) :expected expected
                        :errors (if (= expected finality) [] [:finality-reconstruction-mismatch])})
                     (ruling/validate-finality-against-context
                      finality world workflow-id (:ruling request) authentication-verification))]
        (doc/emit opts (assoc result :schema-version "protocol-finality-verification.v1"
                              :authority :supplied-world :chain-evidence :assumed-by-replay)
                  (if (:valid? result) 0 1))))))
