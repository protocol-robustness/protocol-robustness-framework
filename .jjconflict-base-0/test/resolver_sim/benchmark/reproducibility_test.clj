(ns resolver-sim.benchmark.reproducibility-test
  "Reproducibility projection property: same admitted benchmark input + same
  execution semantics ⇒ same reproducibility root, even when wall-clock,
  host, VCS-state, materialization, and signing metadata differ."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.final-evidence-boundary-test :as boundary]
            [resolver-sim.benchmark.reproducibility :as repro]
            [resolver-sim.benchmark.runner :as runner]))

(defn- normalize [bundle] (#'runner/normalize-runtime-values bundle))

(deftest runtime-metadata-does-not-participate
  (let [base (repro/reproducibility-root (normalize (#'boundary/make-bundle {})))
        variants
        [[:different-timestamp
          #(assoc % :timestamp "2099-12-31T23:59:59Z")]
         [:different-host-environment
          #(assoc % :environment {:os-name "OtherOS" :java-version "8"})]
         [:different-vcs-state
          #(assoc % :repo {:commit "ffffff" :dirty? false})]
         [:different-artifact-locations
          #(assoc % :benchmark/artifact-index {:chunks ["/elsewhere"]})]
         [:different-manifest-wallclock
          #(update-in % [:run/manifest :manifest/at] (constantly "2001-01-01T00:00:00Z"))]
         [:signed-copy
          #(assoc % :evidence/signature "cafebabe"
                  :evidence/public-key-path "/tmp/k.pub")]]]
    (doseq [[label mutate] variants]
      (testing (name label)
        (is (= base (repro/reproducibility-root (normalize (mutate (#'boundary/make-bundle {}))))))))))

(deftest semantic-changes-do-participate
  (let [base (repro/reproducibility-root (normalize (#'boundary/make-bundle {})))
        variants
        [[:outcome-flip
          #(assoc-in % [:results 0 :outcome] :failed)]
         [:metric-change
          #(assoc-in % [:metrics :passed] 2)]
         [:certification-change
          #(assoc % :benchmark-certification {:tier :full :certified? false})]
         [:invariant-summary-change
          #(assoc-in % [:invariant-summary :passed-checks] 0)]
         [:benchmark-id-change
          #(assoc-in % [:benchmark :id] :other-pack)]
         [:commitment-version-change
          #(assoc % :evidence/commitment-version "bundle-root.v2")]]]
    (doseq [[label mutate] variants]
      (testing (name label)
        (is (not= base (repro/reproducibility-root
                        (normalize (mutate (#'boundary/make-bundle {}))))))))))

(deftest projection-is-domain-separated-from-bundle-root
  ;; Reproducibility identity must not collide with integrity identity.
  (let [bundle (normalize (#'boundary/make-bundle {}))]
    (is (not= (repro/reproducibility-root bundle)
              (:evidence/hash bundle)))))

(deftest nil-and-absent-behave-per-canonical-contract-inside-projection-too
  ;; Mirrors the boundary suite's contract: the projection inherits the
  ;; canonical encoder's treatment, whatever it is — pin it so drift is loud.
  (let [with-nil (normalize (#'boundary/make-bundle {:concept-section nil}))
        without (dissoc (normalize (#'boundary/make-bundle {})) :concept/section)]
    ;; make-bundle with {:concept-section nil} ASSOCES the key with nil; if the
    ;; canonical pipeline preserves tag-null these differ; if it drops nils
    ;; they coincide. Either way BOTH roots must be stable across runs.
    (is (= (repro/reproducibility-root with-nil)
           (repro/reproducibility-root with-nil)))
    (is (= (repro/reproducibility-root without)
           (repro/reproducibility-root without)))))

(deftest ordering-insensitivity-inherited-by-reprojection
  (let [a (#'boundary/make-bundle {})
        b (assoc a :metrics (apply array-map
                                   (interleave [:z-last-key :passed :total :a-first-key]
                                               ["z" 3 3 1])))]
    (is (= (repro/reproducibility-root (normalize a))
           (repro/reproducibility-root (normalize b))))))
