(require '[clojure.edn :as edn]
         '[resolver-sim.allocation.bounded-source-snapshot :as bounded])

(def package-path
  (or (first *command-line-args*)
      "examples/partial-fill/pro-95-base-sepolia-demo/stage-b-demo-package.edn"))
(def package (edn/read-string (slurp package-path)))
(def trust (:trust package))
(def qualification
  (bounded/qualify-stage-b-inputs
   {:snapshot (:snapshot package)
    :source-trust (:source trust)
    :policy (:policy package)
    :policy-trust (:policy trust)
    :beneficiaries (:beneficiaries package)
    :beneficiary-trust (:beneficiary trust)
    :funding (:funding package)
    :funding-trust (:funding trust)
    :allocation (:allocation package)}))
(def request-events
  (filter #(= :request/open (:event/kind %))
          (get-in package [:snapshot :snapshot/events])))
(def requested-total
  (reduce + (map #(get-in % [:event/payload :requested-units]) request-events)))
(def available
  (get-in package [:snapshot :snapshot/events 4 :event/payload :available-units]))
(def policy-rules
  (get-in package [:policy :binding/subject :policy/rules]))
(def expected-allocation (:allocation/expected-units policy-rules))
(def material-limits
  [:synthetic-inputs :generated-demo-keys :bundled-demo-trust-keys
   :no-production-source-authentication :no-funding-execution
   :no-base-sepolia-contract-deployment :no-public-chain-transaction
   :not-pre-mainnet-release])

(when-not (= :demonstration-only-not-pre-mainnet-release (:demo/classification package))
  (throw (ex-info "package is not explicitly demonstration-only" {})))
(when-not (= {:name "Base Sepolia" :chain-id 84532} (:demo/network package))
  (throw (ex-info "package network identity is not Base Sepolia" {})))
(when-not (and (false? (:demo/uses-real-source package))
               (false? (:demo/uses-real-token package))
               (false? (:demo/contract-deployed package))
               (false? (:demo/transaction-submitted package))
               (= material-limits (:demo/limitations package)))
  (throw (ex-info "package scope or demonstration limitations changed" {})))
(when-not (:valid? qualification)
  (throw (ex-info "signed Stage B demo bindings failed" {:result qualification})))
(when-not (and (= 100 requested-total)
               (= 70 available)
               (= expected-allocation (:allocation package))
               (= 70 (reduce + (vals (:allocation package))))
               (= 30 (- requested-total available)))
  (throw (ex-info "demo request, available reserve, or allocation arithmetic mismatch"
                  {:requested requested-total
                   :available available
                   :expected expected-allocation
                   :actual (:allocation package)})))

(println (pr-str {:stage-b-envelope-verification (:status qualification)
                  :requested-units requested-total
                  :available-demo-units available
                  :allocation (:allocation package)
                  :unmet-requested-units (- requested-total available)
                  :demo-classification (:demo/classification package)
                  :stage-c-status :not-run
                  :public-chain-qualification :not-qualified}))
