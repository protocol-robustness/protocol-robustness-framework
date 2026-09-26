(ns resolver-sim.research.anvil-executor
  "Fresh local Anvil execution adapter for research jobs.

   This is observed local execution, not remote attestation, confidentiality,
   or malicious-host protection.  It composes V1 declarations by root and
   emits successor observation/receipt artifacts rather than changing their
   declaration-only meaning."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-environment :as environment])
  (:import [java.net URI ServerSocket]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:const observation-schema "research-execution-environment-observation.v1")
(def ^:const receipt-schema "research-anvil-execution-receipt.v1")
(def ^:const validation-schema "research-observed-fixed-validation-receipt.v1")
(def ^:const fixed-checks
  [:declared-environment-bound :observed-environment-bound :chain-id-matches
   :tool-version-matches :contract-code-matches :prestate-matches
   :action-matches :poststate-observed :semantic-result-bound
   :dependency-closure-bound :control-state-consistent])

(def ^:private fixed-mnemonic
  "test test test test test test test test test test test junk")

(defn- root [domain x] (hash-ref/sha256-ref (hc/domain-hash domain x)))
(defn- root? [x] (hash-ref/valid-sha256-ref? x))
(defn runtime-code-root [hex-code] (root :anvil-runtime-code-v1 (str/lower-case hex-code)))
(defn state-root [projection] (root :anvil-state-projection-v1 projection))
(defn action-root [action] (root :anvil-executed-action-v1 action))
(defn semantic-result-root [result] (root :anvil-semantic-result-v1 result))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)] (.getLocalPort socket)))

(defn- rpc
  [url id method params]
  (let [body (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params})
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 3))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body)) .build)
        response (.send (HttpClient/newHttpClient) request
                        (HttpResponse$BodyHandlers/ofString))
        value (json/read-str (.body response))]
    (when-let [error (get value "error")]
      (throw (ex-info "Anvil JSON-RPC error" {:method method :error error})))
    (get value "result")))

(defn- wait-rpc! [url process]
  (loop [n 0]
    (let [result (try (rpc url 1 "web3_clientVersion" [])
                      (catch Exception _ nil))]
      (cond
        (string? result) result
        (or (>= n 100) (not (.isAlive process)))
        (throw (ex-info "Anvil RPC never became reachable" {:cause :remote-worker-unreachable}))
        :else (do (Thread/sleep 25) (recur (inc n)))))))

(defn- quantity [n] (str "0x" (Long/toHexString n)))

(defn- observe-projection [url chain-id code-identities balances]
  (let [codes (mapv (fn [{:keys [deployment/address deployment/code-root]}]
                      (let [code (rpc url 2 "eth_getCode" [address "latest"])]
                        {:deployment/address address
                         :deployment/code-root (runtime-code-root code)
                         :declared-code-root code-root}))
                    code-identities)
        balance-values (mapv (fn [address]
                               {:balance/address address
                                :balance/wei (rpc url 3 "eth_getBalance" [address "latest"])})
                             (sort balances))]
    {:projection/chain-id chain-id
     :projection/code-identities (vec (sort-by :deployment/address codes))
     :projection/balances balance-values}))

(defn observation-root [x]
  (root :research-execution-environment-observation-v1
        (dissoc x :research-execution-environment-observation/root)))
(defn receipt-root [x]
  (root :research-anvil-execution-receipt-v1
        (dissoc x :research-anvil-execution-receipt/root)))
(defn validation-root [x]
  (root :research-observed-fixed-validation-receipt-v1
        (dissoc x :research-observed-fixed-validation-receipt/root)))

(defn valid-observation? [x]
  (and (= observation-schema (:artifact/schema x))
       (= :observed-local-execution (:observation/assurance x))
       (every? root? [(:research-execution-environment/root x)
                      (:research-execution-environment-observation/root x)])
       (= (:research-execution-environment-observation/root x)
          (observation-root x))))

(defn valid-receipt? [x]
  (and (= receipt-schema (:artifact/schema x))
       (= :observed-local-execution (:execution/assurance x))
       (= :success (:execution/transaction-status x))
       (every? root? (map x [:research-execution-job/root
                             :research-run-assignment/root
                             :runner-participation/root :runner/reference
                             :research-execution-environment/root
                             :research-execution-environment-observation/root
                             :resolved-research-dependency-set/root
                             :research-implementation-identity/root
                             :environment-before/root :executed-action/root
                             :environment-after/root :semantic-result/root
                             :research-anvil-execution-receipt/root]))
       (keyword? (:runner/site-id x))
       (= (:research-anvil-execution-receipt/root x) (receipt-root x))))

(defn valid-fixed-validation? [x]
  (and (= validation-schema (:artifact/schema x))
       (= :passed (:validation/status x))
       (= fixed-checks (mapv :check/id (:validation/checks x)))
       (every? #(= :pass (:check/status %)) (:validation/checks x))
       (root? (:research-anvil-execution-receipt/root x))
       (= (:research-observed-fixed-validation-receipt/root x) (validation-root x))))

(defn- anvil-version []
  (let [out (-> (ProcessBuilder. ^java.util.List ["anvil" "--version"])
                (.redirectErrorStream true) .start .getInputStream slurp)]
    (second (re-find #"Version: ([^\s]+)" out))))

(defn- start-anvil! [chain-id]
  (let [port (free-port)
        process (-> (ProcessBuilder.
                     ^java.util.List ["anvil" "--host" "127.0.0.1" "--port" (str port)
                                      "--chain-id" (str chain-id) "--timestamp" "0"
                                      "--mnemonic" fixed-mnemonic "--quiet"])
                    (.redirectErrorStream true) .start)]
    {:process process :url (str "http://127.0.0.1:" port)}))

(defn- stop! [^Process process]
  (when process
    (.destroy process)
    (when (.isAlive process) (.destroyForcibly process))))

(defn- receipt! [url tx]
  (loop [n 0]
    (let [r (rpc url 8 "eth_getTransactionReceipt" [tx])]
      (if r r
          (if (> n 100) (throw (ex-info "Transaction receipt timeout" {:cause :transfer-timeout}))
              (do (Thread/sleep 20) (recur (inc n))))))))

(defn execute!
  "Execute one actual value-transfer action against a fresh isolated Anvil.

   Required `context` keys are canonical artifacts/roots already produced by
   the research architecture plus `:action` (`:from`, `:to`, `:value-wei`,
   optional `:data`) and `:expected-action-root`.  Returned runtime fields are
   deliberately outside all returned canonical roots."
  [{:keys [job assignment runner-participation execution-environment dependency-manifest
           resolved-dependencies implementation-root action expected-action-root
           expected-prestate-root expected-poststate-root cancellation-stage runtime-chain-id]
    :or {cancellation-stage :none}}]
  (if (= cancellation-stage :before-launch)
    {:execution/status :cancelled :process/launched? false :control/cutpoint :before-launch}
    (do
      (when-not (:valid? (environment/validate-environment execution-environment))
        (throw (ex-info "Invalid declared environment" {})))
      (when-not (and (:valid? (dependency/validate-manifest dependency-manifest))
                     (dependency/valid-resolved-set? resolved-dependencies))
        (throw (ex-info "Invalid dependency closure" {:cause :dependency-mismatch})))
      (when (some #(= :sealed-to-execution-environment (:dependency/visibility %))
                  (:dependencies dependency-manifest))
        (throw (ex-info "Sealed dependencies require a confidential executor"
                        {:cause :sealed-dependency-unsupported})))
      (let [declared-version (get-in execution-environment [:tool/identity :tool/version])
            actual-version (anvil-version)]
        (when-not (= declared-version actual-version)
          (throw (ex-info "Observed Anvil version differs from declaration"
                          {:cause :environment-version-mismatch :declared declared-version :observed actual-version})))
        (let [{:keys [process url]} (start-anvil! (or runtime-chain-id (:evm/chain-id execution-environment)))]
          (try
            (let [client-version (wait-rpc! url process)
                  chain-hex (rpc url 4 "eth_chainId" [])
                  observed-chain (Long/parseLong (subs chain-hex 2) 16)
                  _ (when-not (= observed-chain (:evm/chain-id execution-environment))
                      (throw (ex-info "Observed chain id differs" {:cause :chain-id-mismatch})))
                  accounts (rpc url 5 "eth_accounts" [])
                  from (or (:from action) (first accounts))
                  to (or (:to action) (second accounts))
                  action (assoc action :from from :to to :data (or (:data action) "0x"))
                  actual-action-root (action-root action)
                  _ (when-not (= expected-action-root actual-action-root)
                      (throw (ex-info "Declared action differs from executed action" {:cause :action-mismatch})))
                  monitored (vec (sort (set [from to])))
                  pre (observe-projection url observed-chain (:deployment/code-identities execution-environment) monitored)
                  pre-root (state-root pre)
                  mismatched-code (seq (filter #(not= (:deployment/code-root %) (:declared-code-root %))
                                               (:projection/code-identities pre)))
                  _ (when mismatched-code
                      (throw (ex-info "Observed runtime bytecode differs" {:cause :contract-code-mismatch})))
                  _ (when (and expected-prestate-root (not= expected-prestate-root pre-root))
                      (throw (ex-info "Observed pre-state differs" {:cause :execution-prestate-mismatch})))
                  observation-body {:artifact/schema observation-schema
                                    :research-execution-environment/root (:research-execution-environment/root execution-environment)
                                    :observation/assurance :observed-local-execution
                                    :tool/identity {:tool/name :anvil :tool/version actual-version}
                                    :evm/chain-id observed-chain
                                    :rpc/client-version client-version
                                    :observed-code-identities (:projection/code-identities pre)}
                  observation (assoc observation-body :research-execution-environment-observation/root
                                     (observation-root observation-body))]
              (if (= cancellation-stage :after-reachability)
                {:execution/status :cancelled :process/launched? true
                 :realized-reachability :rpc-reachable :control/cutpoint :before-action
                 :environment-observation observation}
                (let [tx (rpc url 6 "eth_sendTransaction"
                              [{"from" from "to" to "value" (quantity (:value-wei action))
                                "data" (:data action)}])
                      chain-receipt (receipt! url tx)
                      _ (when-not (= "0x1" (get chain-receipt "status"))
                          (throw (ex-info "Anvil action reverted" {:cause :task-execution-failed})))
                      post (observe-projection url observed-chain (:deployment/code-identities execution-environment) monitored)
                      post-root (state-root post)
                      _ (when (and expected-poststate-root (not= expected-poststate-root post-root))
                          (throw (ex-info "Observed post-state differs" {:cause :execution-poststate-mismatch})))
                      semantic {:semantic/operation :evm-value-transfer
                                :semantic/from from :semantic/to to
                                :semantic/value-wei (:value-wei action)
                                :semantic/post-balances (:projection/balances post)}
                      result-root (semantic-result-root semantic)
                      receipt-body {:artifact/schema receipt-schema
                                    :research-execution-job/root (:research-execution-job/root job)
                                    :research-run-assignment/root (:research-run-assignment/root assignment)
                                    :runner-participation/root (:runner-participation/root runner-participation)
                                    :runner/reference (:runner/reference runner-participation)
                                    :runner/site-id (:runner/site-id runner-participation)
                                    :research-execution-environment/root (:research-execution-environment/root execution-environment)
                                    :research-execution-environment/body execution-environment
                                    :research-execution-environment-observation/root (:research-execution-environment-observation/root observation)
                                    :research-execution-environment-observation/body observation
                                    :resolved-research-dependency-set/root (:resolved-research-dependency-set/root resolved-dependencies)
                                    :research-implementation-identity/root implementation-root
                                    :environment-before/root pre-root :executed-action/root actual-action-root
                                    :environment-after/root post-root :semantic-result/root result-root
                                    :execution/assurance :observed-local-execution
                                    :execution/transaction-status :success}
                      receipt (assoc receipt-body :research-anvil-execution-receipt/root (receipt-root receipt-body))
                      validation-body {:artifact/schema validation-schema
                                       :research-anvil-execution-receipt/root (:research-anvil-execution-receipt/root receipt)
                                       :validation/checks (mapv #(hash-map :check/id % :check/status :pass) fixed-checks)
                                       :validation/status :passed}
                      validation (assoc validation-body :research-observed-fixed-validation-receipt/root
                                        (validation-root validation-body))]
                  {:execution/status :complete :realized-reachability :rpc-action-completed
                   :environment-observation observation :pre-state pre :post-state post :semantic-result semantic
                   :receipt receipt :fixed-validation validation
             ;; Transaction hash is forensic runtime evidence, intentionally not in receipt root.
                   :runtime/transaction-hash tx :runtime/port (subs url (inc (.lastIndexOf url ":")))})))
            (finally (stop! process))))))))
