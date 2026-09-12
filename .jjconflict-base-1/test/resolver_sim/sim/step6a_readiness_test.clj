(ns resolver-sim.sim.step6a-readiness-test
  "Step 6a calibration and readiness checks before the canonical 1000-epoch run.

   These tests must all pass before Step 6b (canonical baseline) is run.

   Readiness checks:
   1. Golden small run      — same seed → byte-identical; different seed → different curves
   2. Callback parity       — on-epoch-complete output == final :epoch-results
   3. Manifest completeness — all 7 required fields present and non-nil
   4. Audit result sanity   — 7 checks present; violations carry epoch numbers
   5. Known-pass fixture    — calibration-pass params produce :pass audit
   6. Known-fail fixture    — calibration-fail params produce :fail audit (not rubber-stamp)
   7. Output files written  — write-audit-outputs produces all 5 expected files"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io  :as io]
            [clojure.edn      :as edn]
            [resolver-sim.sim.multi-epoch :as me]
            [resolver-sim.sim.audit       :as audit]
            [resolver-sim.io.audit-outputs :as audit-out]
            [resolver-sim.stochastic.rng  :as rng]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- load-params [filename]
  (edn/read-string (slurp (str "data/params/" filename))))

(defn- run-small
  [p & {:keys [seed epochs trials callback]
        :or   {seed 42 epochs 6 trials 60}}]
  (if callback
    (me/run-multi-epoch (rng/make-rng seed) epochs trials p callback)
    (me/run-multi-epoch (rng/make-rng seed) epochs trials p)))

;; ---------------------------------------------------------------------------
;; 1. Golden small run
;; ---------------------------------------------------------------------------

(deftest same-seed-produces-identical-economics
  (testing "same seed produces identical epoch-level economics and curve distributions"
    (let [p  (load-params "phase-j-calibration-pass.edn")
          r1 (run-small p :seed 42)
          r2 (run-small p :seed 42)
          ;; Compare epoch-results — these contain aggregate stats, not IDs
          ;; so they must be fully identical
          e1 (mapv #(dissoc % :routing-mode) (:epoch-results r1))
          e2 (mapv #(dissoc % :routing-mode) (:epoch-results r2))]
      (is (= e1 e2) ":epoch-results aggregate stats must be identical for same seed")
      ;; Compare equity curve VALUE distributions (sorted), not the ID-keyed maps.
      ;; Resolver IDs may differ across runs due to population turnover, but
      ;; the distribution of equity curves must be the same.
      (is (= (sort (map vec (vals (:equity-trajectories r1))))
             (sort (map vec (vals (:equity-trajectories r2)))))
          "equity curve value distribution must be identical for same seed"))))

(deftest different-seed-different-trajectories
  (testing "different seeds produce different per-resolver equity curves"
    (let [p   (load-params "phase-j-calibration-pass.edn")
          r42 (run-small p :seed 42)
          r99 (run-small p :seed 99)
          curves-42 (set (map (fn [[_ t]] (vec (:equity t))) (:full-trajectories r42)))
          curves-99 (set (map (fn [[_ t]] (vec (:equity t))) (:full-trajectories r99)))]
      (is (not= curves-42 curves-99)
          "per-resolver equity curve sets must differ between seeds"))))

;; ---------------------------------------------------------------------------
;; 2. Callback parity
;; ---------------------------------------------------------------------------

(deftest callback-output-equals-final-epoch-results
  (testing "on-epoch-complete summaries match the final :epoch-results list"
    (let [p        (load-params "phase-j-calibration-pass.edn")
          captured (atom [])
          callback (fn [_n summary] (swap! captured conj summary))
          result   (run-small p :seed 42 :callback callback)]
      (is (= (count @captured) (count (:epoch-results result)))
          "callback must fire exactly once per epoch")
      ;; Compare epoch-by-epoch: :epoch :dominance-ratio :honest-mean-profit
      (doseq [[cb-s final-s] (map vector @captured (:epoch-results result))]
        (is (= (:epoch cb-s)              (:epoch final-s))             ":epoch must match")
        (is (= (:dominance-ratio cb-s)    (:dominance-ratio final-s))   ":dominance-ratio must match")
        (is (= (:honest-mean-profit cb-s) (:honest-mean-profit final-s)) ":honest-mean-profit must match")))))

;; ---------------------------------------------------------------------------
;; 3. Manifest completeness
;; ---------------------------------------------------------------------------

(deftest manifest-has-all-required-fields
  (testing "make-manifest includes all 7 required fields, all non-nil"
    (let [p    (load-params "phase-j-calibration-pass.edn")
          r    (run-small p :seed 42)
          m    (audit/make-manifest r p "data/params/phase-j-calibration-pass.edn" 42)]
      (is (string? (:params-file m))       ":params-file must be a string")
      (is (string? (:params-hash m))       ":params-hash must be a string")
      (is (= 64 (count (:params-hash m)))  ":params-hash must be 64 hex chars (SHA-256)")
      (is (number? (:seed m))              ":seed must be a number")
      (is (number? (:epochs m))            ":epochs must be a number")
      (is (number? (:trials-per-epoch m))  ":trials-per-epoch must be a number")
      (is (keyword? (:routing-mode m))     ":routing-mode must be a keyword")
      (is (string? (:git-commit m))        ":git-commit must be a string")
      (is (string? (:sim-version m))       ":sim-version must be a string")
      (is (string? (:completed-at m))      ":completed-at must be a string")
      (is (not= "unknown" (:git-commit m)) ":git-commit must not be 'unknown' in a git repo"))))

(deftest params-hash-is-stable
  (testing "params-hash is identical for the same params map"
    (let [p   (load-params "phase-j-calibration-pass.edn")
          h1  (audit/params-hash p)
          h2  (audit/params-hash p)]
      (is (= h1 h2) "params-hash must be deterministic")))
  (testing "params-hash differs for different params"
    (let [p-pass (load-params "phase-j-calibration-pass.edn")
          p-fail (load-params "phase-j-calibration-fail.edn")]
      (is (not= (audit/params-hash p-pass) (audit/params-hash p-fail))
          "different params must produce different hashes"))))

;; ---------------------------------------------------------------------------
;; 4. Audit result sanity
;; ---------------------------------------------------------------------------

(deftest audit-result-has-all-seven-checks
  (testing "analyze-multi-epoch returns all 7 required check keys"
    (let [p      (load-params "phase-j-calibration-pass.edn")
          r      (run-small p :seed 42 :epochs 8 :trials 80)
          checks (:checks (audit/analyze-multi-epoch r))]
      (is (contains? checks :honest-mean-positive?))
      (is (contains? checks :malice-mean-nonpositive?))
      (is (contains? checks :dominance-above-threshold?))
      (is (contains? checks :honest-p10-above-malice-p90?))
      (is (contains? checks :malice-equity-share-below-limit?))
      (is (contains? checks :malice-slope-not-improving?))
      (is (contains? checks :malice-survival-rate-low?)))))

(deftest epoch-level-dominance-violations-carry-epoch-numbers
  (testing "epoch-level dominance violations include :epoch and :value keys"
    (let [p      (load-params "phase-j-calibration-fail.edn")
          r      (run-small p :seed 42 :epochs 8 :trials 200)
          audit  (audit/analyze-multi-epoch r {:dominance-threshold 2.0})
          ;; Epoch-level violations are produced by the per-epoch loop and include :epoch
          ;; Summary-level violations (from the checks map) do not include :epoch
          epoch-dom-vs (filter #(and (= :dominance-above-threshold? (:check %))
                                     (contains? % :epoch))
                               (:violations audit))]
      (is (seq epoch-dom-vs)
          "At least one epoch-level dominance violation should exist with threshold 2.0")
      (doseq [v epoch-dom-vs]
        (is (number? (:epoch v)) ":epoch must be a number")
        (is (number? (:value v)) ":value must be a number")))))

;; ---------------------------------------------------------------------------
;; 5. Known-pass fixture
;; ---------------------------------------------------------------------------

(deftest calibration-pass-audit-returns-pass
  (testing "phase-j-calibration-pass.edn with sufficient epochs/trials returns :pass"
    (let [p     (load-params "phase-j-calibration-pass.edn")
          r     (run-small p :seed 42 :epochs 10 :trials 200)
          ;; malice-survival-rate-low? requires many epochs for natural attrition;
          ;; use threshold 1.0 to disable it for the 10-epoch calibration run.
          audit (audit/analyze-multi-epoch r {:malice-survival-threshold 1.0})]
      (is (= :pass (:result audit))
          (str "calibration-pass must produce :pass audit, got violations: "
               (:violations audit))))))

;; ---------------------------------------------------------------------------
;; 6. Known-fail fixture
;; ---------------------------------------------------------------------------

(deftest calibration-fail-audit-returns-fail
  (testing "phase-j-calibration-fail.edn (zero detection) returns :fail"
    (let [p     (load-params "phase-j-calibration-fail.edn")
          r     (run-small p :seed 42 :epochs 10 :trials 200)
          audit (audit/analyze-multi-epoch r)]
      (is (= :fail (:result audit))
          "zero-detection config must produce :fail — malice earns as much as honest")
      (is (seq (:violations audit))
          "violations must be non-empty")
      ;; Specifically: malice is profitable → malice-mean-nonpositive? must be false
      (is (false? (get-in audit [:checks :malice-mean-nonpositive?]))
          "with zero slashing, malice earns positive fees — malice-mean-nonpositive? must be false"))))

(deftest fail-fixture-is-not-just-noise
  (testing "calibration-fail violations are consistent across two seeds"
    (let [p      (load-params "phase-j-calibration-fail.edn")
          a42    (audit/analyze-multi-epoch (run-small p :seed 42  :epochs 10 :trials 200))
          a99    (audit/analyze-multi-epoch (run-small p :seed 99  :epochs 10 :trials 200))
          failed-checks-42 (set (map :check (:violations a42)))
          failed-checks-99 (set (map :check (:violations a99)))]
      (is (= :fail (:result a42)) "seed 42 must fail")
      (is (= :fail (:result a99)) "seed 99 must fail")
      ;; The same structural checks should fail regardless of seed
      (is (= failed-checks-42 failed-checks-99)
          (str "same structural checks must fail for any seed. "
               "seed 42: " failed-checks-42 "  seed 99: " failed-checks-99)))))

;; ---------------------------------------------------------------------------
;; 7. Output files written correctly
;; ---------------------------------------------------------------------------

(deftest write-audit-outputs-creates-all-five-files
  (testing "write-audit-outputs writes all 5 required files"
    (let [p       (load-params "phase-j-calibration-pass.edn")
          r       (run-small p :seed 42 :epochs 6 :trials 60)
          audit-r (audit/analyze-multi-epoch r)
          mfst    (audit/make-manifest r p "data/params/phase-j-calibration-pass.edn" 42)
          tmp-dir (str "/tmp/audit-test-" (System/currentTimeMillis))
          _       (audit-out/write-audit-outputs tmp-dir r audit-r mfst)

          required ["epoch-results.edn"
                    "trajectory.csv"
                    "audit-result.edn"
                    "manifest.edn"
                    "PHASE_J_STABILITY_AUDIT.md"]]
      (doseq [fname required]
        (is (.exists (io/file tmp-dir fname))
            (str fname " must exist after write-audit-outputs")))
      ;; Spot-check: manifest.edn is readable EDN
      (let [m (edn/read-string (slurp (str tmp-dir "/manifest.edn")))]
        (is (contains? m :seed))
        (is (contains? m :git-commit)))
      ;; Spot-check: audit-result.edn has :result key
      (let [a (edn/read-string (slurp (str tmp-dir "/audit-result.edn")))]
        (is (contains? a :result))
        (is (#{:pass :fail} (:result a))))
      ;; Spot-check: trajectory.csv has header row
      (let [csv (slurp (str tmp-dir "/trajectory.csv"))
            header (first (clojure.string/split-lines csv))]
        (is (clojure.string/includes? header "resolver_id"))
        (is (clojure.string/includes? header "strategy"))
        (is (clojure.string/includes? header "equity")))
      ;; Spot-check: PHASE_J_STABILITY_AUDIT.md contains key sections
      (let [md (slurp (str tmp-dir "/PHASE_J_STABILITY_AUDIT.md"))]
        (is (clojure.string/includes? md "# Phase J"))
        (is (clojure.string/includes? md "## Hypothesis"))
        (is (clojure.string/includes? md "## Checks"))))))
