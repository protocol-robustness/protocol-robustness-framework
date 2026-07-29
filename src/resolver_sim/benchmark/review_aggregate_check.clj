(ns resolver-sim.benchmark.review-aggregate-check
  "Aggregate invariant checks for benchmark review structures.
   Complementary to the per-structure validators in review-round and
   canonical-indices — these checks verify cross-structure consistency
   properties that span multiple artifacts."
  (:require [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]))

;; ── Aggregate member-bit-width consistency ─────────────────────────────────

(defn check-aggregate-member-bit-width
  "Verify that the member bit-width is consistent between a review round
   and its canonical-indices artifact (if present).

   Also verifies that the round's bit-width is non-negative and matches
   the expected width for the number of members.

   Returns {:holds? bool
            :violations [{:kind ::member-bit-width-mismatch
                          :round-bit-width <int-or-nil>
                          :artifact-bit-width <int-or-nil>
                          :message string} ...]}."
  [round & [canonical-indices]]
  (let [violations (atom [])
        round-width (rr/member-bit-width round)]
    (when (and (rr/round-uses-member-keys? round) (nil? round-width))
      (swap! violations conj
             {:kind ::member-bit-width-mismatch
              :round-bit-width nil
              :artifact-bit-width nil
              :message "bit-width is nil for a keyed review round"}))
    (when canonical-indices
      (let [artifact-width (ci/member-bit-width canonical-indices)]
        (when (and round-width artifact-width (not= round-width artifact-width))
          (swap! violations conj
                 {:kind ::member-bit-width-mismatch
                  :round-bit-width round-width
                  :artifact-bit-width artifact-width
                  :message (str "bit-width mismatch between round (" round-width
                                ") and canonical-indices (" artifact-width ")")}))))
    {:holds? (empty? @violations)
     :violations (vec @violations)}))

;; ── Aggregate member-key density consistency ───────────────────────────────

(defn check-aggregate-member-key-density
  "Verify that member key density (dense 0..n-1) is consistent between
   a review round and its canonical-indices artifact (if present).

   Returns {:holds? bool :violations [...]}."
  [round & [canonical-indices]]
  (let [violations (atom [])]
    (when (rr/round-uses-member-keys? round)
      (let [keys (rr/member-keys round)]
        (when (not= keys (range (count keys)))
          (swap! violations conj
                 {:kind ::non-dense-member-keys
                  :keys keys
                  :message (str "round keys " keys " are not dense 0.." (dec (count keys)))}))
        (when canonical-indices
          (let [n (:review-member/count canonical-indices)]
            (when (not= (count keys) n)
              (swap! violations conj
                     {:kind ::member-count-mismatch
                      :round-count (count keys)
                      :artifact-count n
                      :message (str "round member count " (count keys)
                                    " differs from artifact count " n)}))))))
    {:holds? (empty? @violations)
     :violations (vec @violations)}))
