(ns resolver-sim.benchmark.certification-verification
  "Independent verification of a benchmark certification.

   benchmark-certification is a write-once, self-produced producer artifact: it is
   constructed at bundle construction time by the benchmark runner and MUST NOT be
   replaced, amended, or re-issued in place by later verification, review,
   admission, researcher consensus, or signing. Post-run processes produce NEW
   artifacts that reference the immutable certification rather than editing it.

   This namespace is the first such post-run primitive:
   verify-benchmark-certification independently re-derives everything the stored
   certification claims from the evidence bundle's primary artifacts
   (:results, :claim-results, the benchmark manifest) under a committed
   verification profile, then compares the re-derivation to the stored
   certification. It never trusts the stored :all-invariants-pass,
   :invariant-summary, count, or claim commitments as justification.

   The result is an immutable, append-only outcome:
     benchmark-certification-verification.v1
   which commits the evidence root, the certification root, the verification
   profile root, a per-check verdict map, and an explicit :verified? predicate.

   Provenance model (machine-readable, not inferred from control flow):
     certification  -> :certification/creation {:provenance :in-band
                                                :producer :benchmark-runner}
     verification   -> :verification/creation {:provenance :out-of-band
                                               :producer :benchmark-certification-verifier}"
  (:require [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.hash.canonical :as hc]))

;; ── Verification profile ──────────────────────────────────────────────────────
;; The profile commits the acceptance policy the verifier applies. Default policy
;; is fail-closed: certification success is impossible while any required claim is
;; :fail, :not-implemented, :not-exercised, :error, or otherwise not explicitly
;; permitted by the profile. Permitting an outcome is an explicit, committed
;; decision — never an implicit default.

(def ^:const verification-profile-schema
  "benchmark-certification-verification-profile.v1")

(def ^:const verification-schema
  "benchmark-certification-verification.v1")

(def default-verification-profile
  {:verification-profile/schema verification-profile-schema
   :verification-profile/id :verify-benchmark-certification.v1
   :profile/claim-outcome-acceptance
   {:pass            :permit
    :fail            :reject
    :not-applicable  :reject
    :unsupported     :reject
    :not-evaluated   :reject
    :not-implemented :reject
    :not-exercised   :reject
    :error           :reject}
   :profile/certification-required-fields
   [:certification/schema :certification/creation
    :benchmark-id :scenario-count :all-invariants-pass
    :invariant-summary :required-claims-covered?
    :claim-set/root :claim-outcome/root :certification-hash]})

(defn profile-root
  "Domain-separated commitment to a verification profile. The profile's own
   :verification-profile/root field is excluded so the root is self-consistent."
  [profile]
  (hc/domain-hash verification-profile-schema
                  (dissoc profile :verification-profile/root)))

;; ── Independent derivation ────────────────────────────────────────────────────
;; Everything below is recomputed from the evidence's primary artifacts; nothing
;; is read from the certification being verified.

(defn derived-invariant-summary
  "Re-derive the per-invariant pass/total summary and all-pass? from the scenario
   results' :invariant-results. Mirrors the runner's aggregation exactly."
  [results]
  (let [all-inv (mapcat :invariant-results results)
        passed  (count (filter #(= :pass (:result %)) all-inv))
        ids     (distinct (map :id all-inv))]
    {:summary (into {}
                    (map (fn [id]
                           [id {:passed (count (filter #(and (= id (:id %))
                                                             (= :pass (:result %)))
                                                       all-inv))
                                :total  (count (filter #(= id (:id %)) all-inv))}]))
                    ids)
     :total-checks (count all-inv)
     :passed-checks passed
     :all-pass? (= (count all-inv) passed)}))

(defn- add-check
  "Accumulate one check verdict into a {checks failures} accumulator."
  [acc id ok? reason]
  (-> acc
      (assoc-in [:checks id] (if ok? :pass :fail))
      (cond-> (not ok?) (update :failures conj {:check/id id :check/reason reason}))))

(defn verification-checks
  "Independently derive every claim the certification makes and compare against
   the stored certification under the given profile.

   Returns {:checks {<check-kw> :pass|:fail} :failures [<map>]}. Every core check
   re-derives from evidence; :certification/hash-integrity and
   :certification/bundle-binding additionally confirm the certification object
   binds to the evidence bundle and recomputes its own committed hash."
  [evidence certification profile]
  (let [manifest (:benchmark evidence)
        results (vec (:results evidence))
        claim-results (vec (:claim-results evidence))
        metrics (:metrics evidence)
        inv (derived-invariant-summary results)
        required-ids (runner/required-claim-ids manifest)
        required-set (set required-ids)
        evaluated-set (set (map :claim/id claim-results))
        derived-covered? (runner/required-claims-covered? required-ids claim-results)
        derived-claim-set-root (runner/claim-set-root required-ids)
        derived-claim-outcome-root (runner/claim-outcome-root
                                    (runner/claim-outcome-projection claim-results))
        stored-cert-hash (:certification-hash certification)
        expected-cert-hash (hc/hash-with-intent {:hash/intent :benchmark-certification}
                                                (dissoc certification :certification-hash))
        bundle-check (integrity/verify-bundle-hash evidence)
        acceptance (:profile/claim-outcome-acceptance profile)
        missing-required-fields (->> (:profile/certification-required-fields profile)
                                     (remove #(contains? certification %)) vec)
        uncovered-claims (remove evaluated-set required-ids)
        extra-claims (remove required-set evaluated-set)
        rejected-claims (->> required-ids
                             (keep (fn [id]
                                     (when-let [outcome (:claim/outcome
                                                         (first (filter #(= id (:claim/id %)) claim-results)))]
                                       (when-not (= :permit (get acceptance outcome))
                                         {:claim/id id :claim/outcome outcome})))) vec)]
    (-> {:checks {} :failures []}
        (add-check :evidence/integrity (:hash-ok? bundle-check)
                   {:bundle/reason (:reason bundle-check)
                    :bundle/scheme (:scheme bundle-check)})
        (add-check :certification/bundle-binding (= certification (:benchmark-certification evidence))
                   {:stored-hash stored-cert-hash
                    :bundle-certification-hash (:certification-hash (:benchmark-certification evidence))})
        (add-check :certification/hash-integrity (and (string? stored-cert-hash)
                                                      (= stored-cert-hash expected-cert-hash))
                   {:stored stored-cert-hash :recomputed expected-cert-hash})
        (add-check :certification/required-fields (empty? missing-required-fields)
                   {:missing (vec missing-required-fields)})
        (add-check :scenario-count (and (= (:scenario-count certification) (count results))
                                        (= (:scenario-count certification) (:total metrics)))
                   {:stored (:scenario-count certification)
                    :derived (count results)
                    :metrics-total (:total metrics)})
        (add-check :invariant-summary (= (:invariant-summary certification) (:summary inv))
                   {:stored (:invariant-summary certification)
                    :derived (:summary inv)})
        (add-check :all-invariants-pass (= (:all-invariants-pass certification) (:all-pass? inv))
                   {:stored (:all-invariants-pass certification)
                    :derived (:all-pass? inv)})
        (add-check :claim-results (and (= derived-covered? true)
                                       (= derived-covered? (:required-claims-covered? certification))
                                       (= derived-claim-set-root (:claim-set/root certification))
                                       (= derived-claim-outcome-root (:claim-outcome/root certification)))
                   {:derived-covered? derived-covered?
                    :certification-covered? (:required-claims-covered? certification)
                    :derived-set-root derived-claim-set-root
                    :certification-set-root (:claim-set/root certification)
                    :derived-outcome-root derived-claim-outcome-root
                    :certification-outcome-root (:claim-outcome/root certification)
                    :uncovered (vec uncovered-claims)
                    :extra (vec extra-claims)})
        (add-check :claim-closure (and (= derived-covered? true)
                                       (empty? uncovered-claims)
                                       (empty? rejected-claims))
                   {:uncovered (vec uncovered-claims)
                    :rejected (vec rejected-claims)
                    :acceptance acceptance}))))

;; ── Public verification entry points ─────────────────────────────────────────

(defn verify-benchmark-certification
  "Independently verify a certification against the evidence bundle it is claimed
   to certify, under a committed verification profile.

   Any arity: when given only evidence, uses the certification stored in the
   bundle and the default verification profile. When given a separate
   certification, verifies THAT object against the bundle (bundle-binding closes
   if it is not the committed certification).

   Returns a benchmark-certification-verification.v1 map; never mutates the
   certification."
  ([evidence]
   (verify-benchmark-certification evidence default-verification-profile))
  ([evidence verification-profile]
   (verify-benchmark-certification evidence (:benchmark-certification evidence) verification-profile))
  ([evidence certification verification-profile]
   (let [{:keys [checks failures]} (verification-checks evidence certification verification-profile)]
     {:certification-verification/schema verification-schema
      :evidence/root (:evidence/hash evidence)
      :certification/root (:certification-hash certification)
      :verification-profile/root (profile-root verification-profile)
      :checks checks
      :failures (vec failures)
      :verified? (boolean (and (seq checks)
                               (every? #(= :pass %) (vals checks))))
      :verification/creation {:provenance :out-of-band
                              :producer :benchmark-certification-verifier}})))

(defn verify-benchmark-certification!
  "Fail-closed gate: assert that the certification verifies under the given
   profile, returning the verification result when :verified? is true and throwing
   otherwise. The certification is never modified."
  ([evidence]
   (verify-benchmark-certification! evidence default-verification-profile))
  ([evidence verification-profile]
   (verify-benchmark-certification! evidence (:benchmark-certification evidence) verification-profile))
  ([evidence certification verification-profile]
   (let [result (verify-benchmark-certification evidence certification verification-profile)]
     (when-not (:verified? result)
       (throw (ex-info "Benchmark certification verification failed"
                       {:verification/failure :not-verified
                        :verification/failures (:failures result)})))
     result)))