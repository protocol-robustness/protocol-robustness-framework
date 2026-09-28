(ns resolver-sim.allocation.source-bound-entitlement-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.source-bound-entitlement :as sut]
            [resolver-sim.hash.canonical :as hc]))

(def fixture-path "etc/conformance/vectors/source-bound-entitlement-v1.json")

(defn fixture []
  (json/read-str (slurp fixture-path) :key-fn keyword))

(defn materialize [base transform]
  (case transform
    "identity" base
    "rounding-7-of-10"
    (-> base
        (assoc-in [:realized-statement-input :available] "7")
        (assoc-in [:realized-statement-input :requested]
                  {:claim-c "2" :claim-a "5" :claim-b "3"})
        (assoc :economic-reserve-state
               {:gross-balance "10"
                :reserved-before "2"
                :protected-capital "1"
                :available-amount "7"
                :availability-revision "17"}))
    "reverse-all-input-rows"
    (-> base
        (update-in [:realized-statement-input :allocation-context :claimants]
                   #(vec (reverse %)))
        (update-in [:realized-statement-input :allocation-context :outcomes 0 :allocations]
                   #(vec (reverse %)))
        (update :beneficiary-mapping #(vec (reverse %)))
        (update :openings #(vec (reverse %))))
    "replace-claim-b-salt"
    (update base :openings
            (fn [rows]
              (mapv #(if (= "claim-b" (:claim-id %))
                       (assoc % :salt "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                       %)
                    rows)))
    "replace-claim-b-beneficiary"
    (-> base
        (update :beneficiary-mapping
                (fn [rows]
                  (mapv #(if (= "claim-b" (:claim-id %))
                           (assoc % :beneficiary "0x4444444444444444444444444444444444444444")
                           %)
                        rows)))
        (update-in [:realized-statement-input :allocation-context :claimants]
                   (fn [rows]
                     (mapv #(if (= "claim-b" (:claim-id %))
                              (assoc % :economic-owner-id
                                     "0x4444444444444444444444444444444444444444")
                              %)
                           rows))))
    "duplicate-claim-a-mapping"
    (update base :beneficiary-mapping
            (fn [rows]
              (conj (vec rows)
                    {:claim-id "claim-a"
                     :beneficiary "0x5555555555555555555555555555555555555555"})))
    (throw (ex-info "unknown fixture transform" {:transform transform}))))

(deftest cross-language-conformance-vectors
  (let [{:keys [base-input vectors expected]} (fixture)]
    (doseq [{:keys [id transform expected-status expected-error]} vectors]
      (testing id
        (let [input (materialize base-input transform)]
          (if (= "rejected" expected-status)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  (re-pattern expected-error)
                                  (sut/derive input)))
            (is (= (get expected (keyword id))
                   (sut/fixture-projection (sut/derive input))))))))))

(deftest ordering-salt-and-beneficiary-properties
  (let [base (:base-input (fixture))
        a (sut/derive base)
        reordered (sut/derive (materialize base "reverse-all-input-rows"))
        salted (sut/derive (materialize base "replace-claim-b-salt"))
        substituted (sut/derive (materialize base "replace-claim-b-beneficiary"))
        canonical-keys [:realized-statement-root :allocation-binding-root
                        :canonical-entitlement-set-root :allocation-claim-root
                        :entitlement-count :entitlement-total]]
    (is (= ["claim-a" "claim-b" "claim-c"]
           (mapv :claim/id (get-in a [:entitlement-set :entitlements]))))
    (is (= (select-keys a canonical-keys) (select-keys reordered canonical-keys)))
    (is (= (:public-values a) (:public-values reordered)))
    (is (= (select-keys a canonical-keys) (select-keys salted canonical-keys)))
    (is (not= (:evm-entitlement-commitments a) (:evm-entitlement-commitments salted)))
    (is (not= (:evm-entitlement-table-root a) (:evm-entitlement-table-root salted)))
    (is (not= (:canonical-entitlement-set-root a)
              (:canonical-entitlement-set-root substituted)))
    (is (not= (:allocation-claim-root a) (:allocation-claim-root substituted)))
    (is (not= (:evm-entitlement-table-root a) (:evm-entitlement-table-root substituted)))))

(deftest zero-rows-are-not-execution-entitlements
  (let [base (:base-input (fixture))
        input (-> base
                  (assoc-in [:realized-statement-input :requested :claim-c] "0")
                  (assoc-in [:realized-statement-input :available] "56")
                  (assoc :economic-reserve-state
                         {:gross-balance "86"
                          :reserved-before "20"
                          :protected-capital "10"
                          :available-amount "56"
                          :availability-revision "17"})
                  (update :beneficiary-mapping
                          #(vec (remove (fn [row] (= "claim-c" (:claim-id row))) %)))
                  (update :openings
                          #(vec (remove (fn [row] (= "claim-c" (:claim-id row))) %))))
        result (sut/derive input)]
    (is (= 2 (:entitlement-count result)))
    (is (= 56N (:entitlement-total result)))
    (is (= ["claim-a" "claim-b"]
           (mapv :claim/id (get-in result [:entitlement-set :entitlements]))))))

(deftest authority-substitution-properties
  (let [base (:base-input (fixture))
        original (sut/derive base)
        changed-operation
        (sut/derive (assoc-in base [:prepared-operation :operation-id]
                              "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        changed-application
        (sut/derive (assoc base :reserve-binding-root
                           "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"))
        changed-profile (sut/derive (assoc-in base [:prepared-operation :beneficiary-profile]
                                              "immutable-address-v1"))
        changed-amount (sut/derive (materialize base "rounding-7-of-10"))]
    (doseq [changed [changed-operation changed-application changed-profile changed-amount]]
      (is (not= (:canonical-entitlement-set-root original)
                (:canonical-entitlement-set-root changed)))
      (is (not= (:allocation-claim-root original) (:allocation-claim-root changed)))
      (is (not= (:evm-entitlement-table-root original)
                (:evm-entitlement-table-root changed))))))

(deftest duplicate-openings-are-rejected
  (let [base (:base-input (fixture))
        duplicate (update base :openings conj (first (:openings base)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"duplicate opening claim identity"
                          (sut/derive duplicate)))))

(deftest downstream-beneficiary-remapping-is-rejected
  (let [base (:base-input (fixture))
        remapped (update base :beneficiary-mapping
                         (fn [rows]
                           (mapv #(if (= "claim-b" (:claim-id %))
                                    (assoc % :beneficiary
                                           "0x4444444444444444444444444444444444444444")
                                    %)
                                 rows)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"beneficiary must match allocation-context economic owner"
                          (sut/derive remapped)))))

(deftest downstream-profile-remapping-is-rejected
  (let [base (:base-input (fixture))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/derive (assoc base :beneficiary-profile "immutable-address-v1"))))))

(deftest public-values-layout-and-schema-word
  (let [result (sut/derive (:base-input (fixture)))
        words (:public-words result)]
    (is (= 9 (count words)))
    (is (= (* 2 sut/public-values-length) (count (:public-values result))))
    (is (= (:schema-word result) (first words)))
    (is (= (apply str words) (:public-values result)))
    (is (= (format "%064x" (biginteger (:entitlement-count result))) (nth words 6)))
    (is (= (format "%064x" (biginteger (:entitlement-total result))) (nth words 7)))))

(deftest descriptor-scheme-fields-are-root-bound
  (let [descriptor sut/representation-descriptor
        root (hc/domain-hash :entitlement-representation-descriptor-v1 descriptor)
        changed (assoc descriptor :uint-word-scheme "different-scheme-v1")]
    (is (contains? descriptor :commitment-field-types))
    (is (contains? descriptor :claim-leaf-scheme))
    (is (contains? descriptor :table-summary-scheme))
    (is (not= root
              (hc/domain-hash :entitlement-representation-descriptor-v1 changed)))))
