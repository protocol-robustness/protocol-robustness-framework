(ns resolver-sim.evidence.accounting-reconciliation
  "Reconcile retained ERC-20 and successor lifecycle events. This establishes
   arithmetic over recorded logs, not authenticated chain state or call traces."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.math BigInteger]
           [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def event-signatures
  {:transfer "Transfer(address,address,uint256)"
   :funded "RatifiedAndFunded(bytes32,bytes32,uint256,uint64)"
   :ratified "EntitlementRatificationFunded(bytes32,bytes32,uint256)"
   :activated "EntitlementOperationActivated(bytes32,address)"
   :withdrawn "EntitlementWithdrawn(bytes32,uint256,address,uint256,uint8,uint8,uint256,uint256)"
   :closed "EntitlementOperationClosed(bytes32,uint256,uint256)"})

(def event-topics
  (into {} (map (fn [[kind signature]]
                  [kind (str "0x" (hc/bytes->hex
                                   (.digest (Keccak$Digest256.) (.getBytes signature "UTF-8"))))]))
        event-signatures))

(def ^:private zero-address "0x0000000000000000000000000000000000000000")

(defn- lower [v] (when (string? v) (str/lower-case v)))

(defn- integer-hex [v]
  (when-not (and (string? v) (re-matches #"0x[0-9a-fA-F]+" v))
    (throw (ex-info "Malformed event integer" {})))
  (bigint (BigInteger. (subs v 2) 16)))

(defn- address-word [v]
  (when-not (and (string? v) (re-matches #"(?i)0x0{24}[0-9a-f]{40}" v))
    (throw (ex-info "Malformed event address word" {})))
  (str "0x" (subs (str/lower-case v) 26)))

(defn- data-words [log n]
  (let [data (get log "data")]
    (when-not (and (string? data)
                   (re-matches (re-pattern (str "(?i)0x[0-9a-f]{" (* 64 n) "}")) data))
      (throw (ex-info "Malformed static event data" {})))
    (mapv #(integer-hex (str "0x" (apply str %))) (partition 64 (subs data 2)))))

(defn- decode-event [kind receipt log]
  (let [topics (get log "topics")
        topic-count (case kind :transfer 3 :withdrawn 4 :closed 2 3)]
    (when-not (and (vector? topics) (= topic-count (count topics))
                   (every? #(and (string? %) (re-matches #"(?i)0x[0-9a-f]{64}" %)) topics)
                   (= (lower (get receipt "transactionHash")) (lower (get log "transactionHash")))
                   (= (lower (get receipt "blockHash")) (lower (get log "blockHash")))
                   (= (get receipt "blockNumber") (get log "blockNumber"))
                   (= (get receipt "transactionIndex") (get log "transactionIndex"))
                   (not (true? (get log "removed"))))
      (throw (ex-info "Invalid event shape or receipt context" {})))
    (merge
     {:kind kind :tx (lower (get receipt "transactionHash"))
      :block-hash (lower (get receipt "blockHash"))
      :block (integer-hex (get receipt "blockNumber"))
      :transaction-index (integer-hex (get receipt "transactionIndex"))
      :log-index (integer-hex (get log "logIndex"))}
     (case kind
       :transfer {:from (address-word (nth topics 1)) :to (address-word (nth topics 2))
                  :amount (first (data-words log 1))}
       :funded (let [[amount revision] (data-words log 2)]
                 {:operation-id (lower (nth topics 1)) :observation-root (lower (nth topics 2))
                  :amount amount :revision revision})
       :ratified {:operation-id (lower (nth topics 1)) :receipt-id (lower (nth topics 2))
                  :amount (first (data-words log 1))}
       :activated (do (data-words log 0)
                      {:operation-id (lower (nth topics 1)) :representation (address-word (nth topics 2))})
       :withdrawn (let [[amount mode observation amount-after count-after] (data-words log 5)]
                    (when-not (and (<= mode 1) (<= observation 2)
                                   (or (zero? mode) (= 1 observation)))
                      (throw (ex-info "Invalid withdrawal event enum" {})))
                    {:operation-id (lower (nth topics 1)) :ordinal (integer-hex (nth topics 2))
                     :beneficiary (address-word (nth topics 3)) :amount amount :required-mode mode
                     :proof-observation observation :claimed-amount-after amount-after
                     :claimed-count-after count-after})
       :closed (let [[amount count] (data-words log 2)]
                 {:operation-id (lower (nth topics 1)) :amount amount :count count})))))

(defn- ordered-events [manifest broadcasts]
  (let [actors {:transfer (lower (get-in manifest ["contracts" "token"]))
                :funded (lower (get-in manifest ["contracts" "reserve"]))
                :ratified (lower (get-in manifest ["contracts" "ratificationController"]))
                :activated (lower (get-in manifest ["contracts" "ratificationController"]))
                :withdrawn (lower (get-in manifest ["contracts" "allocator"]))
                :closed (lower (get-in manifest ["contracts" "allocator"]))}
        decoded (for [broadcast (vals broadcasts)
                      receipt (get broadcast "receipts")
                      log (get receipt "logs")
                      [kind actor] actors
                      :when (and actor (= actor (lower (get log "address")))
                                 (= (event-topics kind) (lower (first (get log "topics")))))]
                  (try
                    (when-not (= "0x1" (get receipt "status"))
                      (throw (ex-info "Event receipt was not successful" {})))
                    {:event (decode-event kind receipt log)}
                    (catch Exception e
                      {:error {:kind :conflicting-evidence :check :accounting-event-context
                               :tx (get receipt "transactionHash") :message (.getMessage e)}})))
        events (->> decoded (keep :event) (sort-by (juxt :block :transaction-index :log-index)) vec)
        positions (map (juxt :block :transaction-index :log-index) events)]
    {:events events
     :errors (cond-> (vec (keep :error decoded))
               (not= (count positions) (count (distinct positions)))
               (conj {:kind :conflicting-evidence :check :duplicate-accounting-event-position
                      :message "Retained events share a chain position"})
               (some #(> (count (set (map :block-hash %))) 1) (vals (group-by :block events)))
               (conj {:kind :conflicting-evidence :check :conflicting-accounting-block-hashes
                      :message "Retained events disagree on a block hash"}))}))

(defn reconcile-records
  "Decode event ABI and derive token net flows, funding and claim progression.
   No declared report status or script assertion establishes a positive result."
  [{:keys [manifest broadcasts expected-total expected-count expected-members]}]
  (let [{:keys [events errors]} (ordered-events manifest broadcasts)
        op (lower (get manifest "operationId"))
        token (lower (get-in manifest ["contracts" "token"]))
        reserve (lower (get-in manifest ["contracts" "reserve"]))
        allocator (lower (get-in manifest ["contracts" "allocator"]))
        transfers (filterv #(= :transfer (:kind %)) events)
        relevant (filterv #(and (:operation-id %) (= op (:operation-id %))) events)
        by-kind (group-by :kind relevant)
        withdrawals (get by-kind :withdrawn [])
        funding (get by-kind :funded [])
        ratifications (get by-kind :ratified [])
        activations (get by-kind :activated [])
        closed (get by-kind :closed [])
        funding-transfers (filterv #(and (= reserve (:from %)) (= allocator (:to %))) transfers)
        payouts (filterv #(= allocator (:from %)) transfers)
        balances (reduce (fn [result {:keys [from to amount]}]
                           (cond-> result
                             (not= from zero-address) (update from (fnil - 0N) amount)
                             (not= to zero-address) (update to (fnil + 0N) amount)))
                         (sorted-map) transfers)
        progression (reduce (fn [{:keys [amount count] :as state} e]
                              (let [next-amount (+ amount (:amount e)) next-count (inc count)]
                                {:amount next-amount :count next-count
                                 :valid? (and (:valid? state) (pos? (:amount e))
                                              (= next-amount (:claimed-amount-after e))
                                              (= next-count (:claimed-count-after e))
                                              (<= next-amount expected-total) (<= next-count expected-count))}))
                            {:amount 0N :count 0N :valid? true} withdrawals)
        transfer-matches? (and (= (count payouts) (count withdrawals))
                               (every? (fn [w]
                                         (= 1 (count (filter #(and (= (:tx w) (:tx %))
                                                                   (= (:beneficiary w) (:to %))
                                                                   (= (:amount w) (:amount %))
                                                                   (< (:log-index %) (:log-index w))) payouts)))) withdrawals))
        position (juxt :block :transaction-index :log-index)
        observed (fn [items predicate] (if (seq items) (if predicate :pass :fail) :not-observed))
        checks {:reserve-funding-events
                (if-not (every? seq [funding ratifications funding-transfers]) :not-observed
                        (observed funding (and (= 1 (count funding)) (= 1 (count ratifications))
                                               (= 1 (count funding-transfers))
                                               (= expected-total (:amount (first funding)) (:amount (first ratifications))
                                                  (:amount (first funding-transfers)))
                                               (= (:tx (first funding)) (:tx (first ratifications))
                                                  (:tx (first funding-transfers))))))
                :funded-before-activated-before-claims
                (if-not (every? seq [funding activations withdrawals]) :not-observed
                        (observed withdrawals (and (= 1 (count funding)) (= 1 (count activations))
                                                   (neg? (compare (position (first funding)) (position (first activations))))
                                                   (every? #(neg? (compare (position (first activations)) (position %))) withdrawals))))
                :withdrawal-transfers-and-progression
                (observed withdrawals (and (:valid? progression) transfer-matches?
                                           (= expected-count (count withdrawals))
                                           (= (count withdrawals) (count (distinct (map :ordinal withdrawals))))
                                           (every? #(< (:ordinal %) expected-count) withdrawals)))
                :withdrawal-members-match-derivation
                (if (nil? expected-members) :not-verifiable
                    (observed withdrawals
                              (= (sort-by :ordinal expected-members)
                                 (sort-by :ordinal (map #(select-keys % [:ordinal :beneficiary :amount]) withdrawals)))))
                :closure-event-and-zero-derived-liability
                (observed closed (and (= 1 (count closed)) (= expected-total (:amount progression) (:amount (first closed)))
                                      (= expected-count (:count progression) (:count (first closed)))
                                      (= (:tx (last withdrawals)) (:tx (first closed)))
                                      (pos? (compare (position (first closed)) (position (last withdrawals))))))}
        failed (for [[check status] checks :when (= :fail status)]
                 {:kind :conflicting-evidence :check check :message (str "Event accounting failed: " (name check))})
        foreign (filter #(and (:operation-id %) (not= op (:operation-id %))) events)]
    {:profile "successor-entitlement-retained-events.v0"
     :event-abi event-signatures :token token :operation-id op
     :events events :checks checks
     :errors (vec (concat errors failed
                          (when (seq foreign)
                            [{:kind :conflicting-evidence :check :accounting-operation-binding
                              :message "Lifecycle events target a different operation"}])))
     :gaps (vec (concat
                 (for [[check status] checks :when (#{:not-observed :not-verifiable} status)]
                   {:kind (if (= :not-verifiable status) :unverified-claim :missing-observation)
                    :status status :artifact (str "event accounting: " (name check))
                    :reason "Required event or independently derived member input is unavailable"})
                 [{:kind :missing-observation :status :not-observed
                   :artifact "transaction-level token balance observations and complete transfer coverage"
                   :reason "Net flows over retained logs do not authenticate opening/closing balances or establish receipt completeness"}
                  {:kind :missing-observation :status :not-observed
                   :artifact "historical allocator storage and consumed-claim state"
                   :reason "Closure events derive a zero remaining amount, but final allocator storage and bitmaps were not retained"}]))
     :net-token-flows balances
     :claimed-amount-from-events (:amount progression) :claimed-count-from-events (:count progression)
     :remaining-amount-from-events (- expected-total (:amount progression))
     :recorded-payout-total (reduce + 0N (map :amount payouts))
     :provenance {:level :retained-local-event-reconciliation
                  :authenticated-chain-state false :transaction-traces-verified false
                  :storage-liability-verified false :absolute-opening-balances-assumed false}}))
