(ns resolver-sim.benchmark.review.position-group
  "Position-group schema for consensus classification.

   A position-group classifies researchers into groups based on their
   assessment status for a given dimension, theorem, or conclusion.
   This prevents absent statuses from being incorrectly folded into
   majority disagreement.

   Groups:
     supporting-members       — assessed and in agreement with majority
     qualifying-members       — assessed with qualifications/caveats
     dissenting-members       — assessed but in disagreement
     absent-members           — did not participate in this dimension
     not-reviewed-members     — explicitly marked as :not-reviewed
     insufficient-information-members — explicitly marked as :insufficient-information
     not-applicable-members   — explicitly marked as :not-applicable
     not-evaluable-members    — explicitly marked as :not-evaluable (target could
                                not meaningfully be evaluated)

   Non-assessment is evidence about evaluability, not a vote on the target:
   `:insufficient-information` (“cannot assess — evidence insufficient”) and
   `:not-evaluable` (“this target cannot meaningfully be evaluated”) are kept as
   distinct groups even though both are excluded from majority computation, so
   the underlying classifications never become indistinguishable in a
   certificate."
  (:require [malli.core :as m]))

(def absent-statuses
  "Status values that indicate non-assessment.
   Members with these statuses are excluded from majority computation.
   :not-evaluable is a theorem/conclusion target status meaning the researcher
   could not form an assessment; it is a non-assessment, grouped separately
   from :insufficient-information so the two classifications stay distinct."
  #{:not-reviewed :insufficient-information :not-applicable :not-evaluable})

(def position-group-schema
  "Malli schema for a position-group classification."
  [:map {:closed true}
   [:supporting-members [:vector keyword?]]
   [:qualifying-members [:vector keyword?]]
   [:dissenting-members [:vector keyword?]]
   [:absent-members [:vector keyword?]]
   [:not-reviewed-members [:vector keyword?]]
   [:insufficient-information-members [:vector keyword?]]
   [:not-applicable-members [:vector keyword?]]
   [:not-evaluable-members [:vector keyword?]]])

(defn position-group
  "Construct a position-group map. All groups default to empty vector."
  [& {:keys [supporting qualifying dissenting absent not-reviewed
             insufficient-information not-applicable not-evaluable]}]
  {:supporting-members (vec (or supporting []))
   :qualifying-members (vec (or qualifying []))
   :dissenting-members (vec (or dissenting []))
   :absent-members (vec (or absent []))
   :not-reviewed-members (vec (or not-reviewed []))
   :insufficient-information-members (vec (or insufficient-information []))
   :not-applicable-members (vec (or not-applicable []))
   :not-evaluable-members (vec (or not-evaluable []))})

(defn empty-group
  "Position-group with all groups empty."
  []
  (position-group))

(defn valid?
  "True when the map conforms to position-group-schema."
  [pg]
  (m/validate position-group-schema pg))

(defn explain
  "Return Malli validation errors for a position-group map."
  [pg]
  (m/explain position-group-schema pg))
