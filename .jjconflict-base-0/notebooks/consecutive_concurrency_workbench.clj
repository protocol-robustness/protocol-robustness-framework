;; # Consecutive Commitments & Concurrency Workbench
;;
;; **Purpose:** experiment with the boundary between a parseable consecutive
;; byte stream and a *bound* protocol commitment, then demonstrate that
;; concurrent admission attempts do not let completion order confer authority.
;;
;; This is an executable Clerk notebook.  It uses the in-memory admission store
;; as a deterministic reference model; PostgreSQL's multi-process equivalent is
;; covered by `postgres_admission_concurrency_test.clj`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.consecutive-concurrency-workbench
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.evidence.confidence :as confidence]
            [resolver-sim.hash.sequence :as sequence]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as admission-store]
            [resolver-sim.resubmission.admission-workflow :as workflow])
  (:import [java.util Arrays]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ## 1. Consecutive bytes are parseable, but do not state their meaning
;;
;; The bare stream below has the same bytes regardless of whether a caller calls
;; it an evidence chain, a confidence composition, or a command argument list.
;; That ambiguity is semantic, not a parser bug.  `bound-sequence` fixes it by
;; committing the purpose, count, and component structure in one canonical value.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def components
  [{:subject-hash "sha256:subject-a" :role :required
    :level :high :status :final :scope :unbounded}
   {:subject-hash "sha256:subject-b" :role :required
    :level :medium :status :provisional :scope :bounded}])

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def bare-consecutive-bytes
  (sequence/encode-sequence components))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def confidence-bound
  (sequence/bound-sequence {:purpose :confidence-composition
                           :expected-component-count 2}
                          components))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def evidence-bound
  (sequence/bound-sequence {:purpose :evidence-chain
                           :expected-component-count 2}
                          components))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Experiment" "Result" "Interpretation"]
  :rows [["Bare bytes can be reused under two labels"
          (str (Arrays/equals bare-consecutive-bytes
                              (sequence/encode-sequence components)))
          "true: labels are not encoded in bare concatenation"]
         ["Bound confidence value equals bound evidence value"
          (str (= confidence-bound evidence-bound))
          "false: :purpose is committed"]
         ["Bound confidence hash equals evidence hash"
          (str (= (sequence/sequence-hash {:purpose :confidence-composition} components)
                  (sequence/sequence-hash {:purpose :evidence-chain} components)))
          "false: domain-separated commitment preserves meaning"]]})

;; ## 2. Tricky framing edge cases fail closed
;;
;; A string, map, and set might each be tempting to coerce into a sequence.
;; `bound-sequence` rejects them at the API boundary.  In particular, silently
;; sorting a set would make its commitment indistinguishable from a caller that
;; deliberately supplied the corresponding vector.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(defn rejection-reason
  [value]
  (try
    (sequence/bound-sequence {:purpose :workbench-edge-case} value)
    :accepted-unexpectedly
    (catch clojure.lang.ExceptionInfo e
      (or (:required (ex-data e)) :rejected))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Input" "Bound-sequence result" "Why"]
  :rows [["nil" (name (rejection-reason nil)) "not a component sequence"]
         ["string" (name (rejection-reason "abc")) "a scalar, not chars to concatenate"]
         ["map" (name (rejection-reason {:a 1})) "map entries are not an implicit sequence"]
         ["set component" (name (rejection-reason [#{:a :b}]))
          "set-to-vector projection must be explicit"]]})

;; ## 3. Confidence can be conferred only by a declared composition policy
;;
;; The aggregate does not inherit an arbitrary label from a byte stream.  The
;; policy chooses which components constrain it.  Here two required components
;; use `:all-required`, so the weaker/provisional/bounded component constrains
;; the result; a supporting component cannot silently lower it.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def confidence-components
  (conj components
        {:subject-hash "sha256:diagnostic-only" :role :supporting
         :level :low :status :provisional :scope :trace-bounded}))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def confidence-composition
  (confidence/compose-confidence confidence-components
                                 :prf.confidence/all-required-v1))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def confidence-commitment
  (confidence/concatenate-bound confidence-components :by-subject))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Committed property" "Observed value"]
  :rows [["composition policy" (pr-str (:confidence/composition-policy confidence-composition))]
         ["bound sequence purpose" ":confidence-composition (fixed by concatenate-bound)"]
         ["component count" (str (count confidence-components))]
         ["sequence commitment" confidence-commitment]
         ["aggregate confidence" (pr-str (:confidence/aggregate confidence-composition))]
         ["supporting component lowers aggregate?"
          (str (= :low (get-in confidence-composition [:confidence/aggregate :level])))] ]})

;; ## 4. Fully parallel workflow contention
;;
;; Every worker starts from the same pre-reservation snapshot, supplies complete
;; snapshot-bound validation, and begins at the same barrier.  The first worker
;; obtaining the reservation is the sole signer; while it pauses, every other
;; worker reaches the same store and is rejected with operational contention.
;; Signing completion cannot select the winner.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn sha-ref [char]
  (str "sha256:" (apply str (repeat 64 char))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn validation-for
  [snapshot candidate-root]
  {:profile-id :consecutive-concurrency-workbench
   :profile-version "1"
   :checks (mapv (fn [check-id]
                   {:check/id check-id
                    :valid? true
                    :validated-against/root (:concurrency/snapshot-root snapshot)
                    :validated-against/version (:concurrency/expected-state-version snapshot)
                    :validated-against/candidate-root candidate-root})
                 admission/required-check-order)})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn parallel-workflow-contention!
  "Run `workers` fully concurrent admission workflows and return only stable
   observations. The latch is a start barrier, not an authority mechanism; the
   store's reservation transition remains the sole arbitration point."
  [workers]
  (let [store (admission-store/in-memory-store)
        family-id "sha256:CONSECUTIVE-CONCURRENCY-WORKBENCH"
        snapshot (admission-store/snapshot! store family-id)
        start (CountDownLatch. 1)
        signer-entered (CountDownLatch. 1)
        signer-count (atom 0)
        run-worker
        (fn [n]
          (let [candidate-root (sha-ref (char (+ (int \a) n)))]
            (.await start)
            (workflow/attempt!
             {:admission-store store
              :family-id family-id
              :snapshot snapshot
              :candidate-root candidate-root
              :idempotency-key (str "workbench-idempotency-" n)
              :proposed-ordering-root (sha-ref \o)
              :validation (validation-for snapshot candidate-root)
              :sign! (fn [payload]
                       (swap! signer-count inc)
                       (.countDown signer-entered)
                       ;; Keep the reservation live while other futures contend.
                       (Thread/sleep 150)
                       {:receipt/root (sha-ref \r)
                        :signing/payload-root (:signing/payload-root payload)})
              :verify-signature! (fn [payload signed]
                                   (= (:signing/payload-root payload)
                                      (:signing/payload-root signed)))})))
        futures (mapv #(future (run-worker %)) (range workers))]
    (.countDown start)
    ;; Confirms that one contender reached the signer before result collection.
    (.await signer-entered 5 TimeUnit/SECONDS)
    (let [outcomes (mapv deref futures)
          final-state (admission-store/snapshot! store family-id)
          counts (frequencies (map :concurrency/outcome outcomes))]
      {:workers workers
       :outcome-counts counts
       :signer-invocations @signer-count
       :family-version (:family/version final-state)
       :authoritative-head (:family/head final-state)
       :all-contention-results
       (every? #{:finalized :contention} (map :concurrency/outcome outcomes))
       :invariant-holds?
       (and (= 1 (get counts :finalized 0))
            (= (dec workers) (get counts :contention 0))
            (= 1 @signer-count)
            (= 1 (:family/version final-state)))})))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def parallel-contention-result
  (parallel-workflow-contention! 12))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Invariant" "Observed"]
  :rows [["workers" (str (:workers parallel-contention-result))]
         ["outcomes" (pr-str (:outcome-counts parallel-contention-result))]
         ["signer invocations" (str (:signer-invocations parallel-contention-result))]
         ["durable family version" (str (:family-version parallel-contention-result))]
         ["only finalized or contention outcomes?" (str (:all-contention-results parallel-contention-result))]
         ["one winner; signer cannot confer authority?" (str (:invariant-holds? parallel-contention-result))]]})

;; ## Conclusion
;;
;; - Consecutive canonical values need an explicit purpose and structure binding;
;;   parseability alone does not confer protocol meaning.
;; - Confidence is conferred only through a declared composition policy and its
;;   committed component sequence.
;; - Under parallel contention, the reservation/fence transition chooses the
;;   one workflow entitled to sign and finalize.  The signer cannot choose a
;;   winner merely by returning first.
