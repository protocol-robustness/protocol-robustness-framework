(ns resolver-sim.conformance.reconciliation
  "Planned-versus-observed reconciliation (execution fidelity).

   The plan is deterministic and the claim binds its fingerprint; this
   namespace proves the EXECUTED RECEIPTS correspond exactly to the plan.

   Receipts are keyed by subject ID (a stable identifier shared across
   implementations — e.g. a manifest entry id), not by an implementation-
   specific content root, so receipts from different schemes can be reconciled.
   Each receipt still binds its own :subject/root as evidence.

   A receipt is ADMISSIBLE for (step, subject) when:
     - :status is :pass;
     - :subject/id is in the required subject set;
     - :subject-set/root matches the declared subject set.

   Enforcement:
     - every required planned step produced exactly one admissible receipt per
       required subject (missing / duplicate);
     - no receipt satisfies a step for a different subject (wrong-subject);
     - no unplanned receipt can influence the verdict (unexpected);
     - skipped steps are permitted only when the plan explicitly models the
       skip (:skippable? true);
     - a failed or missing prerequisite makes downstream successful receipts
       inadmissible (dependency-mismatch);
     - the claim binds :reconciliation/root, not merely the plan fingerprint."
  (:require [resolver-sim.hash.canonical :as hc]))

(defn admissible-receipt?
  "True when a receipt is admissible for a required subject set and root.
   required-set is a set of subject ids."
  [receipt required-set subject-set-root]
  (and (= :pass (:status receipt))
       (contains? required-set (:subject/id receipt))
       (= subject-set-root (:subject-set/root receipt))))

(defn- plan-step-ids [plan]
  (mapv :step/id (:steps plan)))

(defn- skippable-step-ids [plan]
  (set (map :step/id (filter :skippable? (:steps plan)))))

(declare reconciliation-root)

(defn reconcile
  "Reconcile observed receipts against a plan and required subject set.

   plan        {:plan/root ... :steps [{:step/id :replay :requires [...] :produces [...] :skippable? bool}]}
   observed    [receipts] each {:step/id ... :subject/id ... :subject/root ...
                                :subject-set/root ... :status :pass|:fail}
   subject-set {:subject-set/root ... :subjects [<id>...]}

   Returns {:reconciliation/status :pass|:fail
            :plan/root ...
            :planned-step-ids [...] :observed-step-ids [...]
            :missing-steps [] :unexpected-steps [] :duplicate-steps []
            :subject-mismatches [] :dependency-mismatches []
            :terminal-receipts [...] :reconciliation/root ...}."
  [plan observed subject-set]
  (let [required (set (:subjects subject-set))
        subject-set-root (:subject-set/root subject-set)
        plan-ids (plan-step-ids plan)
        skippable (skippable-step-ids plan)
        by-step (group-by :step/id observed)
        admissible (fn [r] (admissible-receipt? r required subject-set-root))
        admissible-for (fn [step-id]
                         (filterv admissible (get by-step step-id [])))
        ;; missing: non-skippable step with no admissible receipt for a subject
        missing-steps
        (vec (for [step (:steps plan)
                   :when (not (:skippable? step))
                   :let [have (set (map :subject/id (admissible-for (:step/id step))))]
                   s (sort (remove have required))]
               {:step/id (:step/id step) :subject/id s}))
        ;; duplicates: >1 admissible receipt per (step, subject)
        duplicate-steps
        (vec (for [step (:steps plan)
                   :let [receipts (admissible-for (:step/id step))
                         dup-subjects (->> receipts
                                           (map :subject/id)
                                           frequencies
                                           (keep (fn [[id n]] (when (> n 1) id))))]
                   s (sort dup-subjects)]
               {:step/id (:step/id step) :subject/id s}))
        ;; unexpected: receipts whose step is not planned (or planned but
        ;; not part of the run) — cannot influence the verdict
        unexpected-steps
        (vec (for [r observed
                   :when (not (some #(= (:step/id r) %) plan-ids))]
               {:step/id (:step/id r) :subject/id (:subject/id r)}))
        ;; wrong-subject: a PASS receipt for a planned step whose subject is
        ;; not in the required set, or whose subject-set root does not match.
        ;; (Failed receipts for required subjects surface as missing-steps, not
        ;; subject-mismatches.)
        subject-mismatches
        (vec (for [r observed
                   :when (and (= :pass (:status r))
                              (some #(= (:step/id r) %) plan-ids)
                              (not (admissible r)))]
               {:step/id (:step/id r) :subject/id (:subject/id r)
                :reason (if (not (contains? required (:subject/id r)))
                          :wrong-subject
                          :subject-set-mismatch)}))
        ;; dependency: every non-skippable prerequisite of a step must have an
        ;; admissible receipt for the SAME subject.  A plan step's :requires are
        ;; RECEIPT ids; map them to the producing step via the plan's :produces.
        producer (into {} (mapcat (fn [s] (map (fn [p] [p (:step/id s)]) (:produces s))))
                        (:steps plan))
        skippable-producers (set (map :step/id (filter :skippable? (:steps plan))))
        dependency-mismatches
        (vec (for [step (:steps plan)
                   :let [needed (remove (fn [rid]
                                          (contains? skippable-producers (get producer rid)))
                                        (:requires step))]
                   :when (seq needed)
                   r (admissible-for (:step/id step))
                   prereq-id (sort needed)
                   :let [prereq-step (get producer prereq-id)]
                   :when (nil? (some #(and (= prereq-step (:step/id %))
                                           (= (:subject/id r) (:subject/id %))
                                           (admissible %))
                                     observed))]
               {:step/id (:step/id step) :subject/id (:subject/id r)
                :prerequisite prereq-id}))
        terminal-step (last plan-ids)
        terminal-receipts (admissible-for terminal-step)
        ok? (and (empty? missing-steps)
                 (empty? duplicate-steps)
                 (empty? unexpected-steps)
                 (empty? subject-mismatches)
                 (empty? dependency-mismatches))
        result {:reconciliation/status (if ok? :pass :fail)
                :plan/root (:plan/root plan)
                :planned-step-ids plan-ids
                :observed-step-ids (vec (map :step/id observed))
                :missing-steps missing-steps
                :unexpected-steps unexpected-steps
                :duplicate-steps duplicate-steps
                :subject-mismatches subject-mismatches
                :dependency-mismatches dependency-mismatches
                :terminal-receipts (vec (mapv #(select-keys % [:step/id :subject/id :subject/root])
                                              terminal-receipts))}]
    (assoc result :reconciliation/root (reconciliation-root result))))

(defn reconciliation-root
  "Content root of a reconciliation result (deterministic, canonical)."
  [reconciliation]
  (hc/domain-hash "conformance.reconciliation.v1"
                  (select-keys reconciliation
                               [:plan/root
                                :planned-step-ids
                                :observed-step-ids
                                :missing-steps
                                :unexpected-steps
                                :duplicate-steps
                                :subject-mismatches
                                :dependency-mismatches
                                :terminal-receipts])))

(defn passed?
  [reconciliation]
  (= :pass (:reconciliation/status reconciliation)))
