(ns resolver-sim.io.content-addressed-store-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [resolver-sim.io.content-addressed-store :as store]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.nio.file Files]))

(defn- temp-store []
  (store/create-store (str (Files/createTempDirectory "resolver-sim-cas-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- artifact [value]
  (let [base {:artifact/type :test-artifact :value value}
        hash (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record} base))]
    (assoc base :artifact/hash hash)))

(defn- valid? [artifact]
  (= (:artifact/hash artifact)
     (hash-ref/sha256-ref
      (hc/hash-with-intent {:hash/intent :evidence-record}
                           (dissoc artifact :artifact/hash)))))

(deftest unlinked-store-is-idempotent-and-self-verifying
  (let [backend (temp-store)
        value (artifact :one)
        hash (:artifact/hash value)]
    (is (= :stored (:status (store/put-if-absent! backend {:hash-reference hash :artifact value :verify valid?}))))
    (is (= :already-present (:status (store/put-if-absent! backend {:hash-reference hash :artifact value :verify valid?}))))
    (is (= value (store/resolve-artifact backend hash)))
    (is (= {:present? true :valid? true :hash hash :artifact value}
           (store/verify-stored-artifact backend hash valid?)))))

(deftest canonical-bytes-preserve-semantic-data-across-map-order
  (let [first-value {:artifact/type :test-artifact
                     :nested {:z 2 :a 1}
                     :keyword :sample/namespaced
                     :ratio 3/7}
        second-value (array-map :ratio 3/7
                                :keyword :sample/namespaced
                                :nested (array-map :a 1 :z 2)
                                :artifact/type :test-artifact)]
    (is (= (store/canonical-edn first-value) (store/canonical-edn second-value)))
    (is (= first-value (clojure.edn/read-string (store/canonical-edn first-value))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/canonical-edn {:when (java.time.Instant/now)})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/canonical-edn {:runtime (fn [] :nope)})))))

(deftest unlinked-store-rejects-invalid-and-colliding-writes
  (let [backend (temp-store)
        value (artifact :one)
        hash (:artifact/hash value)]
    (store/put-if-absent! backend {:hash-reference hash :artifact value :verify valid?})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
                          (store/put-if-absent! backend
                                                {:hash-reference hash
                                                 :artifact (assoc value :value :tampered)
                                                 :verify (constantly true)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"self-verification"
                          (store/put-if-absent! backend
                                                {:hash-reference hash
                                                 :artifact (assoc value :value :tampered)
                                                 :verify valid?})))))
