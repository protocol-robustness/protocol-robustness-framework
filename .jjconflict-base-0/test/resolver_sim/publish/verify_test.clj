(ns resolver-sim.publish.verify-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.commands.publish :as pubcmd]
            [resolver-sim.publish-client :as pc]
            [resolver-sim.publish.verify :as verify]
            [resolver-sim.support.ed25519 :as fx]))

(defn tmpdir []
  (let [d (java.io.File/createTempFile "prf-pub-verify" "")]
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

(defn fake-authority-runner [kp]
  (fn [_argv input _timeout-ms]
    (let [out (with-out-str
                (pubcmd/run-from-reader
                 (io/reader (java.io.StringReader. input))
                 (:private-key kp)
                 {:key-id (:key/id kp) :assurance :process-isolated}))]
      {:exit 0 :stdout out :stderr ""})))

(deftest verify-accepts-published-tree
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     _ (write-file stage "traces/t1.json" "[1]")
                     _ (pc/publish! {:stage (.getAbsolutePath stage)
                                     :target (.getAbsolutePath target)
                                     :run-id "run-1"
                                     :command ["prf" "publish" "check"]
                                     :runner (fake-authority-runner kp)
                                     :trust-policy (fx/trust-policy kp :artifact-publisher :active)})
                     res (verify/verify-publication target
                                                    (fx/trust-policy kp :artifact-publisher :active))]
                 (is (true? (:valid? res)))
                 (is (= "run-1" (:run-id res)))))))

(deftest verify-rejects-missing-publication
  (with-dirs (fn [stage target]
               (write-file stage "summary.json" "{}")
               (let [res (verify/verify-publication target
                                                    {:trusted-keys []})]
                 (is (false? (:valid? res)))
                 (is (= :publication-missing (:reason res)))))))

(deftest verify-rejects-unsigned-in-process-bundle
  ;; An in-process promotion that never consulted the publisher must fail:
  ;; the certificate is missing, so the tree cannot be verified.
  (with-dirs (fn [stage target]
               (write-file stage "summary.json" "{}")
               (write-file stage "test-artifacts.json" "{}")
               (let [res (verify/verify-publication target
                                                    {:trusted-keys []})]
                 (is (false? (:valid? res)))
                 (is (= :publication-missing (:reason res)))))))

(deftest verify-rejects-tampered-file-after-promotion
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     _ (pc/publish! {:stage (.getAbsolutePath stage)
                                     :target (.getAbsolutePath target)
                                     :run-id "run-2"
                                     :command ["prf" "publish" "check"]
                                     :runner (fake-authority-runner kp)
                                     :trust-policy (fx/trust-policy kp :artifact-publisher :active)})]
                 (write-file target "summary.json" "tampered-after-publish")
                 (let [res (verify/verify-publication target
                                                      (fx/trust-policy kp :artifact-publisher :active))]
                   (is (false? (:valid? res)))
                   (is (= :manifest-commit-mismatch (:reason res))))))))

(deftest verify-rejects-wrong-role-key
  (with-dirs (fn [stage target]
               (let [kp (fx/keypair)
                     _ (write-file stage "summary.json" "{}")
                     _ (pc/publish! {:stage (.getAbsolutePath stage)
                                     :target (.getAbsolutePath target)
                                     :run-id "run-3"
                                     :command ["prf" "publish" "check"]
                                     :runner (fake-authority-runner kp)
                                     :trust-policy (fx/trust-policy kp :artifact-publisher :active)})]
                 (let [res (verify/verify-publication target
                                                      (fx/trust-policy kp :sensitivity-sentinel :active))]
                   (is (false? (:valid? res)))
                   (is (= :wrong-key-role (:reason res))))))))
