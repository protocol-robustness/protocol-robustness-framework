(ns resolver-sim.network.checkpoint-witness-fresh-process
  (:require [clojure.edn :as edn]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.network.checkpoint :as checkpoint])
  (:import [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64]))

(defn -main [package-path]
  (let [{:keys [checkpoint predecessor policy membership principal-id key-id
                private-key-base64 store-path]}
        (edn/read-string (slurp package-path))]
    (try
      (let [private-key (.generatePrivate (KeyFactory/getInstance "Ed25519")
                                          (PKCS8EncodedKeySpec.
                                           (.decode (Base64/getDecoder) ^String private-key-base64)))
            member (some #(when (and (= principal-id (:principal/id %))
                                     (= key-id (:key/id %))) %)
                         (:membership/members membership))
            context {:network/genesis-root (:network/genesis-root checkpoint)
                     :predecessor predecessor
                     :policy policy
                     :membership membership
                     :events []
                     :verify-event (fn [_] {:valid? true})
                     :verify-completeness (fn [_ roots _] {:valid? (empty? roots)})
                     :replay (fn [_ _ _ _]
                               {:valid? true
                                :dag-state-root (:checkpoint/dag-state-root checkpoint)
                                :frontier-roots (:checkpoint/frontier-roots checkpoint)
                                :resulting-policy-root (:checkpoint/resulting-policy-root checkpoint)
                                :resulting-membership-root (:checkpoint/resulting-membership-root checkpoint)
                                :resulting-epoch (:checkpoint/resulting-epoch checkpoint)
                                :state (:state predecessor)})}
            result (checkpoint/endorse-checkpoint!
                    checkpoint context
                    {:principal/id principal-id
                     :key/id key-id
                     :private-key private-key
                     :public-key (signing/parse-public-key-content (:key/public-key member))
                     :store-path store-path})]
        (println (pr-str result)))
      (catch clojure.lang.ExceptionInfo e
        (println (pr-str {:error (:reason (ex-data e))}))))))
