(ns resolver-sim.configuration-head
  "Authoritative, fenced installation of chain-configuration heads.

  This is the application-side complement to `chain-configuration-transition.v1`.
  It introduces no governance authority: authorization remains the existing
  control-plane decision that produced the transition root.  This component only
  atomically applies that already-authorized transition exactly once against the
  currently installed head.

  A self-hash alone would not establish a history.  The `TransactionStore` CAS
  is the authoritative mutation boundary: a committed activation has observed
  and replaced one exact head state in the same atomic operation."
  (:require [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.transaction.protocol :as protocol]
            [resolver-sim.transaction.ordering :as ordering]))

(def ^:const state-schema "configuration-head-state.v1")
(def ^:const activation-schema "configuration-head-activation.v1")

(defn head-state-root [head]
  (ref/sha256-ref
   (hc/domain-hash :configuration-head-state-v1
                   (dissoc head :configuration-head-state/root))))

(defn valid-head-state? [head]
  (and (= state-schema (:schema-version head))
       (ref/valid-sha256-ref? (:configuration/head-root head))
       (genesis/valid-epoch? (:configuration/epoch head))
       (or (nil? (:configuration/predecessor-head-root head))
           (ref/valid-sha256-ref? (:configuration/predecessor-head-root head)))
       (or (nil? (:configuration/activation-transition-root head))
           (ref/valid-sha256-ref? (:configuration/activation-transition-root head)))
       (integer? (:configuration/sequence head))
       (not (neg? (:configuration/sequence head)))
       (= (:configuration-head-state/root head) (head-state-root head))))

(defn activation-root [activation]
  (ref/sha256-ref
   (hc/domain-hash :configuration-head-activation-v1
                   (dissoc activation :configuration-head-activation/root))))

(defn initial-head
  "Build the immutable initial head.  Initial configuration is supplied by the
  already sealed chain-instance genesis, not newly authorized here."
  [configuration-root epoch]
  (let [head {:schema-version state-schema
              :configuration/head-root configuration-root
              :configuration/epoch epoch
              :configuration/predecessor-head-root nil
              :configuration/activation-transition-root nil
              :configuration/sequence 0}]
    (assoc head :configuration-head-state/root (head-state-root head))))

(defn- state-root [state]
  (ref/sha256-ref
   (hc/domain-hash :configuration-head-state-v1
                   (select-keys state [:configuration/current-head
                                       :configuration/commit-index]))))

(defn- effects-root [effects]
  (ref/sha256-ref (hc/domain-hash :configuration-head-activation-v1 effects)))

(defn derive-successor-head
  "Pure canonical configuration-head transition. Returns either `{:status
   :committed :configuration/head ... :configuration/activation ...}` or an
   existing stable rejection reason. It has no authorization or store/CAS input."
  [head transition parent-configuration new-configuration]
  (let [transition-root (try (genesis/chain-configuration-transition-root transition)
                             (catch Exception _ nil))
        parent-root (try (genesis/chain-configuration-root parent-configuration)
                         (catch Exception _ nil))
        new-root (try (genesis/chain-configuration-root new-configuration)
                      (catch Exception _ nil))]
    (cond
      (not (valid-head-state? head)) {:status :rejected :reason :configuration-head-invalid}
      (nil? transition-root) {:status :rejected :reason :configuration-transition-invalid}
      (not= (:configuration/head-root head) (:configuration/parent-root transition)) {:status :rejected :reason :transition-parent-not-current-head}
      (not= parent-root (:configuration/parent-root transition)) {:status :rejected :reason :transition-parent-configuration-mismatch}
      (not= new-root (:configuration/new-root transition)) {:status :rejected :reason :transition-new-configuration-mismatch}
      (not= (inc (:configuration/epoch head)) (:epoch transition)) {:status :rejected :reason :configuration-epoch-not-successor}
      :else
      (let [next-head-base {:schema-version state-schema
                            :configuration/head-root new-root
                            :configuration/epoch (:epoch transition)
                            :configuration/predecessor-head-root (:configuration-head-state/root head)
                            :configuration/activation-transition-root transition-root
                            :configuration/sequence (inc (:configuration/sequence head))}
            next-head (assoc next-head-base :configuration-head-state/root (head-state-root next-head-base))
            activation-base {:schema-version activation-schema
                             :configuration/previous-head-root (:configuration-head-state/root head)
                             :configuration/new-head-root (:configuration-head-state/root next-head)
                             :configuration/root new-root
                             :configuration/epoch (:epoch transition)
                             :configuration/activation-transition-root transition-root}
            activation (assoc activation-base :configuration-head-activation/root (activation-root activation-base))
            effects [{:effect/type :configuration-head-activated
                      :configuration/previous-head-root (:configuration-head-state/root head)
                      :configuration/new-head-root (:configuration-head-state/root next-head)
                      :configuration/activation-root (:configuration-head-activation/root activation)}]]
        {:status :committed
         :configuration/head next-head
         :configuration/activation activation
         :effects effects
         :transition-root transition-root}))))

(defn- apply-activation [state {:keys [transition parent-configuration new-configuration
                                       expected-head-root authorized-transition-root]}]
  (let [head (:configuration/current-head state)
        transition-root (try (genesis/chain-configuration-transition-root transition)
                             (catch Exception _ nil))
        derived (derive-successor-head head transition parent-configuration new-configuration)]
    (cond
      (not= (:configuration-head-state/root head) expected-head-root)
      {:status :rejected :reason :configuration-head-mismatch}
      (not= transition-root authorized-transition-root)
      {:status :rejected :reason :transition-not-authorized}
      (not= :committed (:status derived)) derived
      :else
      (let [next-state {:configuration/current-head (:configuration/head derived)
                        :configuration/commit-index (inc (:configuration/commit-index state))}]
        {:status :committed :state next-state
         :public-result {:configuration/head (:configuration/head derived)
                         :configuration/activation (:configuration/activation derived)}
         :effects (:effects derived)
         :ordering-input {:transaction/action :prf.configuration/activate-head
                          :transaction/scope :chain-configuration
                          :transaction/conflict-key [:chain-configuration-head]
                          :transaction-ordering/schema ordering/ordering-v2-schema
                          :transaction/input-root transition-root
                          :transaction/expected {:configuration/head-root expected-head-root}
                          :transaction/observed {:configuration/head-root (:configuration-head-state/root head)}}}))))

(deftype ConfigurationHeadStore [state-atom]
  protocol/TransactionStore
  (transact! [_ _ expected-version transition-fn]
    (loop []
      (let [current @state-atom
            version (:configuration/store-version current)]
        (if (and (some? expected-version) (not= expected-version version))
          {:status :contention :reason :version-mismatch :expected-version expected-version :observed-version version}
          (let [result (transition-fn current)]
            (if-not (= :committed (:status result))
              result
              (let [before (state-root current)
                    after (state-root (:state result))
                    tx (ordering/transaction-ordering
                        (merge (:ordering-input result)
                               {:transaction/commit-index (:configuration/commit-index (:state result))
                                :transaction/previous-transaction-hash (:configuration/last-transaction-root current)
                                :transaction/state-before-root before
                                :transaction/state-after-root after
                                :transaction/effects-root (effects-root (:effects result))}))
                    final-state (assoc (:state result)
                                       :configuration/store-version (inc version)
                                       :configuration/last-transaction-root (:transaction-ordering/hash tx))]
                (if (compare-and-set! state-atom current final-state)
                  (assoc result :transaction-ordering tx)
                  (recur))))))))))

(defn new-store [initial-configuration-root initial-epoch]
  (ConfigurationHeadStore.
   (atom {:configuration/current-head (initial-head initial-configuration-root initial-epoch)
          :configuration/commit-index 0
          :configuration/store-version 0
          :configuration/last-transaction-root nil})))

(defn current-head [store] (:configuration/current-head @(.state-atom store)))

(defn activate!
  "Atomically install a successor configuration.  `authorized-transition-root`
  must be the root authorized by the pre-existing governance/control-plane; the
  command is fenced by the exact current head root and store version."
  [store command]
  (protocol/transact! store [:chain-configuration-head]
                      (:expected-store-version command)
                      #(apply-activation % command)))
