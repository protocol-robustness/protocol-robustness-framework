(ns resolver-sim.oracle.detection
  "Oracle protocol adapters over stochastic/detection (pure detection helpers).

   Pass :rng (SplittableRandom) in params for reproducible detection rolls."
  (:require [resolver-sim.stochastic.detection :as det]))

(defprotocol Oracle
  "Fraud detection."

  (detect-fraud?
    [this dispute-outcome resolution-params]
    "Detect whether fraud occurred in this dispute.
     Returns: true | false"))

(deftype PhaseIOracle []
  Oracle

  (detect-fraud? [_ _dispute-outcome params]
    (let [fraud-det-prob (:fraud-detection-probability params 0.0)
          oracle-params (det/prepare-oracle-params params)]
      (det/roll-detect? (det/oracle-roll oracle-params :fraud-detection) fraud-det-prob))))

;; Re-export stochastic detection API for sim layers and tests
(def validate-oracle-params! det/validate-oracle-params!)
(def prepare-oracle-params det/prepare-oracle-params)
(def collect-oracle-fixture-warnings det/collect-oracle-fixture-warnings)
(def oracle-roll-event det/oracle-roll-event)
(def normalize-oracle-fixture det/normalize-oracle-fixture)
(def oracle-roll-in-scope? det/oracle-roll-in-scope?)
(def oracle-fixture-roll-kinds det/oracle-fixture-roll-kinds)
(def roll-detect? det/roll-detect?)
(def appeal-reversal-outcome det/appeal-reversal-outcome)
(def reversal-slashed-live? det/reversal-slashed-live?)
(def reversal-pending-live? det/reversal-pending-live?)
(def l2-slashed? det/l2-slashed?)
(def normalize-detection-probabilities det/normalize-detection-probabilities)
(def detect-probabilistic-violations det/detect-probabilistic-violations)
(def select-slash-reason det/select-slash-reason)
(def slash-amount-for-reason det/slash-amount-for-reason)
(def detect-fraud det/detect-fraud-rolls)
(def apply-slashing det/apply-slashing)
(def apply-freeze det/apply-freeze)
(def calculate-escape-window det/calculate-escape-window)
