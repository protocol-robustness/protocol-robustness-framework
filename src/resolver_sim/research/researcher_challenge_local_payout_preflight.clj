(ns resolver-sim.research.researcher-challenge-local-payout-preflight
  "Fail-closed preflight for the local Challenge 001 ERC-20 demonstration."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.network.shortfall-pilot-adapter :as pilot-adapter]))

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(defn preflight
  [config-path profile-path history-path evidence-path challenge-root obligation-root
   researcher-public-path]
  (let [config (read-edn config-path)
        profile (read-edn profile-path)
        history (read-edn history-path)
        evidence (read-edn evidence-path)
        state (:state (pilot-adapter/replay config profile history evidence))
        researcher-root
        (challenges/researcher-reference
         (:network/root config)
         (challenges/canonical-public-key (slurp researcher-public-path)))
        challenge? (some #(= challenge-root (:artifact/root %)) (:challenges config))
        matching-reviews
        (filter #(= obligation-root
                    (get-in % [:obligation :researcher-reward-obligation/root]))
                (vals (:reviews state)))
        review (first matching-reviews)
        payout-records (get-in state [:payouts obligation-root] [])]
    (when-not (and (= "devnet" (namespace (:network/id config)))
                   (string/starts-with? (get-in config [:reward/policy :reward/currency] "")
                                        "TEST-")
                   challenge?
                   (= 1 (count matching-reviews))
                   (= :accepted (:decision review))
                   (= researcher-root (:researcher/root review))
                   (:incentive/eligible? review)
                   (empty? payout-records))
      (throw (ex-info "local demo requires one unpaid, eligible obligation in a TEST-* devnet"
                      {:reason :invalid-local-demo-history})))
    {:valid? true
     :configuration/root (:artifact/root config)
     :challenge/root challenge-root
     :researcher/root researcher-root
     :obligation/root obligation-root
     :history/root (:history/root state)
     :demo-only? true}))

(defn -main [& args]
  (when-not (= 7 (count args))
    (throw (ex-info "Usage: CONFIG PROFILE HISTORY EVIDENCE CHALLENGE_ROOT OBLIGATION_ROOT RESEARCHER_PUBLIC"
                    {:reason :invalid-arguments})))
  (prn (apply preflight args)))
