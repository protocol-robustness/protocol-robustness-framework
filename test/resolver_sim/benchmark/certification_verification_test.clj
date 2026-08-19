(ns resolver-sim.benchmark.certification-verification-test
  "Tests for independent certification verification (P0).

   The certification is write-once: the verifier never modifies it. It
   independently re-derives everything the stored certification claims from the
   evidence's primary artifacts and accepts/rejects under a committed
   verification profile. These tests prove both the happy path and the fail-closed
   adversarial behaviour — especially claim closure, where certification success
   must be impossible while any required claim is :fail, :not-implemented,
   :not-exercised, :error, or otherwise not explicitly permitted by the profile."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.certification-verification :as cv]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.vcs :as vcs]))

;; ── Committed-evidence factory ──────────────────────────────────────────────
;; Builds a bundle whose :evidence/hash is committed exactly like the runner does
;; (hashable-evidence under :bundle-root intent), so verify-bundle-hash accepts it.

(defn- commit-bundle
  [bundle]
  (assoc bundle :evidence/hash
         (hc/hash-with-intent {:hash/intent :bundle-root}
                              (into (sorted-map) (integrity/hashable-evidence bundle)))))

(defn- make-manifest [& [claims]]
  {:benchmark/id :benchmark/test :benchmark/claims (or claims [:claim/a])})

(def default-results
  [{:scenario/id "S1" :outcome :pass
    :invariant-results [{:id :conservation :result :pass}]}
   {:scenario/id "S2" :outcome :pass
    :invariant-results [{:id :conservation :result :pass}]}])

(defn- claim-results* [& {:keys [outcome claims]}]
  (let [outcome (or outcome :pass)
        claims  (or claims [:claim/a])]
    (mapv (fn [id] {:claim/id id :claim/outcome outcome :claim/scope :benchmark}) claims)))

(defn- make-cert
  ([manifest claim-results]
   (make-cert manifest claim-results (count default-results)))
  ([manifest claim-results scenario-count]
   (runner/build-certification manifest {:scenario-count scenario-count
                                         :all-invariants-pass true
                                         :invariant-summary {:conservation {:passed 2 :total 2}}}
                               claim-results)))

(defn- make-metrics [n & [passed]]
  {:total n :passed (or passed n)})

(defn- make-evidence
  "Build a fully committed evidence bundle with default passing content."
  [& {:keys [claim-results results metrics certification] :as opts}]
  (let [manifest (or (:manifest opts) (make-manifest))
        results (or results default-results)
        claim-results (or claim-results (claim-results*))
        metrics (or metrics (make-metrics (count results)))
        certification (or certification (make-cert manifest claim-results (count results)))
        bundle {:benchmark manifest
                :results results
                :metrics metrics
                :claim-results claim-results
                :benchmark-certification certification}]
    (commit-bundle bundle)))

(deftest verifies-a-committed-bundle
  (testing "a well-formed committed bundle verifies under the default profile"
    (let [evidence (make-evidence)
          result (cv/verify-benchmark-certification evidence)]
      (is (true? (:verified? result)))
      (is (= "benchmark-certification-verification.v1"
             (:certification-verification/schema result)))
      (is (= (:evidence/hash evidence) (:evidence/root result)))
      (is (= (get-in evidence [:benchmark-certification :certification-hash])
             (:certification/root result)))
      (is (= :out-of-band (get-in result [:verification/creation :provenance])))
      (is (string? (:verification-profile/root result)))
      (doseq [k [:evidence/integrity :certification/bundle-binding
                 :certification/hash-integrity :certification/required-fields
                 :scenario-count :invariant-summary :all-invariants-pass
                 :claim-results :claim-closure]]
        (is (= :pass (get-in result [:checks k])) (str "check " k " passes"))))))

(deftest verification-is-deterministic
  (testing "same evidence + profile yields an identical verification artifact"
    (let [evidence (make-evidence)]
      (is (= (cv/verify-benchmark-certification evidence)
             (cv/verify-benchmark-certification evidence))))))

(deftest rebinding-a-different-certification-fails-closed
  (testing "verifying a certification object that is NOT the committed one is rejected"
    (let [evidence (make-evidence)
          other (make-cert (make-manifest [:claim/a]) (claim-results* :claims [:claim/other]))]
      ;; 2-arity verifies the bundle's OWN committed certification, which is valid
      (is (true? (:verified? (cv/verify-benchmark-certification evidence))))
      ;; 3-arity with a foreign certification fails bundle-binding + hash-integrity
      (let [r (cv/verify-benchmark-certification evidence other cv/default-verification-profile)]
        (is (false? (:verified? r)))
        (is (= :fail (get-in r [:checks :certification/bundle-binding])))
        (is (= :fail (get-in r [:checks :claim-results])))))))

(deftest tampering-a-claim-outcome-fails-verification
  (testing "flipping an evaluated claim to :fail is caught even though all-invariants-pass stays true"
    (let [manifest (make-manifest)
          cert (make-cert manifest (claim-results*))
          evidence (make-evidence :manifest manifest
                                  :certification cert
                                  :claim-results (claim-results* :outcome :fail))
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :claim-results])))
      (is (= :fail (get-in r [:checks :claim-closure]))
          "closure must reject a :fail required claim under the default profile"))))

(deftest removing-a-required-claim-fails-verification
  (testing "a required claim that vanishes from the results is detected (no count masking)"
    (let [manifest (make-manifest [:claim/a :claim/b])
          cert (make-cert manifest (claim-results* :claims [:claim/a :claim/b]))
          evidence (make-evidence :manifest manifest
                                  :certification cert
                                  :claim-results (claim-results* :claims [:claim/a]))
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :claim-results])))
      (is (= :fail (get-in r [:checks :claim-closure])))
      (let [closure-failure (some #(when (= :claim-closure (:check/id %)) (:check/reason %)) (:failures r))]
        (is (some #(= :claim/b %) (:uncovered closure-failure)))))))

(deftest extra-unexpected-claim-fails-verification
  (testing "an unexpected evaluated claim breaks exact-set claim coverage"
    (let [manifest (make-manifest [:claim/a])
          cert (make-cert manifest (claim-results* :claims [:claim/a]))
          evidence (make-evidence :manifest manifest
                                  :certification cert
                                  :claim-results (claim-results* :claims [:claim/a :claim/extra]))
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :claim-results]))))))

(deftest scenario-count-tampering-fails-verification
  (testing "a stored scenario-count that disagrees with the results is caught"
    (let [manifest (make-manifest)
          cert (make-cert manifest (claim-results*) 999)
          evidence (make-evidence :manifest manifest :certification cert)
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :scenario-count]))))))

(deftest invariant-summary-tampering-fails-verification
  (testing "a stored invariant-summary that disagrees with the results is caught"
    (let [manifest (make-manifest)
          cert (runner/build-certification manifest
                                           {:scenario-count 2 :all-invariants-pass true
                                            :invariant-summary {:conservation {:passed 1 :total 2}}}
                                           (claim-results*))
          evidence (make-evidence :manifest manifest :certification cert)
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :invariant-summary]))))))

(deftest all-invariants-pass-tampering-fails-verification
  (testing "a stored all-invariants-pass that contradicts the results is caught"
    (let [manifest (make-manifest)
          cert (runner/build-certification manifest
                                           {:scenario-count 2 :all-invariants-pass false
                                            :invariant-summary {:conservation {:passed 2 :total 2}}}
                                           (claim-results*))
          evidence (make-evidence :manifest manifest :certification cert)
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :all-invariants-pass]))))))

(deftest missing-evidence-hash-fails-closed
  (testing "an uncommitted bundle (no :evidence/hash) cannot verify"
    (let [evidence (dissoc (make-evidence) :evidence/hash)
          r (cv/verify-benchmark-certification evidence)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :evidence/integrity]))))))

(deftest fg-certification-hash-tampering-fails-verification
  (testing "mutating the committed :certification-hash breaks hash-integrity (and the bundle) "
    (let [evidence (make-evidence)
          tampered (update-in evidence [:benchmark-certification :certification-hash]
                              (constantly (apply str (repeat 64 "0"))))
          r (cv/verify-benchmark-certification tampered)]
      (is (false? (:verified? r)))
      (is (= :fail (get-in r [:checks :certification/hash-integrity])))
      (is (= :fail (get-in r [:checks :evidence/integrity]))
          "a corrupted committed hash must also break the bundle-root recomputation"))))

(deftest claim-outcome-policy-is-committed-not-implicit
  (testing "a non-passing claim outcome is rejected by default but explicitly permitted by a profile"
    (let [manifest (make-manifest)
          na-claims (claim-results* :outcome :not-applicable)
          cert (make-cert manifest na-claims)
          evidence (make-evidence :manifest manifest :certification cert :claim-results na-claims)
          default-result (cv/verify-benchmark-certification evidence)
          permissive (assoc-in cv/default-verification-profile
                               [:profile/claim-outcome-acceptance :not-applicable] :permit)
          permissive-result (cv/verify-benchmark-certification evidence permissive)]
      (is (false? (:verified? default-result))
          "default profile must not silently admit :not-applicable claims")
      (is (= :fail (get-in default-result [:checks :claim-closure])))
      (is (true? (:verified? permissive-result))
          "an explicitly committed policy may permit :not-applicable")
      (is (not= (:verification-profile/root default-result)
                (:verification-profile/root permissive-result))
          "acceptance policy is committed into the verification profile root"))))

(deftest verify-fail-closed-gate-throws
  (testing "the throwing gate rejects an unverifiable certification without modifying it"
    (let [evidence (make-evidence)
          tampered (update-in evidence [:benchmark-certification :certification-hash]
                              (constantly (apply str (repeat 64 "0"))))
          before (:benchmark-certification tampered)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cv/verify-benchmark-certification! tampered)))
      (is (= before (:benchmark-certification tampered))
          "the certification object is never mutated by verification"))))

(deftest run-benchmark-bundle-is-verifiable
  (testing "an evidence bundle produced by run-benchmark verifies independently"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit" :dirty? false}})
                  vcs/source-provenance (fn [] {:git-commit-sha "sha256:test-commit"
                                                :source/hash "sha256:test-source-hash"
                                                :source/hash-algorithm "source-tree-hash-v1"
                                                :source/hash-roots []
                                                :code-hash "sha256:test-code-hash"
                                                :deps-hash "sha256:test-deps-hash"
                                                :input-hash "sha256:test-input-hash"
                                                :dirty? false})
                  sew/replay-with-sew-protocol (fn [_scenario _opts]
                                                 {:events-processed 3
                                                  :outcome :pass
                                                  :halt-reason nil
                                                  :metrics {:invariant-results {}}
                                                  :world {:status :ok}})
                  sew-inv/check-all (fn [_world] {:results {}})]
      (let [evidence (runner/run-benchmark "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
            result (cv/verify-benchmark-certification evidence)]
        (is (true? (:verified? result)))
        (is (empty? (:failures result)))))))