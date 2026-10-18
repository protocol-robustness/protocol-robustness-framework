(ns resolver-sim.protocols.sew.dispute-context.disagree
  "Small fail-closed checks for model-only dispute-context/disagree fixtures."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def fixture-schema :prf/dispute-context-disagree-fixtures.v1)

(defn- case-errors [case]
  (let [{:keys [posted posting-fee custodied] :as bond} (:bond case)
        appeal (:appeal case)
        errors (cond-> []
                 (not (and (:case/id case) (:workflow/id case)
                           (:round/id case) (:basis/root case)))
                 (conj :missing-case-binding)
                 (not (and (= (:workflow/id case) (:workflow/id bond))
                           (= (:round/id case) (:round/id bond))
                           (= (:basis/root case) (:basis/root bond))))
                 (conj :bond-case-binding-mismatch)
                 (not (and (every? #(and (integer? %) (<= 0 %))
                                   [posted posting-fee custodied])
                           (= posted (+ posting-fee custodied))))
                 (conj :bond-posting-not-conserved)
                 (and (= :accepted (:status appeal))
                      (not (contains? #{:upheld-on-merits :overturned-on-merits}
                                      (:decision appeal))))
                 (conj :accepted-appeal-missing-merits-decision)
                 (and (= :upheld-on-merits (:decision appeal))
                      (not= :prior-resolver-per-policy (:disposition (:bond case))))
                 (conj :upheld-appeal-bond-policy-mismatch)
                 (and (= :overturned-on-merits (:decision appeal))
                      (not= :appellant-refund-per-policy (:disposition (:bond case))))
                 (conj :overturned-appeal-bond-policy-mismatch)
                 (and (= :procedurally-rejected (:status appeal))
                      (or (nil? (:reason appeal)) (:decision appeal)))
                 (conj :procedural-rejection-confused-with-merits)
                 (and (= :dissent (:position (:research case)))
                      (not= (get-in case [:settlement :before-root])
                            (get-in case [:settlement :after-root])))
                 (conj :researcher-dissent-changed-settlement)
                 (and (= :dissent (:position (:research case)))
                      (not= (:basis/root case) (get-in case [:research :target/root])))
                 (conj :dissent-target-mismatch)
                 (and (= :dissent (:position (:research case)))
                      (not (contains? #{:verified :unverified}
                                      (get-in case [:research :signature/status]))))
                 (conj :invalid-dissent-signature-status))]
    errors))

(defn validate-fixtures
  "Validate fixture structure and case-local invariants; this does not claim
   signature verification, live-chain provenance, or economic execution."
  [fixture]
  (let [cases (:cases fixture)
        ids (map :case/id cases)
        errors (vec
                (concat
                 (when-not (= fixture-schema (:fixture/schema fixture))
                   [:invalid-schema])
                 (when-not (= :model-only (:fixture/status fixture))
                   [:fixture-must-be-marked-model-only])
                 (when-not (= (count ids) (count (set ids)))
                   [:duplicate-case-id])
                 (mapcat (fn [case]
                           (map #(vector (:case/id case) %) (case-errors case)))
                         cases)))]
    {:valid? (empty? errors) :errors errors}))

(defn validate-case-binding
  "Reject appeal, dissent, or bond material transplanted onto another case."
  [expected supplied]
  (let [keys [:case/id :workflow/id :round/id :basis/root]
        mismatches (vec (keep (fn [key]
                                (when (not= (get expected key) (get supplied key)) key))
                              keys))]
    {:valid? (empty? mismatches) :mismatches mismatches}))

(defn load-fixtures
  ([] (load-fixtures "data/fixtures/dispute-context/disagree.edn"))
  ([path]
   (with-open [reader (io/reader path)]
     (edn/read (java.io.PushbackReader. reader)))))
