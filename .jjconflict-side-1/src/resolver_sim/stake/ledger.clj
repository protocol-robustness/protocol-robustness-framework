(ns resolver-sim.stake.ledger
  "Generic stake ledger for entity-agnostic stake tracking.
   Supports bond, slash, release, and top-up operations.
   Entity-ID-generic — works for resolver addresses, attestor IDs, etc.

   Ledger shape:
     {:stake/ledger       {entity-id {:stake/amount N
                                      :stake/unit  :accountability-points
                                      :stake/status :bonded}}
      :stake/operations   [{:stake/op :bond :stake/entity-id ...
                            :stake/tx-hash \"sha256-...\"}]
      :stake/ledger-hash  \"sha256-...\"}"
  (:require [resolver-sim.util.attribution :as attr]
            [resolver-sim.evidence.capture :as cap]))

;; ── Dynamic Ledger Registry ──────────────────────────────────────────────

(def ^:dynamic *ledger*
  "In-memory stake ledger atom.
   Map of entity-id -> stake record.
   Each record: {:stake/amount N :stake/unit kw :stake/status kw}"
  (atom nil))

(defmacro with-fresh-ledger
  "Execute body with a fresh empty stake ledger.
   The outer ledger is restored when body exits.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [*ledger* fresh-atom#]
       ~@body)))

(defn init-ledger!
  "Initialize the ledger with a seed map of entity-id -> initial amount.
   Each entry gets {:stake/amount amount :stake/unit :accountability-points :stake/status :bonded}."
  [seed]
  (reset! *ledger* (reduce-kv (fn [acc entity-id amount]
                                (assoc acc entity-id
                                       {:stake/amount (bigint amount)
                                        :stake/unit :accountability-points
                                        :stake/status :bonded}))
                              {} seed))
  nil)

(defn clear-ledger!
  "Reset the ledger to empty."
  []
  (reset! *ledger* {})
  nil)

;; ── Queries ──────────────────────────────────────────────────────────────

(defn get-stake
  "Get the current stake for an entity-id.
   Returns the stake record or nil if not found."
  [entity-id]
  (get @*ledger* entity-id))

(defn get-stake-amount
  "Get the current stake amount for an entity-id.
   Returns 0 if not found."
  [entity-id]
  (get-in @*ledger* [entity-id :stake/amount] 0))

;; ── Operations ───────────────────────────────────────────────────────────

(defn- ^:private abs-diff
  [a b]
  (let [diff (- a b)]
    (if (neg? diff) (- diff) diff)))

(defn- capture-stake-evidence!
  [op entity-id before-amount after-amount opts]
  (let [reason (keyword (str "stake/" (name op)))
        subject-type (or (:subject/type opts) :entity)]
    (attr/with-attribution
      {:subject/type subject-type
       :subject/id entity-id
       :action/type reason
       :evidence/reason reason}
      (cap/capture-event-evidence!
       reason
       {:stake/before before-amount}
       {:stake/after after-amount}
       (merge {:stake/entity entity-id
               :stake/amount (abs-diff after-amount before-amount)}
              (select-keys opts [:stake/violation-ref :stake/reason]))
       nil
       {:world-before @*ledger*
        :world-after (assoc @*ledger* entity-id
                            {:stake/amount after-amount
                             :stake/unit :accountability-points
                             :stake/status :bonded})}))))

(defn bond-stake
  "Bond (lock) stake for an entity-id.
   Increases the entity's stake by amount.
   Returns updated ledger entry.
   opts: :subject/type, :stake/reason"
  [entity-id amount & [opts]]
  (let [current (get-stake-amount entity-id)
        new-amount (+ current (bigint amount))
        entry {:stake/amount new-amount
               :stake/unit :accountability-points
               :stake/status :bonded}]
    (swap! *ledger* assoc entity-id entry)
    (capture-stake-evidence! :bond entity-id current new-amount opts)
    entry))

(defn top-up-stake
  "Add to an existing bonded stake.
   Same as bond-stake but semantically distinct (increase existing).
   Returns updated ledger entry."
  [entity-id amount & [opts]]
  (bond-stake entity-id amount opts))

(defn release-stake
  "Release (withdraw) stake for an entity-id.
   Decreases the entity's stake.
   Guards: amount must be positive, entity must have sufficient stake.
   Returns {:ok true :entry <entry>} or {:ok false :error kw}."
  [entity-id amount & [opts]]
  (let [current (get-stake-amount entity-id)]
    (cond
      (or (nil? amount) (not (number? amount)) (<= amount 0))
      {:ok false :error :invalid-amount}

      (> (bigint amount) current)
      {:ok false :error :insufficient-stake}

      :else
      (let [new-amount (- current (bigint amount))
            entry {:stake/amount new-amount
                   :stake/unit :accountability-points
                   :stake/status :bonded}]
        (swap! *ledger* assoc entity-id entry)
        (capture-stake-evidence! :release entity-id current new-amount opts)
        {:ok true :entry entry}))))

(defn slash-stake
  "Slash (penalty deduct) stake for an entity-id.
   Deducts amount from the entity's stake.
   Guards: amount must be positive, entity must have sufficient stake.
   Does NOT distribute slashed funds (caller's responsibility).
   Returns {:ok true :entry <entry> :slashed-amount <n>}
           or {:ok false :error kw}."
  [entity-id amount & [opts]]
  (let [current (get-stake-amount entity-id)
        actual (min current (bigint amount))]
    (cond
      (or (nil? amount) (not (number? amount)) (<= amount 0))
      {:ok false :error :invalid-amount}

      (zero? current)
      {:ok false :error :no-stake}

      :else
      (let [new-amount (- current actual)
            entry {:stake/amount new-amount
                   :stake/unit :accountability-points
                   :stake/status :bonded}]
        (swap! *ledger* assoc entity-id entry)
        (capture-stake-evidence! :slash entity-id current new-amount opts)
        {:ok true :entry entry :slashed-amount actual}))))

;; ── Batch Operations ─────────────────────────────────────────────────────

(defn apply-stake-actions
  "Apply a vector of stake action maps to the ledger.
   Each action: {:action/type :stake/slash :attestor/id \"...\" :stake/amount 100}
   Supported types: :stake/bond, :stake/slash, :stake/release, :stake/top-up
   Returns {:applied [<results>] :errors [<error-maps>]}."
  [actions & [opts]]
  (let [op-fn {:stake/bond    (fn [e a o] (bond-stake e a o) {:ok true})
               :stake/top-up  (fn [e a o] (top-up-stake e a o) {:ok true})
               :stake/slash   (fn [e a o] (slash-stake e a o))
               :stake/release (fn [e a o] (release-stake e a o))}]
    (loop [remaining actions
           applied []
           errors []]
      (if (empty? remaining)
        {:applied applied :errors errors}
        (let [{:keys [action/type attestor/id stake/amount]} (first remaining)
              f (get op-fn type)]
          (if (nil? f)
            (recur (rest remaining) applied
                   (conj errors {:action type :error :unknown-action-type}))
            (let [result (f id amount opts)]
              (if (:ok result)
                (recur (rest remaining) (conj applied result) errors)
                (recur (rest remaining) applied
                       (conj errors (assoc result :action type :attestor/id id)))))))))))
