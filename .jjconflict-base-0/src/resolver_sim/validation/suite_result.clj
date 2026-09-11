(ns resolver-sim.validation.suite-result
  "Suite-local validation result construction.
   Each suite reduces its individual checks into a normalized result map.
   The shape is designed to be consumed by `validation.root/merge-validation-result`
   so that multiple suites can be accumulated into a single validation root.
   Reducer:
     (reduce check->suite-acc empty-suite-acc checks)
       → suite result map
   Status derivation:
     derive-suite-status checks
       → :passed | :warning | :failed")

(def empty-suite-acc
  {:suite/id     nil
   :suite/type   nil
   :status       :unknown
   :status-keys  #{}
   :warning-keys #{}
   :error-keys   #{}
   :checks       []
   :errors       []
   :warnings     []
   :evidence     []
   :metrics      {:checks 0 :passed 0 :failed 0 :warnings 0}})

(defn check->suite-acc
  "Reduce one check map into a suite-result accumulator.

   Check shape:
     {:check/id      keyword       ;; unique check identifier
      :status        :passed|:failed|:warning
      :severity      :critical|:warning|:info  (default :warning)
      :status-key    keyword|nil   ;; added to status-keys when non-nil
      :error-key     keyword|nil   ;; added to error-keys for :failed checks
      :warning-key   keyword|nil   ;; added to warning-keys for :warning checks
      :suite/id      keyword|nil   ;; scoped suite id (defaults to suite)
      :scenario-id   string|nil    ;; originating scenario
      :expected      any           ;; expected value
      :actual        any           ;; actual value
      :message       string        ;; human explanation
      :evidence-ref  map|nil       ;; {:artifact-id kw :path [...]}
      :check-group   keyword|nil}  ;; optional grouping

   Returns updated accumulator."
  [acc check]
  (let [status   (:status check :passed)
        severity (:severity check :warning)
        s-key    (:status-key check)
        e-key    (:error-key check)
        w-key    (:warning-key check)
        error    (when (= :failed status)
                   {:key         (or e-key :unclassified-error)
                    :severity    severity
                    :suite/id    (:suite/id check (:suite/id acc))
                    :scenario-id (:scenario-id check)
                    :message     (:message check)
                    :evidence-ref (:evidence-ref check)})
        warning  (when (= :warning status)
                   {:key         (or w-key :unclassified-warning)
                    :severity    severity
                    :suite/id    (:suite/id check (:suite/id acc))
                    :scenario-id (:scenario-id check)
                    :message     (:message check)
                    :evidence-ref (:evidence-ref check)})]
    (cond-> acc
      s-key                     (update :status-keys conj s-key)
      (and e-key (= :failed status)) (update :error-keys conj e-key)
      (and w-key (= :warning status)) (update :warning-keys conj w-key)
      :always                   (update :checks conj check)
      error                     (update :errors conj error)
      warning                   (update :warnings conj warning)
      (= :passed status)        (update-in [:metrics :passed] inc)
      (= :failed status)        (update-in [:metrics :failed] inc)
      (= :warning status)       (update-in [:metrics :warnings] inc)
      :always                   (update-in [:metrics :checks] inc))))

(defn derive-suite-status
  "Derive suite-level status from a collection of checks.
    - :failed if any check has :failed status
    - :warning if any check has :warning status and no failures
    - :passed otherwise"
  [checks]
  (cond
    (some #(= :failed (:status %)) checks)   :failed
    (some #(= :warning (:status %)) checks)  :warning
    :else                                     :passed))

(defn suite-result
  "Build a complete suite result map from suite metadata and checks.

   Args:
     suite-id    — keyword, e.g. :yield
     suite-type  — keyword, e.g. :protocol
     checks      — vector of check maps
     extra       — optional map merged into the result (for :evidence etc.)

   Returns a suite-result map suitable for validation.root/merge-validation-result."
  ([suite-id suite-type checks]
   (suite-result suite-id suite-type checks {}))
  ([suite-id suite-type checks extra]
   (let [base (reduce check->suite-acc
                      (assoc empty-suite-acc
                             :suite/id suite-id
                             :suite/type suite-type)
                      checks)]
     (-> base
         (assoc :status (derive-suite-status checks))
         (merge extra)))))

(defn checks-passed?
  "True when every check in the collection has :passed status."
  [checks]
  (every? #(= :passed (:status %)) checks))

(defn checks-failed?
  "True when at least one check has :failed status."
  [checks]
  (boolean (some #(= :failed (:status %)) checks)))

(defn checks-by-severity
  "Return only checks at or above the given severity threshold.
   Severity order: :critical > :warning > :info."
  [checks min-severity]
  (let [order {:critical 3 :warning 2 :info 1}]
    (filter #(>= (order (:severity % :info) 0) (order min-severity :info))
            checks)))
