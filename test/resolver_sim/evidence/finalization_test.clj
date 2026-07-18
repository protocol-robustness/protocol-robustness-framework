(ns resolver-sim.evidence.finalization-test
  (:require [clojure.data.json :as json]
              [clojure.java.io :as io]
              [clojure.test :refer [deftest is]]
              [resolver-sim.evidence.chain :as chain]
              [resolver-sim.evidence.finalization :as finalization])
    (:import [java.math BigInteger]
             [java.nio.file Files]
             [java.security MessageDigest]))

(def h1 "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def h2 "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(deftest hash-set-is-canonical-and-rejects-malformed-persisted-shapes
  (let [hash-set (finalization/build-hash-set [h2 h1 h1])]
    (is (= [h1 h2] (:hashes hash-set)))
    (is (= 2 (:count hash-set)))
    (is (:valid? (finalization/validate-hash-set hash-set)))
    (is (some #{:hashes-not-lexicographically-sorted}
              (:errors (finalization/validate-hash-set
                        (assoc hash-set :hashes [h2 h1] :count 2)))))
    (is (some #{:root-mismatch}
              (:errors (finalization/validate-hash-set
                        (assoc hash-set :root h1)))))))

(deftest valid-empty-scenario-finalization-is-explicit
  (let [result (finalization/build-scenario-finalization
                {:run {:run-id "run-1" :run-input-hash h1}
                 :subject {:subject-kind "scenario" :scenario-id "S-empty"}
                 :execution {:status "completed" :terminality "closed"}
                 :chain {:status "valid-empty" :record-count 0 :genesis nil :head nil
                         :reachable-hashes []}
                 :bindings {} :verification {:status "verified"} :policy {}})]
    (is (= "scenario-chain-finalization" (:finalization-kind result)))
    (is (= "valid-empty" (get-in result [:evidence :chain :status])))
    (is (:valid? (finalization/validate-finalization result)))))

(deftest scenario-finalization-execution-identity-is-profile-gated
  (let [legacy (finalization/build-scenario-finalization
                {:run {:run-id "run-1" :run-input-hash h1}
                 :subject {:subject-kind "scenario" :scenario-id "S-empty"}
                 :execution {:status "completed" :terminality "closed"}
                 :chain {:status "valid-empty" :record-count 0 :genesis nil :head nil :reachable-hashes []}
                 :bindings {} :verification {:status "verified"} :policy {}})
        canonical (finalization/build-scenario-finalization
                   {:run {:run-id "run-1" :run-input-hash h1}
                    :execution-id "execution:run-1"
                    :subject {:subject-kind "scenario" :scenario-id "S-empty"}
                    :execution {:status "completed" :terminality "closed"}
                    :chain {:status "valid-empty" :record-count 0 :genesis nil :head nil :reachable-hashes []}
                    :bindings {} :verification {:status "verified"} :policy {}})]
    (is (:valid? (finalization/validate-finalization legacy)))
    (is (some #{:missing-execution-id}
              (:errors (finalization/validate-finalization legacy {:require-execution-id? true}))))
    (is (= "execution:run-1" (:execution/id canonical)))
    (is (:valid? (finalization/validate-finalization canonical {:require-execution-id? true})))
    (is (not= canonical (assoc canonical :execution/id "execution:other")))))

(deftest scenario-finalization-writes-only-forensic-relative-public-metadata
  (let [dir (str (.toFile (java.nio.file.Files/createTempDirectory
                           "scenario-finalization"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
        result (finalization/write-scenario-finalization!
                {:forensic-dir dir
                 :scenario-artifact-id "S-empty-abc123"
                 :scenario-id "S-empty"
                 :scenario-input-hash h1
                 :run-id "run-1"
                 :run-input-hash h1
                 :policy {:allow-empty-targeted-evidence? true}})]
    (is (.isFile (java.io.File. (:path result))))
    (is (re-find #"finalizations/scenarios/S-empty-abc123/evidence-finalization\.json$"
                 (:path result)))
    (is (= "valid-empty" (get-in result [:finalization :evidence :chain :status])))
    (is (:valid? (:validation result)))))

(deftest persisted-chain-references-bind-link-identity-and-file-bytes-separately
  (let [dir (str (.toFile (Files/createTempDirectory
                            "scenario-finalization-linked"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence-dir (io/file dir "event-evidence")
        _ (.mkdirs evidence-dir)
        record (chain/with-fresh-evidence-context*
                 #(chain/inject-chain-fields {:scenario/id "S-linked"
                                               :evidence/hash h1}))
        evidence-file (io/file evidence-dir "targeted-evidence.json")
        _ (spit evidence-file (json/write-str record))
        _ (spit (io/file dir "evidence-registry.json")
                (json/write-str {:evidence-hashes [h1]
                                 :registry-hash h2}))
        expected-byte-digest (let [digest (MessageDigest/getInstance "SHA-256")]
                               (str "sha256:" (format "%064x"
                                                     (BigInteger. 1
                                                                 (.digest digest
                                                                          (Files/readAllBytes (.toPath evidence-file)))))))
        result (finalization/write-scenario-finalization!
                {:forensic-dir dir
                 :scenario-artifact-id "S-linked-abc123"
                 :scenario-id "S-linked"
                 :scenario-input-hash h1
                 :run-id "run-1"
                 :run-input-hash h1})
        chain-data (get-in result [:finalization :evidence :chain])]
    (is (= "verified" (:status chain-data)))
    (is (= expected-byte-digest (get-in chain-data [:genesis :artifact-bytes-sha256])))
    (is (= expected-byte-digest (get-in chain-data [:head :artifact-bytes-sha256])))
    (is (not= (get-in chain-data [:head :hash])
              (get-in chain-data [:head :artifact-bytes-sha256])))
    (is (:valid? (:validation result)))))

(deftest completed-scenario-requires-persisted-registry-membership
  (let [dir (str (.toFile (Files/createTempDirectory
                            "scenario-finalization-no-registry"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence-dir (io/file dir "event-evidence")
        _ (.mkdirs evidence-dir)
        record (chain/with-fresh-evidence-context*
                 #(chain/inject-chain-fields {:scenario/id "S-unregistered"
                                               :evidence/hash h1}))
        _ (spit (io/file evidence-dir "targeted-evidence.json") (json/write-str record))
        result (finalization/write-scenario-finalization!
                {:forensic-dir dir
                 :scenario-artifact-id "S-unregistered-abc123"
                 :scenario-id "S-unregistered"
                 :scenario-input-hash h1
                 :run-id "run-1"
                 :run-input-hash h1})]
    (is (= "invalid" (get-in result [:finalization :evidence :chain :status])))
    (is (= "invalid" (get-in result [:finalization :verification :status])))
    (is (some #(= "evidence-content-registry-missing" (:reason-code %))
              (get-in result [:finalization :verification :reasons])))))

(deftest registry-membership-mismatch-prevents-a-verified-scenario-finalization
  (let [dir (str (.toFile (Files/createTempDirectory
                            "scenario-finalization-registry-mismatch"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence-dir (io/file dir "event-evidence")
        _ (.mkdirs evidence-dir)
        record (chain/with-fresh-evidence-context*
                 #(chain/inject-chain-fields {:scenario/id "S-mismatch"
                                               :evidence/hash h1}))
        _ (spit (io/file evidence-dir "targeted-evidence.json") (json/write-str record))
        _ (spit (io/file dir "evidence-registry.json")
                (json/write-str {:evidence-hashes [h2] :registry-hash h2}))
        result (finalization/write-scenario-finalization!
                {:forensic-dir dir
                 :scenario-artifact-id "S-mismatch-abc123"
                 :scenario-id "S-mismatch"
                 :scenario-input-hash h1
                 :run-id "run-1"
                 :run-input-hash h1})]
    (is (= "invalid" (get-in result [:finalization :verification :status])))
    (is (some #(= "evidence-content-registry-membership-mismatch" (:reason-code %))
              (get-in result [:finalization :verification :reasons])))))

(deftest aborted-scenario-persists-an-open-partial-finalization-without-registry-membership
  (let [dir (str (.toFile (Files/createTempDirectory
                            "scenario-finalization-aborted"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence-dir (io/file dir "event-evidence")
        _ (.mkdirs evidence-dir)
        record (chain/with-fresh-evidence-context*
                 #(chain/inject-chain-fields {:scenario/id "S-aborted"
                                               :evidence/hash h1}))
        _ (spit (io/file evidence-dir "targeted-evidence.json") (json/write-str record))
        result (finalization/write-scenario-finalization!
                {:forensic-dir dir
                 :scenario-artifact-id "S-aborted-abc123"
                 :scenario-id "S-aborted"
                 :scenario-input-hash h1
                 :run-id "run-1"
                 :run-input-hash h1
                 :execution-status "aborted"
                 :execution-outcome "error"})]
    (is (= "partial" (get-in result [:finalization :evidence :chain :status])))
    (is (= "open" (get-in result [:finalization :execution :terminality])))
    (is (= "partial" (get-in result [:finalization :verification :status])))
    (is (some #(= "evidence-content-registry-missing" (:reason-code %))
              (get-in result [:finalization :verification :reasons])))
    (is (some #(= "scenario-execution-aborted" (:reason-code %))
              (get-in result [:finalization :verification :reasons])))
    (is (:valid? (:validation result)))))

(deftest run-finalization-writes-from-persisted-finalizations-and-content-registry
  (let [dir (str (.toFile (Files/createTempDirectory
                            "run-finalization"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        scenario-file (io/file dir "scenario-finalization-1.json")
        scenario-file-2 (io/file dir "scenario-finalization-2.json")
        registry-file (io/file dir "content-registry.json")
        finalization-file (io/file dir "evidence" "finalizations" "run" "evidence-finalization.json")
        reconciliation-report-file (io/file dir "evidence" "reports" "run-evidence-reconciliation.json")
        scenario-finalization (finalization/build-scenario-finalization
                               {:run {:run-id "run-1" :run-input-hash h1}
                                :execution-id "execution:run-1:a"
                                :subject {:subject-kind "scenario"
                                          :scenario-id "S-empty"
                                          :scenario-artifact-id "S-empty-abc123"}
                                :execution {:status "completed" :terminality "closed"}
                                :chain {:status "valid-empty" :record-count 0 :genesis nil :head nil
                                        :reachable-hashes []}
                                :bindings {}
                                :verification {:status "verified"}
                                :policy {}})
        scenario-finalization-2 (-> scenario-finalization
                                               (assoc :execution/id "execution:run-1:b")
                                               (assoc-in [:subject :scenario-id] "S-empty-2")
                                               (assoc-in [:subject :scenario-artifact-id] "S-empty-2-abc123"))
        ;; Simulate the persisted namespace-preserving finalization boundary.
        json-safe (fn [value] (-> value (dissoc :execution/id) (assoc "execution/id" (:execution/id value))))
        _ (spit scenario-file (json/write-str (json-safe scenario-finalization)))
        _ (spit scenario-file-2 (json/write-str (json-safe scenario-finalization-2)))
        _ (spit registry-file (json/write-str {:evidence-hashes [] :registry-hash h1}))
        result (finalization/write-run-finalization!
                {:finalization-path finalization-file
                                 :reconciliation-report-path reconciliation-report-file
                                 :scenario-finalization-files [scenario-file scenario-file-2]
                 :require-execution-identities? true
                 :evidence-files []
                 :registry-path registry-file
                 :run {:run-id "run-1" :run-input-hash h1}
                 :execution {:status "completed" :terminality "closed"}})]
    (is (.isFile (io/file (:path result))))
    (is (.isFile (io/file (:reconciliation-report-path result))))
    (is (= "run-evidence-finalization" (get-in result [:finalization :finalization-kind])))
    (is (= "verified" (get-in result [:finalization :verification :status])))
    (is (= "exact" (get-in result [:finalization :verification :reconciliation :status])))
    (is (= 2 (count (get-in result [:finalization :evidence :scenario-finalizations]))))
    (is (= #{"execution:run-1:a" "execution:run-1:b"}
           (set (map :execution/id (get-in result [:finalization :evidence :scenario-finalizations])))))
    (is (= 2 (get-in result [:finalization :evidence :scenario-finalization-set :count])))
    (is (= 2 (get-in result [:finalization :evidence :scenario-chain-head-set :count])))
    (is (some #{:scenario-finalization-set-root-mismatch}
              (:errors (finalization/validate-finalization
                        (assoc-in (:finalization result)
                                  [:evidence :scenario-finalization-set :root] h1)))))
    (is (some #{:scenario-chain-heads-do-not-match-finalizations}
              (:errors (finalization/validate-finalization
                        (assoc-in (:finalization result)
                                  [:evidence :scenario-chain-heads 0 :head-hash] h1)))))
    (is (:valid? (:validation result)))
    (is (:satisfied? (finalization/evaluate-run-policy (:finalization result))))
    (is (false? (:satisfied? (finalization/evaluate-run-policy
                              (assoc-in (:finalization result) [:policy :profile-id] "forensic-release.v1")))))))

(deftest run-finalization-requires-exact-sets-for-verified-status
  (let [result (finalization/build-run-finalization
                {:run {:run-id "run-1" :run-input-hash h1}
                 :execution {:status "completed" :terminality "closed"}
                 :scenario-finalizations []
                 :disk-hashes [h1]
                 :registry-hashes [h1]
                 :chain-hashes [h1]
                 :declared-hashes [h2]
                 :bindings {} :verification {} :policy {}})]
    (is (= "invalid" (get-in result [:verification :status])))
    (is (= "mismatch" (get-in result [:verification :reconciliation :status])))
    (is (:valid? (finalization/validate-finalization result)))))
