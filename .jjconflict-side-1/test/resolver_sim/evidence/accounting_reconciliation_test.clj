(ns resolver-sim.evidence.accounting-reconciliation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.accounting-reconciliation :as sut]))

(def token "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def reserve "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def allocator "0xcccccccccccccccccccccccccccccccccccccccc")
(def controller "0xdddddddddddddddddddddddddddddddddddddddd")
(def beneficiaries ["0x1111111111111111111111111111111111111111"
                    "0x2222222222222222222222222222222222222222"
                    "0x3333333333333333333333333333333333333333"])
(defn word [n] (str "0x" (format "%064x" n)))
(def operation (word 42))
(defn address-word [addr] (str "0x" (apply str (repeat 24 "0")) (subs addr 2)))
(defn event [kind actor indexed words]
  {"address" actor "topics" (into [(sut/event-topics kind)] indexed)
   "data" (str "0x" (apply str (map #(subs (word %) 2) words)))})
(defn group [block logs]
  (let [hash (word (+ 100 block)) bhash (word (+ 200 block))
        context {"transactionHash" hash "blockHash" bhash
                 "blockNumber" (str "0x" (format "%x" block)) "transactionIndex" "0x0"}]
    {"receipts" [(assoc context "status" "0x1" "logs"
                        (mapv (fn [i log]
                                (merge log context {"logIndex" (str "0x" (format "%x" i))}))
                              (range) logs))]}))
(defn fixture []
  {:manifest {"operationId" operation
              "contracts" {"token" token "reserve" reserve "allocator" allocator
                           "ratificationController" controller}}
   :expected-total 70 :expected-count 3
   :expected-members (mapv (fn [i b a] {:ordinal i :beneficiary b :amount a})
                           (range) beneficiaries [35 21 14])
   :broadcasts
   {:deploy (group 1 [(event :transfer token [(word 0) (address-word reserve)] [80])])
    :run (group 2 [(event :transfer token [(address-word reserve) (address-word allocator)] [70])
                   (event :funded reserve [operation (word 99)] [70 1])
                   (event :ratified controller [operation (word 88)] [70])])
    :activate (group 3 [(event :activated controller [operation (address-word controller)] [])])
    :withdrawal
    {"receipts" (vec (mapcat
                      (fn [i b a after mode observation]
                        (get (group (+ 4 i)
                                    (cond-> [(event :transfer token [(address-word allocator) (address-word b)] [a])
                                             (event :withdrawn allocator [operation (word i) (address-word b)]
                                                    [a mode observation after (inc i)])]
                                      (= i 2) (conj (event :closed allocator [operation] [70 3])))) "receipts"))
                      (range) beneficiaries [35 21 14] [35 56 70] [0 1 0] [0 1 2]))}}})

(deftest retained-log-arithmetic-is-distinct-from-storage-proof
  (let [r (sut/reconcile-records (fixture))]
    (is (every? #{:pass} (vals (:checks r))))
    (is (empty? (:errors r)))
    (is (= 70 (:claimed-amount-from-events r)))
    (is (= 3 (:claimed-count-from-events r)))
    (is (= 0 (:remaining-amount-from-events r)))
    (is (= [35 21 14] (mapv (:net-token-flows r) beneficiaries)))
    (is (= 0 (get (:net-token-flows r) allocator)))
    (is (= 10 (get (:net-token-flows r) reserve)))
    (is (false? (get-in r [:provenance :storage-liability-verified])))
    (is (false? (get-in r [:provenance :authenticated-chain-state])))
    (is (false? (get-in r [:provenance :absolute-opening-balances-assumed])))
    (is (= 2 (count (:gaps r))))))

(deftest pinned-event-topic-conventions
  (is (= "0x503a4da47e6dfaa4d9d257c0b01b7a7afe6a8b640a89b898122e14b73cf1f86a"
         (:withdrawn sut/event-topics)))
  (is (= "0xb415f1cf2cb910acb3843a8423b67029799f9ae041639856b3dce11c84efee08"
         (:closed sut/event-topics)))
  (is (= "0x3cca5f73b83021eb6d86ff1ae8f622db1784aaa2f1c564bad9f130fe2c506922"
         (:funded sut/event-topics))))

(deftest record-enumeration-does-not-control-chain-order
  (let [input (fixture)
        changed (-> input
                    (update :broadcasts (fn [b] (into (sorted-map-by #(compare %2 %1)) b)))
                    (update-in [:broadcasts :withdrawal "receipts"] #(vec (reverse %)))
                    (update-in [:broadcasts :run "receipts" 0 "logs"] #(vec (reverse %))))]
    (is (= (sut/reconcile-records input) (sut/reconcile-records changed)))))

(deftest substituted-context-and-duplicate-events-reject
  (doseq [input [(assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "blockHash"] (word 999))
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "transactionIndex"] "0x1")
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "removed"] true)
                 (update-in (fixture) [:broadcasts :withdrawal "receipts"] #(conj % (first %)))
                 (assoc-in (fixture) [:broadcasts :run "receipts" 0 "logs" 1 "topics" 1] (word 777))]]
    (is (seq (:errors (sut/reconcile-records input))))))

(deftest funding-withdrawal-and-closure-tampering-reject
  (doseq [input [(assoc-in (fixture) [:broadcasts :run "receipts" 0 "logs" 0 "data"] (word 69))
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 1 "data"]
                           (str "0x" (apply str (map #(subs (word %) 2) [35 0 0 36 1]))))
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 1 "logs" 1 "topics" 2] (word 0))
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 2 "logs" 2 "data"]
                           (str (word 70) (subs (word 2) 2)))
                 (assoc-in (fixture) [:expected-members 0 :beneficiary] (second beneficiaries))
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 1 "logs" 1 "data"]
                           (str "0x" (apply str (map #(subs (word %) 2) [21 1 2 56 2]))))]]
    (is (seq (:errors (sut/reconcile-records input))))))

(deftest malformed-data-and-address-words-reject
  (doseq [input [(assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "data"] "0x23")
                 (assoc-in (fixture) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "topics" 2]
                           (str "0x" (apply str (repeat 64 "f"))))]]
    (is (seq (:errors (sut/reconcile-records input))))))

(deftest missing-evidence-and-forged-labels-do-not-create-facts
  (let [input (assoc-in (fixture) [:broadcasts :withdrawal "verified"] true)
        incomplete (-> input (assoc-in [:broadcasts :activate "receipts"] [])
                       (update-in [:broadcasts :withdrawal "receipts" 2 "logs"] pop))
        r (sut/reconcile-records incomplete)]
    (is (= :not-observed (get-in r [:checks :funded-before-activated-before-claims])))
    (is (= :not-observed (get-in r [:checks :closure-event-and-zero-derived-liability])))
    (is (some #(= :missing-observation (:kind %)) (:gaps r)))
    (is (= :not-verifiable (get-in (sut/reconcile-records (dissoc input :expected-members))
                                   [:checks :withdrawal-members-match-derivation])))))
