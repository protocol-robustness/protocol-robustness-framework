(ns resolver-sim.commands.sentinel-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.commands.sentinel :as cmd]
            [resolver-sim.sensitivity.contract :as contract]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as fx]))

(defn- request [content sink]
  (contract/build-request {:artifact-id "req-test"
                           :content content
                           :sink sink
                           :policy-hash-str (sentinel/policy-hash)}))

(deftest decide-signs-valid-decision
  (let [kp (fx/keypair)
        req (request {:scenario-id "s1"} :ipfs)
        resp (cmd/decide {:private-key (:private-key kp) :key-id (:key/id kp)
                          :assurance :process-isolated}
                         req)
        decision (:decision resp)
        policy (fx/trust-policy kp)]
    (is (= contract/response-kind (:response/kind resp)))
    (is (= "req-test" (:request/id resp)))
    (is (= (:request/hash req) (:request/hash resp)))
    (is (= contract/decision-kind (:artifact/kind decision)))
    (is (= (:artifact/declared-hash req) (:sentinel/artifact-hash decision)))
    (is (= :process-isolated (:sentinel/authority-assurance decision)))
    (is (= :ipfs (:sentinel/sink decision)))
    (is (true? (:valid? (sed/verify-envelope decision contract/decision-domain
                                             policy :sensitivity-sentinel))))))

(deftest decide-rejects-artifact-hash-mismatch
  (let [kp (fx/keypair)
        req (request {:scenario-id "s1"} :ipfs)
        tampered (assoc-in req [:artifact/declared-hash] "sha256:forged")]
    (is (thrown-with-msg? Exception #"artifact projection hash mismatch"
                          (cmd/decide {:private-key (:private-key kp)} tampered)))))

(deftest decide-rejects-policy-hash-mismatch
  (let [kp (fx/keypair)
        req (request {:scenario-id "s1"} :ipfs)
        tampered (assoc req :policy/hash "sha256:stale")]
    (is (thrown-with-msg? Exception #"policy hash mismatch"
                          (cmd/decide {:private-key (:private-key kp)} tampered)))))

(deftest decide-blocks-sensitive-content-on-public-sink
  (let [kp (fx/keypair)
        req (request {:node-hash "sha256:n" :sensitivity/findings
                      [{:finding/id "f1" :rule/id :secret-scanner/private-key :rule/version "v2"}]}
                     :ipfs)
        resp (cmd/decide {:private-key (:private-key kp)} req)]
    (is (= :blocked (get-in resp [:decision :sentinel/decision])))))

(deftest run-from-reader-outputs-response-and-exits-zero
  (let [kp (fx/keypair)
        req (request {:scenario-id "s1"} :ipfs)
        input (pr-str req)
        out (with-out-str
              (is (zero? (cmd/run-from-reader
                          (io/reader (java.io.StringReader. input))
                          (:private-key kp)
                          {:key-id (:key/id kp) :assurance :process-isolated}))))]
    (is (some? out))
    (is (re-find #":response/kind" out))
    (is (re-find #":request/id \"req-test\"" out))))

(deftest run-from-reader-rejects-trailing-garbage
  (let [kp (fx/keypair)
        req (request {:scenario-id "s1"} :ipfs)
        input (str (pr-str req) "\n:garbage")
        out (with-out-str
              (is (pos? (cmd/run-from-reader
                         (io/reader (java.io.StringReader. input))
                         (:private-key kp) {:key-id (:key/id kp)}))))]
    (is (re-find #":error/reason :trailing-request" out))))

(deftest run-from-reader-rejects-empty-input
  (let [kp (fx/keypair)
        out (with-out-str
              (is (pos? (cmd/run-from-reader
                         (io/reader (java.io.StringReader. ""))
                         (:private-key kp) {:key-id (:key/id kp)}))))]
    (is (re-find #":error/reason :empty-request" out))))

(deftest run-from-reader-rejects-invalid-request-shape
  (let [kp (fx/keypair)
        out (with-out-str
              (is (pos? (cmd/run-from-reader
                         (io/reader (java.io.StringReader. "{:request/kind :wrong :request/version 1}"))
                         (:private-key kp) {:key-id (:key/id kp)}))))]
    (is (re-find #":error/reason :invalid-request" out))))

(defn- write-pem [^java.security.PrivateKey private-key]
  (let [f (java.io.File/createTempFile "sentinel-key" ".pem")
        pem (str "-----BEGIN PRIVATE KEY-----\n"
                 (.encodeToString (java.util.Base64/getEncoder) (.getEncoded private-key))
                 "\n-----END PRIVATE KEY-----\n")]
    (spit f pem)
    f))

(deftest run-cli-entry-loads-pem-and-signs
  (let [kp (fx/keypair)
        key-file (write-pem (:private-key kp))
        req (request {:scenario-id "s1"} :ipfs)
        out (with-in-str (pr-str req)
              (with-out-str
                (is (zero? (cmd/run {:key (.getPath key-file) :key-id (:key/id kp)})))))]
    (is (string? out))
    (io/delete-file key-file true)))
