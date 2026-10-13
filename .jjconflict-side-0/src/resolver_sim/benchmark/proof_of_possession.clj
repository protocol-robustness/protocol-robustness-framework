(ns resolver-sim.benchmark.proof-of-possession
  "Independent proof-of-possession requirement and establishment semantics.")

(def requirements #{:unspecified :requested :required})
(def statuses #{:not-established :established :verified})

(defn evaluate
  "Evaluate policy without changing the supplied proof status.

  `:verified` is retained as the existing verifier vocabulary and is treated as
  established. Requested gaps are visible but nonblocking; required gaps are
  explicit unsatisfied requirements."
  [requirement status]
  (when-not (requirements requirement)
    (throw (ex-info "Invalid proof-of-possession requirement"
                    {:proof-of-possession/requirement requirement})))
  (when-not (statuses status)
    (throw (ex-info "Invalid proof-of-possession status"
                    {:proof-of-possession/status status})))
  (let [established? (contains? #{:established :verified} status)
        outcome (cond
                  established? :established
                  (= :required requirement) :required-but-not-established
                  (= :requested requirement) :requested-but-not-established
                  :else :unspecified-and-not-established)]
    {:proof-of-possession/requirement requirement
     :proof-of-possession/status status
     :proof-of-possession/outcome outcome
     :proof-of-possession/required-satisfied? (or (not= :required requirement)
                                                  established?)
     :proof-of-possession/outstanding-request? (and (= :requested requirement)
                                                    (not established?))}))
