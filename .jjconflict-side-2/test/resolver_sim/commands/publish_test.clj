(ns resolver-sim.commands.publish-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.commands.publish :as cmd]
            [resolver-sim.publish.contract :as contract]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as fx]))

(defn tmpdir []
  (let [d (java.io.File/createTempFile "prf-pub-authority" "")]
    (.delete d)
    (.mkdirs d)
    d))

(defn write-file [dir rel content]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn stage-with [f]
  (let [dir (tmpdir)]
    (try (f dir)
         (finally (when (.exists dir) (io/delete-file dir :recursively))))))

(defn- build-request [dir run-id]
  (write-file dir "summary.json" "{}")
  (let [entries [{:path "summary.json" :sha256 (manifest/file-sha256 (io/file dir "summary.json"))}]]
    (contract/build-request {:id "req-pub" :root dir :run-id run-id
                             :manifest entries :required ["summary.json"]})))

(deftest decide-signs-valid-certificate
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      resp (cmd/decide {:private-key (:private-key kp) :key-id (:key/id kp)
                                        :assurance :process-isolated}
                                       req)
                      cert (:decision resp)
                      policy (fx/trust-policy kp :artifact-publisher :active)]
                  (is (= contract/response-kind (:response/kind resp)))
                  (is (= "req-pub" (:request/id resp)))
                  (is (= (:request/hash req) (:request/hash resp)))
                  (is (= contract/decision-kind (:artifact/kind cert)))
                  (is (= :approve (:publish/decision cert)))
                  (is (= :process-isolated (:publish/authority-assurance cert)))
                  (is (= "run-1" (:publish/run-id cert)))
                  (is (= (manifest/manifest-commit "run-1" (:publish/manifest req))
                         (:publish/manifest-commit cert)))
                  (is (true? (:valid? (sed/verify-envelope cert contract/decision-domain
                                                           policy :artifact-publisher))))))))

(deftest decide-rejects-request-hash-mismatch
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      tampered (assoc req :request/hash "sha256:forged")]
                  (is (thrown-with-msg? Exception #"request hash mismatch"
                                        (cmd/decide {:private-key (:private-key kp)} tampered)))))))

(deftest decide-rejects-policy-hash-mismatch
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      tampered (sed/attach-request-hash contract/request-domain
                                                        (assoc req :policy/hash "sha256:stale"))]
                  (is (thrown-with-msg? Exception #"policy hash mismatch"
                                        (cmd/decide {:private-key (:private-key kp)} tampered)))))))

(deftest decide-rejects-manifest-commit-mismatch
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      tampered (sed/attach-request-hash contract/request-domain
                                                        (assoc req :publish/declared-commit "sha256:forged"))]
                  (is (thrown-with-msg? Exception #"manifest commitment mismatch"
                                        (cmd/decide {:private-key (:private-key kp)} tampered)))))))

(deftest decide-rejects-modified-artifact-all-or-nothing
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      _ (write-file dir "summary.json" "tampered-content")]
                  (is (thrown-with-msg? Exception #"all-or-nothing verification"
                                        (cmd/decide {:private-key (:private-key kp)} req)))))))

(deftest decide-rejects-deleted-artifact
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      _ (io/delete-file (io/file dir "summary.json") true)]
                  (is (thrown-with-msg? Exception #"all-or-nothing verification"
                                        (cmd/decide {:private-key (:private-key kp)} req)))))))

(deftest run-from-reader-outputs-response-and-exits-zero
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      out (with-out-str
                            (is (zero? (cmd/run-from-reader
                                        (io/reader (java.io.StringReader. (pr-str req)))
                                        (:private-key kp)
                                        {:key-id (:key/id kp) :assurance :process-isolated}))))]
                  (is (re-find #":response/kind" out))
                  (is (re-find #":artifact/kind :artifact-publish-certificate" out))))))

(deftest run-from-reader-rejects-trailing-garbage
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      req (build-request dir "run-1")
                      out (with-out-str
                            (is (pos? (cmd/run-from-reader
                                       (io/reader (java.io.StringReader. (str (pr-str req) "\n:garbage")))
                                       (:private-key kp) {:key-id (:key/id kp)}))))]
                  (is (re-find #":error/reason :trailing-request" out))))))

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
  (let [f (java.io.File/createTempFile "publish-key" ".pem")
        pem (str "-----BEGIN PRIVATE KEY-----\n"
                 (.encodeToString (java.util.Base64/getEncoder) (.getEncoded private-key))
                 "\n-----END PRIVATE KEY-----\n")]
    (spit f pem)
    f))

(deftest run-cli-entry-loads-pem-and-signs
  (stage-with (fn [dir]
                (let [kp (fx/keypair)
                      key-file (write-pem (:private-key kp))
                      req (build-request dir "run-1")
                      out (with-in-str (pr-str req)
                            (with-out-str
                              (is (zero? (cmd/run {:key (.getPath key-file) :key-id (:key/id kp)})))))]
                  (is (string? out))
                  (io/delete-file key-file true)))))
