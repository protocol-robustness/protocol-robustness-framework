(ns resolver-sim.privacy.storage-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.privacy :as command]
            [resolver-sim.privacy.storage-query :as query]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.support.ed25519 :as fx]))

(defn sha [ch] (str "sha256:" (apply str (repeat 64 ch))))
(def subject-ref (sha "a"))
(def event-root (sha "b"))
(def second-event-root (sha "c"))
(def policy-root (sha "d"))
(def storage-key (fx/keypair :fixture-storage-observer))
(def subject-key (fx/keypair :fixture-data-subject))
(def storage-trust
  (fx/trust-policy storage-key :privacy-storage-observer :active))
(def subject-trust
  (update (fx/trust-policy subject-key :privacy-data-subject :active)
          :trusted-keys
          #(mapv (fn [entry] (assoc entry :key/subject-ref subject-ref)) %)))

(defn record
  ([root state sink]
   {:record/event-root root :record/data-category "transaction-metadata"
    :record/policy-root policy-root :record/sink sink :record/state state
    :record/created-at 100 :record/observed-at 150
    :record/retention-until 200}))

(defn inventory
  ([records]
   (inventory subject-ref records
              [{:coverage/sink :sealed-log :coverage/status :complete}
               {:coverage/sink :private-encrypted-bundle
                :coverage/status :partial}]))
  ([records coverage]
   (inventory subject-ref records coverage))
  ([inventory-subject-ref records coverage]
   (let [body {:storage-inventory/schema query/inventory-schema
               :storage-inventory/subject-ref inventory-subject-ref
               :storage-inventory/observed-at 150
               :storage-inventory/coverage coverage
               :storage-inventory/records records}
         rooted (assoc body :storage-inventory/root (query/inventory-root body))]
     (signed/sign-envelope rooted query/inventory-domain
                           (:private-key storage-key) (:key/id storage-key)))))

(defn authorization [scope target-ref authorized-subject-ref]
  (signed/sign-envelope
   {:query-authorization/schema query/authorization-schema
    :query-authorization/scope scope
    :query-authorization/subject-ref authorized-subject-ref
    :query-authorization/target-ref target-ref
    :query-authorization/issued-at 155
    :query-authorization/expires-at 250}
   query/authorization-domain (:private-key subject-key) (:key/id subject-key)))

(defn query-input
  ([scope target-ref records]
   (query-input scope target-ref (inventory records) {}))
  ([scope target-ref inventory overrides]
   (merge {:subject-ref subject-ref
           :event-root (when (= scope :event) target-ref)
           :inventory inventory
           :storage-trust-policy storage-trust
           :authorization (authorization scope target-ref subject-ref)
           :authorization-trust-policy subject-trust
           :now 160 :max-observation-age-seconds 20}
          overrides)))

(defn invoke [handler value]
  (let [file (java.io.File/createTempFile "privacy-query-" ".edn")]
    (try
      (spit file (pr-str value))
      (let [result (atom nil)
            output (with-out-str
                     (reset! result (handler {:input (.getAbsolutePath file)})))]
        {:result @result :output output})
      (finally (.delete file)))))

(deftest event-query-reports-only-fresh-signed-storage-observations
  (let [result (query/query-event
                (query-input :event event-root
                             [(record event-root :stored :sealed-log)]))
        row (first (:privacy-storage-query/records result))]
    (is (= :stored-observed (:privacy-storage-query/event-status result)))
    (is (= :verified-relative-to-caller-supplied-policy
           (:privacy-storage-query/storage-trust-status result)))
    (is (= :verified-relative-to-caller-supplied-policy
           (:privacy-storage-query/authorization-status result)))
    (is (= 200 (:retention-until row)))
    (is (= 40 (:seconds-until-retention row)))
    (is (= :not-resolved-by-storage-query
           (:privacy-storage-query/current-policy-status result)))
    (is (false? (:privacy-storage-query/usable? result)))
    (is (not (contains? result :privacy-storage-query/subject-ref)))))

(deftest wallet-query-is-subject-bound-and-aggregates-without-subject-reference
  (let [inventory (inventory [(record event-root :stored :sealed-log)
                              (record second-event-root :deleted
                                      :private-encrypted-bundle)])
        result (query/query-wallet
                (query-input :wallet subject-ref inventory {}))]
    (is (= :stored-observed (:privacy-storage-query/event-status result)))
    (is (= 2 (:privacy-storage-query/record-count result)))
    (is (= 2 (count (:privacy-storage-query/summary result))))
    (is (not (contains? result :privacy-storage-query/subject-ref)))
    (is (not (some #(contains? % :event-root)
                   (:privacy-storage-query/summary result)))))
  (testing "the trusted subject key must be bound to the queried reference"
    (let [other-ref (sha "e")
          result (query/query-wallet
                  (query-input :wallet other-ref
                               (inventory other-ref
                                          [(record event-root :stored :sealed-log)]
                                          [{:coverage/sink :sealed-log
                                            :coverage/status :complete}])
                               {:subject-ref other-ref
                                :authorization
                                (authorization :wallet other-ref other-ref)}))]
      (is (= :authorization-denied (:privacy-storage-query/status result)))
      (is (= :denied (:privacy-storage-query/authorization-status result)))
      (is (empty? (:privacy-storage-query/summary result)))
      (is (not (contains? result :privacy-storage-query/coverage)))
      (is (not (contains? result :privacy-storage-query/storage-trust-policy-root))))))

(deftest missing-events-and-invalid-evidence-do-not-claim-data-absence
  (let [partial (inventory [] [{:coverage/sink :sealed-log :coverage/status :partial}])
        complete-subset (inventory [] [{:coverage/sink :sealed-log :coverage/status :complete}])
        complete (inventory [] (mapv (fn [sink]
                                       {:coverage/sink sink :coverage/status :complete})
                                     sentinel/all-sinks))
        partial-result (query/query-event
                        (query-input :event event-root partial {}))
        complete-subset-result (query/query-event
                                (query-input :event event-root complete-subset {}))
        complete-result (query/query-event
                         (query-input :event event-root complete {}))
        stale-record-inventory
        (inventory [(assoc (record event-root :stored :sealed-log)
                           :record/observed-at 130)]
                   [{:coverage/sink :sealed-log :coverage/status :complete}])
        stale-record-result
        (query/query-event (query-input :event event-root stale-record-inventory {}))
        uncovered-deleted-record-inventory
        (inventory [(record event-root :deleted :sealed-log)]
                   [{:coverage/sink :private-encrypted-bundle
                     :coverage/status :complete}])
        uncovered-deleted-record-result
        (query/query-event
         (query-input :event event-root uncovered-deleted-record-inventory {}))
        stale-result (query/query-event
                      (query-input :event event-root complete
                                   {:now 180 :max-observation-age-seconds 10}))
        tampered (assoc complete :storage-inventory/observed-at 151)
        tampered-result (query/query-event
                         (query-input :event event-root tampered {}))]
    (is (= :unknown (:privacy-storage-query/event-status partial-result)))
    (is (= :unknown (:privacy-storage-query/event-status complete-subset-result))
        "complete coverage of a subset of known sinks cannot establish absence")
    (is (= :not-observed-in-covered-sinks
           (:privacy-storage-query/event-status complete-result)))
    (is (= :unavailable (:privacy-storage-query/status stale-record-result)))
    (is (= :unknown (:privacy-storage-query/event-status stale-record-result)))
    (is (= :unavailable
           (:privacy-storage-query/status uncovered-deleted-record-result))
        "a record cannot claim a sink omitted from inventory coverage")
    (is (= :unknown
           (:privacy-storage-query/event-status uncovered-deleted-record-result)))
    (is (= :unavailable (:privacy-storage-query/status stale-result)))
    (is (= :unknown (:privacy-storage-query/event-status stale-result)))
    (is (= :unavailable (:privacy-storage-query/status tampered-result)))
    (is (= :unknown (:privacy-storage-query/event-status tampered-result))))
  (testing "malformed time inputs return unavailable rather than throwing"
    (let [result (query/query-wallet
                  (query-input :wallet subject-ref (inventory [])
                               {:now "160"}))]
      (is (= :unavailable (:privacy-storage-query/status result)))
      (is (= :unknown (:privacy-storage-query/event-status result))))))

(deftest query-commands-keep-errors-and-unauthorized-identifiers-private
  (let [valid-input (assoc (query-input :event event-root
                                        [(record event-root :stored :sealed-log)])
                           :event-root event-root)
        valid (invoke command/query-event valid-input)
        valid-output (read-string (:output valid))
        unauthorized-input (assoc valid-input
                                  :authorization
                                  (authorization :event event-root (sha "e")))
        denied (invoke command/query-event unauthorized-input)
        denied-output (read-string (:output denied))
        malformed (invoke command/query-wallet
                          (assoc (query-input :wallet subject-ref (inventory []))
                                 :raw-wallet "sensitive"))
        malformed-output (read-string (:output malformed))]
    (is (= 0 (:exit-code (:result valid))))
    (is (= :stored-observed
           (:privacy-storage-query/event-status valid-output)))
    (is (not (.contains (:output valid) subject-ref)))
    (is (= 1 (:exit-code (:result denied))))
    (is (= :authorization-denied
           (:privacy-storage-query/status denied-output)))
    (is (not (.contains (:output denied) event-root)))
    (is (= 2 (:exit-code (:result malformed))))
    (is (= :unavailable (:privacy-storage-query/status malformed-output)))
    (is (not (.contains (:output malformed) "sensitive")))))
