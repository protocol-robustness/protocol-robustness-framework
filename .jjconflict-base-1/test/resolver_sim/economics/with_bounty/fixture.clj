(ns resolver-sim.economics.with-bounty.fixture
  "Executable entrypoints for the Stage A with-bounty fixture package.

   Lives under the test boundary per ADR-0006 D7: it is a fixture package, not
   a shipped built-in. Eligibility and amount evaluation only — no Sew, no
   custody mutation, no custody reservation."
  (:require [resolver-sim.economics.calculations :as calc]))

(defn eligible?
  "Eligibility entrypoint. An accepted review is eligible only once it is
   finalised (:review/finalised? in the event context). An ineligible result is
   not an error."
  [input]
  (let [context (:event/context input {})]
    (if (true? (:review/finalised? context))
      {:result/classification :eligible
       :result/value true
       :result/domain-evidence {:criterion :accepted-review
                                :review-root "sha256:review"}}
      {:result/classification :ineligible
       :result/value false
       :result/domain-evidence {:criterion :accepted-review
                                :reason :review-not-finalised}})))

(defn calculate
  "Amount entrypoint. Reads the committed base result's :resolved-amount and
   the declared review-bounty rate (basis points); delegates arithmetic to the
   protocol-independent core."
  [input]
  (let [basis (get-in input [:base/result :resolved-amount])
        rate (get-in input [:param-values :fixture/review-bounty-rate] 0)]
    {:amount (calc/calculate-bps-amount basis rate)
     :calculation {:rate rate :scale 10000 :basis basis}}))
