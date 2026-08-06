(ns resolver-sim.publish-client-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.commands.publish :as cmd]
            [resolver-sim.publish.contract :as contract]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.publish-client :as pc]
            [resolver-sim.support.ed25519 :as fx]))

(defn tmpdir []
  (let [d (java.io.File/createTempFile "prf-pub-client" "")]
    (.delete d)
    (.mkdirs d)
    d))

(defn write-file [dir rel content]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn with-dirs [f]
  (let [root (tmpdir)
        stage (io/file root "stage")
        target (io/file root "target")]
    (.mkdirs stage)
    (try (f stage target)
         (finally (io/delete-file root :recursively)))))

(defn fake-authority-runner
  "Run the authority in-process and capture its stdout, so the client's
   process seam can be exercised without spawning a JVM."
  [private-key key-id assurance]
  (fn [argv input timeout-ms]
    (let [out (with-out-str
                (cmd/run-from-reader (io/reader (java.io.StringReader. input))
                                     private-key
                                     {:key-id key-id :assurance assurance}))]
      {:exit 0 :stdout out :stderr ""})))

(deftest request-certificate-returns-verified-signed-certificate
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     entries [{:path "summary.json" :sha256 (manifest/file-sha256 (io/file stage "summary.json"))}]
                     req (contract/build-request {:id "c1" :root (.getAbsolutePath stage)
                                                  :run-id "run-1" :manifest entries
                                                  :required ["summary.json"]})
                     cert (pc/request-certificate
                           {:request req
                            :command ["prf" "publish" "check"]
                            :runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)
                            :trust-policy (fx/trust-policy kp :artifact-publisher :active)})]
                 (is (= :approve (:publish/decision cert)))
                 (is (= "run-1" (:publish/run-id cert)))
                 (is (map? (:signature cert)))))))

(deftest request-certificate-fails-closed-on-modified-set
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     entries [{:path "summary.json" :sha256 (manifest/file-sha256 (io/file stage "summary.json"))}]
                     req (contract/build-request {:id "c2" :root (.getAbsolutePath stage)
                                                  :run-id "run-1" :manifest entries
                                                  :required ["summary.json"]})]
                 (write-file stage "summary.json" "tampered")
                 (is (thrown-with-msg? Exception #"publisher authority exited non-zero|set failed"
                                       (pc/request-certificate
                                        {:request req
                                         :command ["prf" "publish" "check"]
                                         :runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)
                                         :trust-policy (fx/trust-policy kp :artifact-publisher :active)})))))))

(deftest verify-certificate-rejects-wrong-role
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     entries [{:path "summary.json" :sha256 (manifest/file-sha256 (io/file stage "summary.json"))}]
                     req (contract/build-request {:id "c3" :root (.getAbsolutePath stage)
                                                  :run-id "run-1" :manifest entries
                                                  :required ["summary.json"]})
                     runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)
                     {:keys [exit stdout]} (runner ["jvm"] (pr-str req) 1000)
                     response (read-string stdout)]
                 (let [result (pc/verify-certificate response req
                                                     (fx/trust-policy kp :sensitivity-sentinel :active))]
                   (is (false? (:valid? result)))
                   (is (= :wrong-key-role (:reason result))))))))

(deftest publish!-promotes-atomically-and-writes-certificate
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     _ (write-file stage "traces/t1.json" "[1,2]")
                     runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)
                     result (pc/publish!
                             {:stage (.getAbsolutePath stage)
                              :target (.getAbsolutePath target)
                              :run-id "run-9"
                              :runner runner
                              :command ["prf" "publish" "check"]
                              :trust-policy (fx/trust-policy kp :artifact-publisher :active)})]
                 (is (= (.getAbsolutePath target) (:promoted-path result)))
                 (is (= "run-9" (:run-id result)))
                 (is (= "{}" (slurp (io/file target "summary.json"))))
                 (is (= "[1,2]" (slurp (io/file target "traces/t1.json"))))
                 (is (some? (slurp (io/file target "publication.json"))))
                 (is (zero? (count (filter #(re-find #"\.publish-tmp" (.getName %))
                                           (.listFiles (.getParentFile target))))))))))

(deftest publish!-refuses-existing-target
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     _ (.mkdirs target)
                     runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)]
                 (is (thrown-with-msg? Exception #"target already exists"
                                       (pc/publish!
                                        {:stage (.getAbsolutePath stage)
                                         :target (.getAbsolutePath target)
                                         :run-id "run-10"
                                         :runner runner
                                         :command ["prf" "publish" "check"]
                                         :trust-policy (fx/trust-policy kp :artifact-publisher :active)})))))))

(deftest publish!-leaves-target-untouched-on-rejection
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     entries [{:path "summary.json" :sha256 (manifest/file-sha256 (io/file stage "summary.json"))}]
                     req (contract/build-request {:id "r11" :root (.getAbsolutePath stage)
                                                  :run-id "run-11" :manifest entries
                                                  :required ["summary.json"]})
                     runner (fake-authority-runner (:private-key kp) (:key/id kp) :process-isolated)]
                 (io/delete-file (io/file stage "summary.json") true)
                 (is (thrown? Exception
                              (pc/publish!
                               {:stage (.getAbsolutePath stage)
                                :target (.getAbsolutePath target)
                                :run-id "run-11"
                                :request req
                                :runner runner
                                :command ["prf" "publish" "check"]
                                :trust-policy (fx/trust-policy kp :artifact-publisher :active)})))
                 (is (false? (.exists target)))))))

(deftest publish!-without-command-fails-closed
  (with-dirs (fn [stage target]
               (write-file stage "summary.json" "{}")
               (is (thrown-with-msg? Exception #"no publisher authority command configured"
                                     (pc/publish!
                                      {:stage (.getAbsolutePath stage)
                                       :target (.getAbsolutePath target)
                                       :run-id "run-12"
                                       :trust-policy {:trusted-keys []}}))))))
