(ns resolver-sim.economics.with-bounty.verification-basis-test
  "Pre-C2 review R1: the verification basis is a first-class, versioned,
   content-addressed artifact committed into every verifier attestation."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.with-bounty.verification-basis :as basis]))

(def sample-basis
  (basis/build-verification-basis
   {:subject-root "sha256:subject"
    :package-root "sha256:package"
    :artifact-root "sha256:artifact"
    :verification-contract :prf/with-bounty-verification.v1
    :verification-contract-version 1
    :entrypoint "resolver-sim.economics.with-bounty.proof/evaluate-bounty"
    :invocation-parameters {:base-result-root "sha256:base"}
    :dependency-lockfile-root "sha256:lockfile"
    :runtime-root "sha256:runtime"
    :environment-root "sha256:environment"
    :vector-set-root "sha256:vectors"
    :resource-limit-profile {:timeout-ms 10000 :memory-mb 1024}
    :expected-public-result-schema :prf/with-bounty-public-result.v1
    :classification-policy-root "sha256:policy"}))

(deftest basis-builds-and-verifies
  (is (= "with-bounty-verification-basis.v1" (:schema-version sample-basis)))
  (is (= 64 (count (:basis/root sample-basis))))
  (is (:valid? (basis/validate-verification-basis sample-basis)))
  (is (:valid? (basis/verify-verification-basis sample-basis))))

(deftest basis-root-deterministic-and-sensitive
  (is (= (:basis/root sample-basis)
         (:basis/root (basis/build-verification-basis
                       {:subject-root "sha256:subject"
                        :package-root "sha256:package"
                        :artifact-root "sha256:artifact"
                        :verification-contract :prf/with-bounty-verification.v1
                        :verification-contract-version 1
                        :entrypoint "resolver-sim.economics.with-bounty.proof/evaluate-bounty"
                        :invocation-parameters {:base-result-root "sha256:base"}
                        :dependency-lockfile-root "sha256:lockfile"
                        :runtime-root "sha256:runtime"
                        :environment-root "sha256:environment"
                        :vector-set-root "sha256:vectors"
                        :resource-limit-profile {:timeout-ms 10000 :memory-mb 1024}
                        :expected-public-result-schema :prf/with-bounty-public-result.v1
                        :classification-policy-root "sha256:policy"}))))
  (let [different (basis/build-verification-basis
                   (assoc (select-keys sample-basis
                                       [:basis/subject-root :basis/package-root
                                        :basis/artifact-root :basis/verification-contract
                                        :basis/verification-contract-version :basis/entrypoint
                                        :basis/invocation-parameters
                                        :basis/dependency-lockfile-root :basis/runtime-root
                                        :basis/environment-root :basis/vector-set-root
                                        :basis/resource-limit-profile
                                        :basis/expected-public-result-schema
                                        :basis/classification-policy-root])
                          :basis/vector-set-root "sha256:other-vectors"))]
    (is (not= (:basis/root sample-basis) (:basis/root different)))))

(deftest basis-exact-shape-and-tamper-detection
  (is (not (:valid? (basis/validate-verification-basis
                     (assoc sample-basis :basis/unknown "extra")))))
  (is (not (:valid? (basis/verify-verification-basis
                     (assoc sample-basis :basis/root "ignored"))))))

(deftest documented-basis-roots-are-required
  (doseq [field [:basis/artifact-root
                 :basis/environment-root
                 :basis/classification-policy-root]]
    (is (not (:valid? (basis/validate-verification-basis
                       (assoc sample-basis field nil))))
        (str field " must be mandatory"))))
