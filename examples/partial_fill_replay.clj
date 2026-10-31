(ns examples.partial-fill-replay
  "Run with: clojure -M -m examples.partial-fill-replay"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.yield.strategic-execution :as execution]
            [resolver-sim.yield.strategic-partial-fill :as strategic])
  (:import [java.security KeyPairGenerator]))

(defn build-example
  "Create a local, signed two-player example. Keys are ephemeral demo keys.
   With stochastic? true, each declared branch has its own credit schedule."
  ([] (build-example false))
  ([stochastic?]
   (let [catalog (edn/read-string (slurp (io/resource "yield/partial-fill-recovery-game-fixtures.edn")))
         players [:alice :bob]
         rights {:alice :alice-right :bob :bob-right}
         source (:defaults catalog)
         recovery (if stochastic?
                    {:kind :stochastic-process :probability-model :explicit-probabilities
                     :flow-attribution :modeled-assumption
                     :probability-basis {:description "Demo scenarios, not measured frequencies"
                                         :source :explicit-scenario-assumption}
                     :outcomes [{:outcome/id :low :probability-bps 5000 :recovery-amount 5}
                                {:outcome/id :high :probability-bps 5000 :recovery-amount 10}]}
                    (:recovery-process source))
         model (-> source
                   (assoc :game/id "signed-replay-demo" :recovery-process recovery)
                   (update :players #(filterv (fn [p] (some #{(:player/id p)} players)) %))
                   (update :entitlements #(mapv (fn [e] (assoc e :amount 10)) (take 2 %)))
                   (update :rounds #(vector (-> (first %)
                                                (assoc :incoming (vec (vals rights))
                                                       :requested-by-entitlement (zipmap (vals rights) (repeat 10))))))
                   (update-in [:execution-model :parallelism :submissions] #(vec (take 2 %)))
                   (assoc-in [:terminal :horizon-round] 0)
                   (update-in [:terminal :continuation-value-by-player] #(select-keys % players))
                   (update-in [:payoff-parameters :by-player] #(select-keys % players)))
         keys (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
         public (apply str (map #(format "%02x" (bit-and 255 %))
                                (take-last 32 (.getEncoded (.getPublic keys)))))
         trust (fn [id role] {:trusted-keys [{:key/id id :key/public public :key/role role :key/status :active}]})
         model (-> model
                   (assoc :action-permission-claims
                          (mapv (fn [player]
                                  {:player/id player :action :claim-now :permission/status :permitted
                                   :source/root (execution/content-root :demo-permission-source.v1
                                                                       {:purpose :local-demo})}) players))
                   (strategic/sign-recovery-game-action-permissions (.getPrivate keys) :static-permissions)
                   (strategic/sign-recovery-game-assumptions (.getPrivate keys) :assumptions))
         prepared (strategic/prepare-recovery-game-model model)
         steps (mapv (fn [player]
                       {:round/index 0 :submission/id player :action :claim-now
                        :row {:row/id player :key player :owner/id player
                              :entitlement/id (rights player) :obligation-id player
                              :source-position-id player :owed 10 :weight 10 :cap 10}}) players)
         branches (if stochastic?
                    [{:branch/id :low :probability-bps 5000 :deposits [{:event/id :credit :round/index 0 :amount 5}]}
                     {:branch/id :high :probability-bps 5000 :deposits [{:event/id :credit :round/index 0 :amount 10}]}]
                    [{:branch/id :deterministic :probability-bps 10000
                      :deposits [{:event/id :credit :round/index 0 :amount 10}]}])
         execution-model {:schema-version "partial-fill-strategic-execution.v1"
                          :recovery-model/root (strategic/recovery-game-model-root model)
                          :ordering :fixed-public-order :cost-schedule :zero :initial-cash 0
                          :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                          :submission-order players :branches branches
                          :plans (into {} (map (fn [step] [(:submission/id step) {:claim [step]}]) steps))}
         executable (execution/prepare-execution-model prepared execution-model)
         choices (zipmap players (repeat :claim))
         sources (atom {})
         traces (into {}
                      (for [branch branches]
                        (let [initial (execution/initial-state executable)
                              credited (assoc initial :cash (get-in branch [:deposits 0 :amount]) :credited #{:credit})
                              result
                              (reduce (fn [{:keys [state assertions]} step]
                                        (let [player (:submission/id step)
                                              amount (min 10 (:cash state))
                                              decision (:decision (execution/allocate-owned-rows
                                                                   {:available (:cash state) :policy (:policy execution-model)
                                                                    :rows [(:row step)]}))
                                              grant (signed/sign-envelope
                                                     {:schema-version "partial-fill-state-permission.v1"
                                                      :permission/status :permitted :execution/root (:execution/root executable)
                                                      :player/id player :entitlement/id (rights player) :action :claim-now
                                                      :round/index 0 :pre-state/root (execution/state-root state)
                                                      :priority player :amount-limit 10 :cap-limit 10}
                                                     execution/permission-domain (.getPrivate keys) :permissions)
                                              root (execution/content-root :partial-fill-state-permission.v1 grant)
                                              post (-> state (update :cash - amount)
                                                       (update-in [:balances (rights player)] - amount)
                                                       (assoc-in [:payments player] [{:amount amount :round/index 0}])
                                                       (assoc-in [:submissions [(rights player) player]] {:step step :decision decision}))]
                                          (swap! sources assoc root grant)
                                          {:state post :assertions (conj assertions
                                                                        (merge (select-keys step [:round/index :submission/id :action])
                                                                               {:pre-state/root (execution/state-root state)
                                                                                :post-state/root (execution/state-root post)
                                                                                :permission/root root :decision decision}))}))
                                      {:state credited :assertions []} steps)]
                          [(:branch/id branch)
                           {:schema-version "partial-fill-execution-trace.v1" :branch/id (:branch/id branch)
                            :actions choices :execution/root (:execution/root executable)
                            :initial-state/root (execution/state-root initial)
                            :terminal-state/root (execution/state-root (:state result)) :steps (:assertions result)}])))]
     {:prepared prepared :model execution-model
      :static-permission-trust-policy
      (trust :static-permissions strategic/recovery-game-action-permission-role)
      :sign-permission (fn [grant]
                         (signed/sign-envelope (dissoc grant :signature) execution/permission-domain
                                               (.getPrivate keys) :permissions))
      :game {:actions (zipmap players (repeat [:claim]))
             :profiles [{:actions choices :traces-by-id traces}]}
      :opts {:require-validated-execution? true :require-authenticated-assumptions? true
             :assumption-trust-policy (trust :assumptions strategic/recovery-game-assumption-attestation-role)
             :permission-trust-policy (trust :permissions execution/permission-role)
             :permission-sources @sources}})))

(defn -main [& _]
  (try
    (let [{:keys [prepared model game opts]} (build-example)
          result (strategic/analyze-replayed-recovery-game-profiles prepared model game opts)]
      (prn (select-keys result [:status :equilibrium-concept :equilibria :execution-assurance
                                :utility-assumptions-authenticated? :execution-evidence/root])))
    (finally (shutdown-agents))))
