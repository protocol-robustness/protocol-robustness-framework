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
     not-applicable-members   — explicitly marked as :not-applicable"
  (:require [malli.core :as m]))

(def absent-statuses
  "Status values that indicate non-assessment.
   Members with these statuses are excluded from majority computation."
  #{:not-reviewed :insufficient-information :not-applicable})

(def position-group-schema
  "Malli schema for a position-group classification."
  [:map {:closed true}
   [:supporting-members [:vector keyword?]]
   [:qualifying-members [:vector keyword?]]
   [:dissenting-members [:vector keyword?]]
   [:absent-members [:vector keyword?]]
   [:not-reviewed-members [:vector keyword?]]
   [:insufficient-information-members [:vector keyword?]]
   [:not-applicable-members [:vector keyword?]]])

(defn position-group
  "Construct a position-group map. All groups default to empty vector."
  [& {:keys [supporting qualifying dissenting absent not-reviewed insufficient-information not-applicable]}]
  {:supporting-members (vec (or supporting []))
   :qualifying-members (vec (or qualifying []))
   :dissenting-members (vec (or dissenting []))
   :absent-members (vec (or absent []))
   :not-reviewed-members (vec (or not-reviewed []))
   :insufficient-information-members (vec (or insufficient-information []))
   :not-applicable-members (vec (or not-applicable []))})

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
