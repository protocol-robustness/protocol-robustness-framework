(ns resolver-sim.pro-rata.finalization
  "Authority-level finalization for a completed pro-rata application closure.

   This namespace deliberately operates after allocation, output, application,
   ordering, and binding artifacts exist. It does not construct them and it does
   not own claimant execution. Its one lane callback is the durable PostgreSQL
   publication transaction."
  (:require [resolver-sim.execution.finalization-lane :as lane]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.postgres-publication-store :as postgres]
            [resolver-sim.pro-rata.publication :as publication]))

(defn finalization-commitments
  "Derive and validate the roots bound to an authority-level finalization lane.

   `:allocation/hash` is the closed result-set root because it commits the
   canonical allocation envelope, including the canonical request, every result
   row, allocation witnesses, rounding and redistribution semantics. The output
   and eligible invocation-publication binding independently bind that exact
   allocation root into the publication closure."
  [publication-store resolved publication-binding]
  (let [allocation-body (:allocation resolved)
        ordering (:publication-ordering resolved)
        allocation-root (:allocation/hash allocation-body)
        basis-root (:pro-rata-invocation-publication-binding/root publication-binding)
        resolved (assoc resolved
                        :publication-ordering ordering
                        :resolve-body (.resolve-durable-artifact publication-store))
        allocation-valid? (try
                            (allocation/allocation-hash-valid? allocation-body)
                            (catch Exception _ false))
        valid-basis? (and allocation-valid?
                          (= allocation-root (get-in resolved [:output :allocation/root]))
                          (= basis-root (binding/binding-root publication-binding))
                          (= (:publication-ordering/root publication-binding)
                             (:transaction-ordering/hash ordering))
                          (binding/binding-eligible? publication-binding resolved))]
    {:closed-result-set-root allocation-root
     :basis-root basis-root
     :valid-basis? valid-basis?}))

(defn- current-fence [publication-store ordering]
  (let [{:publication/keys [head store-version]}
        (or (postgres/current-head-and-version
             publication-store (:transaction/conflict-key ordering))
            {:publication/head (publication/initial-application-head)
             :publication/store-version 0})]
    {:expected-parent-root (:publication/head-root head)
     :expected-store-version store-version}))

(defn reconcile-local-projection!
  "Repair local realization state from an already committed PostgreSQL authority.
   This operation never publishes, opens a lane, or treats local delivery as an
   authority transaction. It is intended for the crash window after durable
   commit and before local projection."
  [{:keys [realization publication-store conflict-key expected-ordering-root
           expected-binding-root]}]
  (let [authority (postgres/resolve-authoritative-application-publication
                   publication-store conflict-key)
        head (:publication/head authority)
        binding (:publication/binding authority)
        ordering (:publication/ordering authority)]
    (when-not authority
      (throw (ex-info "No authoritative publication is available for reconciliation"
                      {:reason :authoritative-publication-unavailable
                       :conflict-key conflict-key})))
    (when-not (and (= expected-ordering-root (:transaction-ordering/hash ordering))
                   (= expected-binding-root
                      (:pro-rata-invocation-publication-binding/root binding))
                   (= (:publication/last-ordering-root head)
                      expected-ordering-root)
                   (= (:publication/application-binding-root head)
                      expected-binding-root))
      (throw (ex-info "Authoritative publication identity does not match reconciliation request"
                      {:reason :authoritative-publication-identity-mismatch
                       :authority authority})))
    (case (:lifecycle @realization)
      :frozen (let [observation (realization/finalize! realization)]
                (realization/emit! realization)
                {:status :reconciled :authority authority :observation observation})
      :finalized (do (realization/emit! realization)
                     {:status :already-projected :authority authority
                      :observation (:observation @realization)})
      (throw (ex-info "Local realization is not recoverable from its current lifecycle"
                      {:reason :local-realization-not-recoverable
                       :lifecycle (:lifecycle @realization)})))))

(defn finalize-application-bound!
  "Elect one finalizer and durably publish a fully constructed application
   closure. The supplied lane must be the lane that tracked this execution; no
   second lane is opened here.

   PostgreSQL independently revalidates the basis/prerequisites and the locked
   parent root/version before it mutates authority. Local realization projection
   and observation delivery occur only after a committed durable result."
  [{:keys [realization lane publication-store resolved publication-binding]}]
  (let [ordering (:publication-ordering resolved)
        commitments (finalization-commitments publication-store resolved publication-binding)
        {:keys [expected-parent-root expected-store-version]} (current-fence publication-store ordering)]
    (when-not (:valid-basis? commitments)
      (throw (ex-info "Invalid authoritative finalization basis"
                      (select-keys commitments [:closed-result-set-root :basis-root :valid-basis?]))))
    (when-not (= expected-parent-root (:lane/expected-parent-root @lane))
      (throw (ex-info "Finalization lane parent does not match authoritative head"
                      {:expected-parent-root expected-parent-root
                       :lane-parent-root (:lane/expected-parent-root @lane)})))
    (realization/complete-after-authority!
     realization
     (assoc commitments
            :lane lane
            :observed-parent-root expected-parent-root
            :commit! (fn [_ticket]
                       (postgres/publish-application-bound!
                        publication-store ordering publication-binding resolved
                        expected-parent-root expected-store-version))))))
