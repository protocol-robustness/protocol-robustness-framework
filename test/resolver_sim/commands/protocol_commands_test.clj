(ns resolver-sim.commands.protocol-commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.cli.registry :as registry]
            [resolver-sim.commands.document :as document]
            [resolver-sim.commands.forensic :as forensic]
            [resolver-sim.allocation.source-bound-entitlement :as source]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.proof-admission-test :as proof-fixture]
            [resolver-sim.evidence.deployment-bound-test :as deployment-fixture]
            [resolver-sim.evidence.investigation-dispatcher-test :as investigation-fixture]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.financial.finality :as financial]
            [resolver-sim.protocols.sew.dispute-ruling-finality-test :as ruling-fixture]
            [resolver-sim.support.ed25519 :as signing]))

(defn- invoke [path request & options]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)]
    (binding [*in* (java.io.StringReader. (pr-str request)) *out* out *err* err]
      (let [exit (dispatch/run (vec (concat path ["--input" "-"] options)))]
        {:exit exit :output (edn/read-string (str out)) :error (str err)}))))

(defn- temp-dir [] (.toFile (java.nio.file.Files/createTempDirectory "prf-command-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- remove-tree! [root]
  (doseq [file (reverse (file-seq (io/file root)))] (io/delete-file file true)))

(deftest command-registration-and-closed-request-contracts
  (doseq [path [["protocol" "finality" "inspect"] ["protocol" "finality" "verify"]
                ["forensic" "run"] ["forensic" "validate"] ["forensic" "verify"]
                ["forensic" "reproduce"] ["protocol" "pro-rata" "derive-operation"]
                ["protocol" "pro-rata" "check-admission"] ["protocol" "pro-rata" "verify-execution"]]]
    (is (dispatch/resolve-command (clojure.string/join " " path)))
    (is (= 2 (:exit (invoke path {})))))
  (is (:ok? (registry/validate-registry)))
  (is (:ok? (registry/validate-paths))))

(deftest command-help-does-not-evaluate-or-start-a-workflow
  (let [output (with-out-str
                 (is (zero? (dispatch/run ["forensic" "run" "--help"]))))]
    (is (clojure.string/includes? output "--input (required)"))
    (is (clojure.string/includes? output "explicit PRF and output roots"))))

(deftest request-and-json-boundaries-preserve-identities
  (binding [*in* (java.io.StringReader. "{} {}")]
    (is (thrown? clojure.lang.ExceptionInfo (document/read-request "-"))))
  (is (= (str (System/getProperty "user.home") "/prf-runs")
         (document/resolve-path "/tmp/request.edn" "~/prf-runs")))
  (let [root (apply str (repeat 64 "a"))
        output (with-out-str (document/emit {:json? true} {:artifact/root root :amount 9007199254740993N} 0))
        projected (json/read-str output)]
    (is (= root (get projected "artifact/root")))
    (is (= "9007199254740993" (get projected "amount")))))

(deftest financial-finality-recomputes-and-preserves-replay-assumption
  (let [world (-> (types/empty-world 1000)
                  (assoc-in [:escrow-transfers 0] {:escrow-state :released})
                  (update :context/time dissoc :instant))
        request {:protocol :sew :world world :workflow-id 0}
        inspected (invoke ["protocol" "finality" "inspect"] request)
        finality (get-in inspected [:output :finality])]
    (is (= 0 (:exit inspected)))
    (is (= (financial/combine-finality world 0) finality))
    (is (= :assumed-by-replay (get-in finality [:chain :chain/source])))
    (is (= 0 (:exit (invoke ["protocol" "finality" "verify"] (assoc request :finality finality)))))
    (is (= 1 (:exit (invoke ["protocol" "finality" "verify"]
                            (assoc request :finality (assoc-in finality [:chain :chain/source] :rpc))))))
    (is (= 2 (:exit (invoke ["protocol" "finality" "inspect"] (assoc request :typo true)))))
    (is (= 2 (:exit (invoke ["protocol" "finality" "inspect"] (assoc request :workflow-id 99)))))
    (testing "An open gate remains visible and blocks financial finality"
      (let [recovering (assoc-in request [:world :pending-fraud-slashes "s"]
                                {:workflow-id 0 :status :pending})
            result (:output (invoke ["protocol" "finality" "inspect"] recovering))]
        (is (false? (get-in result [:finality :financial :financially-final?])))
        (is (= [:slash-appeal] (get-in result [:finality :financial :open-gates])))))))

(deftest ruling-finality-uses-context-reconstruction
  (let [{:keys [world ruling verification finality]} (#'ruling-fixture/inputs :release)
        request {:protocol :sew :kind :ruling :workflow-id 0
                 :world (update world :context/time dissoc :instant)
                 :ruling ruling :authentication-verification verification}]
    (is (= finality (get-in (invoke ["protocol" "finality" "inspect"] request) [:output :finality])))
    (is (= 0 (:exit (invoke ["protocol" "finality" "verify"] (assoc request :finality finality)))))
    (is (= 1 (:exit (invoke ["protocol" "finality" "verify"]
                            (-> request (assoc :finality finality)
                                (assoc-in [:world :params :max-dispute-level] 1))))))))

(deftest source-derivation-preserves-frozen-identities
  (let [fixture (json/read-str (slurp "etc/conformance/vectors/source-bound-entitlement-v1.json") :key-fn keyword)
        base (:base-input fixture)
        derived (source/derive base)
        bound (assoc-in base [:prepared-operation :policy-root] (str "0x" (:realized-statement-root derived)))
        result (invoke ["protocol" "pro-rata" "derive-operation"] bound)]
    (is (= 0 (:exit result)))
    (is (= (source/fixture-projection (source/derive-controlled-source bound)) (get-in result [:output :operation])))
    (is (= 2 (:exit (invoke ["protocol" "pro-rata" "derive-operation"]
                            (assoc-in bound [:prepared-operation :policy-root] (str "0x" (apply str (repeat 64 "0"))))))))))

(deftest admission-requires-independent-trust-and-exact-input-and-proof-bytes
  (let [root (temp-dir)]
    (try
      (let [raw {"allocation-context" proof-fixture/raw-context "available" "50"
                 "requested" {"A" "50" "B" "50"}
                 "policy" {"mode" "pro-rata" "rounding-policy" "largest-remainder"}
                 "round-state" "result-accepted"}
            input-file (io/file root "realized.json")
            _ (spit input-file (json/write-str raw))
            rebuilt (persisted/reconstruct-input (slurp input-file))
            artifact (#'proof-fixture/artifact-fixture (:statement rebuilt))
            _ (spit (io/file root (:proof/file artifact)) "\u0001\u0002\u0003\u0004")
            kp (signing/keypair)
            digest (persisted/sha256-bytes-ref (java.nio.file.Files/readAllBytes (.toPath input-file)))
            receipt (-> (admission/build-verifier-receipt
                         {:artifact artifact :persisted-input-sha256 digest
                          :verifier-id :sp1-sdk :verifier-version "6.3.1" :verdict :verified})
                        (admission/sign-verifier-receipt (:private-key kp) (:key/id kp)))
            trust-file (io/file root "trust.edn")
            _ (spit trust-file (pr-str {:trust-policy (signing/trust-policy kp :allocation-proof-verifier :active)
                                       :program-registry (#'proof-fixture/program-registry artifact)}))
            request {:artifact artifact :receipt receipt :realized-input (str input-file) :proof-dir (str root)}
            check #(invoke ["protocol" "pro-rata" "check-admission"] % "--trust-context" (str trust-file))]
        (is (= 0 (:exit (check request))))
        (is (= 2 (:exit (invoke ["protocol" "pro-rata" "check-admission"] request))))
        (is (= 1 (:exit (check (assoc-in request [:receipt :verification/verdict] :rejected)))))
        (spit input-file "\n" :append true)
        (is (= 1 (:exit (check request))) "Even semantically equivalent changed input bytes must reject")
        (spit input-file (json/write-str raw))
        (spit (io/file root (:proof/file artifact)) "changed")
        (is (= 1 (:exit (check request))))
        (is (= 2 (:exit (invoke ["protocol" "pro-rata" "verify-execution"] {:bundle-dir (str root)})))))
      (finally (remove-tree! root)))))

(deftest retained-execution-check-reconstructs-and-rejects-mutated-evidence
  (#'investigation-fixture/with-bundle (deployment-fixture/fixture-input)
    (fn [root]
      (let [request {:bundle-dir (str root)}
            result (invoke ["protocol" "pro-rata" "verify-execution"] request)]
        (is (= 0 (:exit result)))
        (is (= :retained-local-execution-evidence (get-in result [:output :scope])))
        (is (false? (get-in result [:output :live-observation?])))
        (is (seq (get-in result [:output :validation :gaps])) "Successful retained checks preserve assurance gaps")
        (spit (io/file root "artifacts/fixture.json") "{}")
        (is (not (zero? (:exit (invoke ["protocol" "pro-rata" "verify-execution"] request)))))))))

(deftest forensic-adapter-verifies-real-golden-bundle-and-rejects-substitution
  (let [root (temp-dir)
        repo (.getCanonicalPath (io/file "."))]
    (try
      (let [unpack (shell/sh "python3" "-c"
                             "import sys,tarfile; tarfile.open(sys.argv[1]).extractall(sys.argv[2],filter='data')"
                             (str repo "/test/forensic_python/fixtures/golden-bundle.tar.gz") (str root))
            request {:repo-root repo :run-dir (str (io/file root "golden-bundle"))
                     :public-key (str repo "/test/forensic_python/fixtures/golden-ed25519.pub")}
            result (invoke ["forensic" "verify"] request)]
        (is (zero? (:exit unpack)))
        (is (= 0 (:exit result)) (:output result))
        (is (= "pass" (get (json/read-str (get-in result [:output :stdout])) "verify/status")))
        (let [validation (invoke ["forensic" "validate"]
                                 {:repo-root repo :bundle (:run-dir request)})]
          (is (= 1 (:exit validation)))
          (is (= "not-verified" (get (json/read-str (get-in validation [:output :stdout])) "validate/status"))))
        (spit (io/file root "golden-bundle/run-bundle-root.json") "{}")
        (is (= 1 (:exit (invoke ["forensic" "verify"] request)))))
      (finally (remove-tree! root)))))

(deftest forensic-argv-resolves-paths-against-request-and-rejects-option-injection
  (let [root (.getCanonicalPath (io/file "."))
        request {:repo-root root :run-dir "bundle with spaces;$(false)"}
        result (forensic/invocation :verify "/tmp/request.edn" request)]
    (is (= root (:root result)))
    (is (= "/tmp/bundle with spaces;$(false)" (last (:argv result))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (forensic/invocation :verify "/tmp/request.edn" (assoc request :shell "unsafe"))))))
