(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence-v2-test
  "Tests for the pro-rata-execution-evidence.v2 profile.

   v2 fixes an overclaiming field name introduced in v1: the emitted
   :current-amount-write-back-verified? implied a verified per-obligation
   current-amount write-back, but actually carried the AGGREGATE operational
   write-back status (which the surrounding block acknowledges can pass for a
   zero or haircut result). v2 renames it to the explicitly operational
   :current-write-back-operationally-verified?, computes the operational pass
   predicate once (shared by :allocation-sound? and the emitted fact), keeps the
   weaker operational / stronger authoritative distinction intact, and emits the
   un-establishable amount/full-fill/residual settlement facts as explicit
   :...-unevaluated? markers rather than hardcoded false.

   v1 is retained unchanged for backward compatibility with existing canonical
   artifacts; the two versions are distinct content-addressed schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as exec-ev]
            [resolver-sim.benchmark.packs.partial-fill.outcome :as outcome]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(defn- base-manifest [operational]
  (om/build-manifest
   {:benchmark/content-root "sha256:cr"
    :benchmark/model-root "sha256:mr"
    :benchmark/evaluation-policy-root "sha256:eval"
    :execution/status :completed
    :results/operational operational}))

(defn- v2-args [operational]
  {:benchmark-content-root "sha256:cr" :model-root "sha256:mr"
   :outcome-manifest (base-manifest operational)
   :allocation-evidence-hash "sha256:alloc-ev"
   :application-evidence-hash "sha256:app-ev"
   :theorem-outcomes [] :conclusions []})

(defn- pass-operational []
  {:conservation :pass :quota-bounded :pass :current-amount-write-back :pass
   :authoritative-application :pass})

(deftest v2-emits-operationally-named-field-not-v1-key
  (let [p (exec-ev/build-pro-rata-execution-evidence-v2 (v2-args (pass-operational)))
        er (:evidence-profile/execution-result p)]
    (is (= "pro-rata-execution-evidence.v2" (:schema-version p)))
    (is (= true (:current-write-back-operational-pass? er))
        "operational write-back passes -> operational-pass flag true")
    (is (not (contains? er :current-amount-write-back-verified?))
        "v2 must not carry the overclaiming v1 key")
    (is (= true (:allocation-sound? er))
        "allocation-sound? shares the same operational write-back predicate")))

(deftest v2-keeps-operational-authoritative-distinction
  ;; The weak operational fact and the strong authoritative fact are separate:
  ;; operational write-back passes while authoritative application is weaker in
  ;; a fail scenario, and the emitted flags differ accordingly (mindful of the
  ;; fact that in this aggregate profile positive-amount/full-fill/residual are
  ;; deliberately false).
  (testing "weaker operational, stronger authoritative both fail"
    (let [p (exec-ev/build-pro-rata-execution-evidence-v2
             (v2-args {:conservation :fail :quota-bounded :fail
                       :current-amount-write-back :fail
                       :authoritative-application :fail}))
          er (:evidence-profile/execution-result p)]
      (is (= false (:current-write-back-operational-pass? er)))
      (is (= false (:allocation-sound? er)))
      (is (= false (:application-write-back-verified? er)))))
  (testing "operational passes while authoritative stricter check fails"
    (let [p (exec-ev/build-pro-rata-execution-evidence-v2
             ;; conservative+quota+current-amount-write-back pass, but the
             ;; authoritative application (withdrawn AND position verified) fails
             (v2-args {:conservation :pass :quota-bounded :pass
                       :current-amount-write-back :pass
                       :authoritative-application :fail}))
          er (:evidence-profile/execution-result p)]
      (is (= true (:current-write-back-operational-pass? er))
          "operational flag tracks the weak operational status")
      (is (= false (:application-write-back-verified? er))
          "authoritative flag stays separate and stronger"))))

(deftest v2-emits-unevaluated-markers-not-factual-false
  (let [er (:evidence-profile/execution-result
            (exec-ev/build-pro-rata-execution-evidence-v2 (v2-args (pass-operational))))]
    (is (= true (:positive-amount-applied-unevaluated? er))
        "amount fact is explicitly unevaluated, not hardcoded false")
    (is (= true (:fully-satisfied-unevaluated? er)))
    (is (= true (:deferred-residual-created-unevaluated? er)))
    (is (not (contains? er :positive-amount-applied?))
        "the aggregate must never emit the factual :positive-amount-applied?")))

(deftest v2-hash-recomputes-under-v2-domain-tag
  (let [args (v2-args (pass-operational))
        p (exec-ev/build-pro-rata-execution-evidence-v2 args)
        h (hc/domain-hash :pro-rata-execution-evidence-v2
                          (dissoc p :evidence-profile/hash))]
    (is (= (str "sha256:" h) (:evidence-profile/hash p)))
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence-v2 p)))
    (is (:valid? (exec-ev/verify-pro-rata-execution-evidence-v2 p args)))))

(deftest v2-verifies-out-of-band-provenance
  (testing "out-of-band profile reproduces its hash under verification"
    (let [args (v2-args (pass-operational))]
      (let [p-out (exec-ev/build-pro-rata-execution-evidence-v2
                   (assoc args :creation/provenance :out-of-band))
            p-in  (exec-ev/build-pro-rata-execution-evidence-v2 args)]
        (is (= :out-of-band (get-in p-out [:evidence-profile/creation :provenance])))
        (is (= :in-band (get-in p-in [:evidence-profile/creation :provenance])))
        (is (:valid? (exec-ev/verify-pro-rata-execution-evidence-v2 p-out args))
            "out-of-band profile verifies when provenance is re-extracted")
        (is (:valid? (exec-ev/verify-pro-rata-execution-evidence-v2 p-in args))
            "in-band profile verifies normally")
        (is (not= (:evidence-profile/hash p-out)
                  (:evidence-profile/hash p-in))
            "different provenance produces different profile hashes")))))

(deftest v2-verification-detects-tampered-provenance
  (testing "mutating stored :creation/provenance breaks verification"
    (let [args (v2-args (pass-operational))
          p (exec-ev/build-pro-rata-execution-evidence-v2
             (assoc args :creation/provenance :out-of-band))
          tampered (assoc-in p [:evidence-profile/creation :provenance] :in-band)
          result (exec-ev/verify-pro-rata-execution-evidence-v2 tampered args)]
      (is (not (:valid? result))
          "tampered provenance must fail verification")
      (is (some #(= :evidence-profile/hash (:field %))
                (:mismatches result))
          "mismatch must report :evidence-profile/hash")))
  (testing "rebuilt-with-declared-out-of-band args, not defaulted in-band"
    (let [args (v2-args (pass-operational))
          p (exec-ev/build-pro-rata-execution-evidence-v2
             (assoc args :creation/provenance :out-of-band))
          recomputed (:profile-recomputed
                       (exec-ev/verify-pro-rata-execution-evidence-v2 p args))]
      (is (= :out-of-band
             (get-in recomputed [:evidence-profile/creation :provenance]))
          "verifier must reconstruct with stored provenance, not default :in-band"))))

(deftest v2-rejects-v1-key-under-v2-schema
  (let [args (v2-args (pass-operational))
        p (exec-ev/build-pro-rata-execution-evidence-v2 args)
        legacy-shaped (update-in p [:evidence-profile/execution-result]
                                 (fn [er] (-> er
                                              (dissoc :current-write-back-operational-pass?)
                                              (assoc :current-amount-write-back-verified? true))))
        v (exec-ev/validate-pro-rata-execution-evidence-v2 legacy-shaped)]
    (is (not (:valid? v)))
    (is (some #(re-find #"must not carry the v1" %) (:errors v)))))

(deftest v1-and-v2-are-distinct-versioned-artifacts
  (let [args (v2-args (pass-operational))
        v1 (exec-ev/build-pro-rata-execution-evidence args)
        v2 (exec-ev/build-pro-rata-execution-evidence-v2 args)]
    (is (= "pro-rata-execution-evidence.v1" (:schema-version v1)))
    (is (= "pro-rata-execution-evidence.v2" (:schema-version v2)))
    (is (not= (:evidence-profile/hash v1) (:evidence-profile/hash v2))
        "the two versions are distinct content-addressed artifacts")
    (is (not (contains? (:evidence-profile/execution-result v2)
                        :current-amount-write-back-verified?))
        "v2 must not carry the legacy v1 key")
    (is (contains? (:evidence-profile/execution-result v1)
                   :current-amount-write-back-verified?)
        "sanity: v1 does carry the v1 key")
    (is (contains? (:evidence-profile/execution-result v2)
                   :current-write-back-operational-pass?)
        "v2 carries the operational-pass field")))

(deftest v2-validator-version-dispatch
  (let [args (v2-args (pass-operational))
        p-v2 (exec-ev/build-pro-rata-execution-evidence-v2 args)
        with-v2-on-legacy (assoc (exec-ev/build-pro-rata-execution-evidence args)
                                 :evidence-profile/execution-result
                                 {:current-write-back-operational-pass? true})]
    (is (:valid? (exec-ev/validate-pro-rata-execution-evidence-any p-v2))
        "v2 dispatches to the v2 validator")
    (is (not (:valid? (exec-ev/validate-pro-rata-execution-evidence-any with-v2-on-legacy)))
        "v1 schema rejects the v2 field (v2-field-on-v1)")))

(deftest v2-package-requires-declarative-marker-not-inferred
  (let [manifest-with-marker (assoc (base-manifest {:conservation :pass})
                                    :outcomes/pro-rata-evidence-required true)
        manifest-without-marker (dissoc (base-manifest {:conservation :pass})
                                        :results/operational)]
    (is (exec-ev/package-requires-pro-rata-evidence? manifest-with-marker)
        "explicit marker declares requirement")
    (is (not (exec-ev/package-requires-pro-rata-evidence? manifest-without-marker))
        "absence of marker fails closed — no inference from optional fields")))

(deftest v2-theorem-binding-fails-with-empty-supplied
  (let [manifest (om/build-manifest
                  {:benchmark/content-root "sha256:cr"
                   :benchmark/model-root "sha256:mr"
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :outcomes/theorems [{:theorem/id :t1 :theorem/hash "sha256:t1"}]
                   :results/operational {:conservation :pass}})
        p (exec-ev/build-pro-rata-execution-evidence-v2
           {:benchmark-content-root "sha256:cr"
            :model-root "sha256:mr"
            :outcome-manifest manifest
            :allocation-evidence-hash "sha256:a"
            :application-evidence-hash "sha256:b"
            :theorem-outcomes []
            :conclusions []})
        v (:evidence-profile/verification p)]
    (is (= false (:theorem-binding-valid? v))
        "empty supplied evidence must NOT satisfy non-empty manifest theorems")))

(deftest v2-conclusion-binding-fails-with-empty-supplied
  (let [manifest (om/build-manifest
                  {:benchmark/content-root "sha256:cr"
                   :benchmark/model-root "sha256:mr"
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :outcomes/conclusions [{:conclusion/id :c1 :conclusion/hash "sha256:c1"}]
                   :results/operational {:conservation :pass}})
        p (exec-ev/build-pro-rata-execution-evidence-v2
           {:benchmark-content-root "sha256:cr"
            :model-root "sha256:mr"
            :outcome-manifest manifest
            :allocation-evidence-hash "sha256:a"
            :application-evidence-hash "sha256:b"
            :theorem-outcomes []
            :conclusions []})
        v (:evidence-profile/verification p)]
    (is (= false (:conclusion-binding-valid? v))
        "empty supplied evidence must NOT satisfy non-empty manifest conclusions")))

(deftest v2-binding-passes-when-supplied-matches-manifest
  (let [manifest (om/build-manifest
                  {:benchmark/content-root "sha256:cr"
                   :benchmark/model-root "sha256:mr"
                   :benchmark/evaluation-policy-root "sha256:eval"
                   :execution/status :completed
                   :outcomes/theorems [{:theorem/id :t1 :theorem/hash "sha256:t1"}]
                   :outcomes/conclusions [{:conclusion/id :c1 :conclusion/hash "sha256:c1"}]
                   :results/operational {:conservation :pass}})
        p (exec-ev/build-pro-rata-execution-evidence-v2
           {:benchmark-content-root "sha256:cr"
            :model-root "sha256:mr"
            :outcome-manifest manifest
            :allocation-evidence-hash "sha256:a"
            :application-evidence-hash "sha256:b"
            :theorem-outcomes [{:theorem/id :t1 :theorem/hash "sha256:t1" :status :established}]
            :conclusions [{:conclusion/id :c1 :conclusion/hash "sha256:c1"}]})
        v (:evidence-profile/verification p)]
    (is (= true (:theorem-binding-valid? v)))
    (is (= true (:conclusion-binding-valid? v)))))

(deftest cross-artifact-funded-pro-rata-never-lies-false
  (testing "A genuinely funded pro-rata execution never reports :positive-amount-applied? false"
    (let [args (v2-args (pass-operational))
          v1 (exec-ev/build-pro-rata-execution-evidence args)
          v2 (exec-ev/build-pro-rata-execution-evidence-v2 args)
          decision (outcome/normalise-decision-outcome
                    {:decision/id :funded
                     :evidence {:allocation-rows [{:key :a :owed 100 :filled 60}]}})
          participant (first (:participants decision))
          artifacts [{:kind :decision-outcome :value participant}
                     {:kind :evidence-v1 :value (:evidence-profile/execution-result v1)}
                     {:kind :evidence-v2 :value (:evidence-profile/execution-result v2)}]]
      (is (true? (:allocation/positive-amount-applied? participant))
          "funded decision row reports positive-amount-applied? true")
      (is (false? (:allocation/fully-satisfied? participant))
          "60 of 100 is a genuine partial fill - truthfully not fully satisfied")
      (doseq [{:keys [kind value]} artifacts]
        (let [applied (or (:allocation/positive-amount-applied? value)
                          (:positive-amount-applied? value))
              unevaluated (or (:allocation/positive-amount-applied-unevaluated? value)
                              (:positive-amount-applied-unevaluated? value))]
          (is (or (true? applied) (true? unevaluated))
              (str kind " must be true or explicitly unevaluated, never false"))
          (is (not= false applied)
              (str kind " must not claim the factual :positive-amount-applied? as false"))))
      (is (:valid? (exec-ev/validate-pro-rata-execution-evidence-any v1)))
      (is (:valid? (exec-ev/validate-pro-rata-execution-evidence-any v2))))))