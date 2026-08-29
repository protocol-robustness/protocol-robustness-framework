(ns resolver-sim.protocols.sew.accounting-force-authorisation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.hash.canonical :as hc]))

(def token :USDC)
(def amount 10)
(def workflow-id 1)
(def owner "0xrecipient")
(def auth-id "force-test")

(defn- reason-for [direction]
  (if (= direction :in) :force-authorised-release :force-authorised-refund))

(defn- scope-for [direction]
  (let [reason (reason-for direction)
        components (held-policy/position-components token reason
                                                    {:held/workflow-id workflow-id
                                                     :owner/address owner})
        fields {:authorization/id auth-id :authorization/type :force-authorisation
                :held/direction direction :token token :amount amount
                :held/account (:held/account components)
                :held/position-id (:held/position-id components)
                :owner/address (:owner/address components)
                :held/reason reason
                :held/workflow-id workflow-id}]
    (held-adjustment/project-held-adjustment-scope fields)))

(defn- scope-hash [scope]
  (hc/domain-hash "force-authorisation-scope" scope))

(defn- provenance [direction]
  (let [scope (scope-for direction)]
    {:authorization/id auth-id :authorization/type :force-authorisation
     :authorization/scope-kind :single-claim
     :authorization/scope-hash (scope-hash scope)}))

(defn- valid-record [direction]
  (let [scope (scope-for direction)]
    {:authorization/id auth-id :authorization/status :active :consumed? false
     :starts-at 0 :expires-at nil :authorization/scope-kind :single-claim
     :authorization/scope scope :authorization/scope-hash (scope-hash scope)}))

(defn- world [direction record]
  (let [position-id [:held/position token :escrow-principal workflow-id]
        base (types/empty-world 10)
        base (assoc base :force-authorisations (if record {auth-id record} {}))]
    (if (= direction :out)
      (-> base
          (assoc-in [:total-held token] amount)
          (assoc-in [:held/positions position-id] amount))
      base)))

(defn- opts [direction prov]
  {:reason (reason-for direction)
   :authorization-provenance prov
   :extra {:held/workflow-id workflow-id :owner/address owner}})

(defn- invoke [direction w prov]
  (if (= direction :in)
    (acct/add-held w token amount (opts direction prov))
    (acct/sub-held w token amount (opts direction prov))))

(defn- rejection! [direction record mutate expected]
  (let [before (world direction (mutate record))
        prov (provenance direction)
        thrown (try (invoke direction before prov) nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown))
    (is (= expected (:type (ex-data thrown))))
    (is (= before
           (try (invoke direction before prov)
                (catch clojure.lang.ExceptionInfo _ before))))))

(defn- rejection-with-extra! [direction record mutate extra-override expected]
  (let [before (world direction (mutate record))
        prov (provenance direction)
        custom-opts {:reason (reason-for direction)
                     :authorization-provenance prov
                     :extra extra-override}
        thrown (try (if (= direction :in)
                      (acct/add-held before token amount custom-opts)
                      (acct/sub-held before token amount custom-opts))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "should throw")
    (is (= expected (:type (ex-data thrown)))
        (str "expected error type " expected ", got " (some-> thrown ex-data :type)))
    (is (= before
           (try (if (= direction :in)
                  (acct/add-held before token amount custom-opts)
                  (acct/sub-held before token amount custom-opts))
                (catch clojure.lang.ExceptionInfo _ before)))
        "world must not be mutated on rejection")))

(deftest force-authorisation-rejections-do-not-mutate-held-state
  (doseq [direction [:in :out]]
    (let [record (valid-record direction)]
      (testing (str "direction " direction)
        (rejection! direction nil identity :authorization/not-found)
        (rejection! direction (assoc record :authorization/scope-kind :unknown) identity :authorization/unsupported-scope-kind)
        (rejection! direction record #(assoc % :authorization/status :revoked) :authorization/not-active)
        (rejection! direction record #(assoc % :authorization/status :consumed) :authorization/already-consumed)
        (rejection! direction record #(assoc % :consumed? true) :authorization/already-consumed)
        (rejection! direction record #(assoc % :starts-at 11) :authorization/not-yet-started)
        (rejection! direction record #(assoc % :expires-at 10) :authorization/expired)
        (rejection! direction record #(dissoc % :authorization/scope) :authorization/missing-scope)
        (rejection! direction record #(assoc-in % [:authorization/scope :amount] 999) :authorization/grant-scope-mismatch)
        (rejection! direction record #(assoc % :authorization/scope-hash "bad") :authorization/grant-scope-hash-mismatch)
        (testing "position-policy: missing :held/workflow-id in extra causes scope mismatch"
          (rejection-with-extra! direction record identity
                                 (dissoc {:held/workflow-id workflow-id :owner/address owner} :held/workflow-id)
                                 :authorization/grant-scope-mismatch))
        (testing "position-policy: missing :owner/address in extra causes scope mismatch"
          (rejection-with-extra! direction record identity
                                 (dissoc {:held/workflow-id workflow-id :owner/address owner} :owner/address)
                                 :authorization/grant-scope-mismatch))))))

(deftest valid-force-authorised-add-and-sub-mutate-and-consume
  (doseq [direction [:in :out]]
    (let [w (world direction (valid-record direction))
          after (invoke direction w (provenance direction))]
      (is (not= w after))
      (is (= :consumed (get-in after [:force-authorisations auth-id :authorization/status])))
      (is (= 1 (count (:held-adjustments after))))
      (is (= 1 (count (:held-artifacts after)))))))
