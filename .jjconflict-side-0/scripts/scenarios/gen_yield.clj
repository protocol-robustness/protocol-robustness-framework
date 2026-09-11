(ns scripts.scenarios.gen-yield
  "Generalized yield scenario generator.
   Creates yield scenarios based on structural templates and parameters."
  (:require [clojure.data.json :as json]))

(defn- template [id title params events expectations]
  {:scenario-id id
   :id (subs id 0 3)
   :schema-version "1.1"
   :title title
   :purpose "functional-test"
   :scenario-author "gen-yield"
   :protocol "yield-v1"
   :initial-block-time 1000000
   :protocol-params params
   :agents [{:id "0xOwnerA" :address "0xOwnerA" :role "provider"}
            {:id "governance" :address "governance" :role "governance"}]
   :events events
   :expectations expectations})

(defn- save-scenario [scenario]
  (let [path (str "scenarios/" (:scenario-id scenario) ".json")]
    (spit path (json/write-str scenario {:escape-slash false :indent true}))

    (println "Generated:" path)))

(defn -main []
  ;; Example: A basic shortfall scenario template
  (let [id "Y05_auto-generated-shortfall"
        params {:yield-profile "aave-v3" :token "USDC" :focus-owner-id "0xOwnerA"}
        events [{:seq 0 :time 1000000 :agent "0xOwnerA" :action "yield_deposit" :params {:amount 1000 :token "USDC"}}
                {:seq 1 :time 1100000 :agent "governance" :action "set-yield-risk" :params {:token "USDC" :shortfall {:available-ratio 0.4 :reason "auto-gen"}}}
                {:seq 2 :time 1200000 :agent "0xOwnerA" :action "yield_withdraw" :params {:token "USDC"}}]
        expectations {:metrics [{:name "yield/available-ratio" :value 0.4}]}]
    (save-scenario (template id "Auto-Generated Shortfall" params events expectations))))
