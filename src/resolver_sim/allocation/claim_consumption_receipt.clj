(ns resolver-sim.allocation.claim-consumption-receipt
  "claim-consumption-receipt.v1 — terminal, content-addressed receipt for claim
   consumption in the probabilistic-allocation lifecycle.

   `:claim-consumption-started` is the terminal, irreversible cutpoint of the
   probabilistic-allocation window (ADR-0007; `cancellation-window.v1`). This
   receipt records that a claim, produced by an allocation result, was consumed:
   it binds the claim, the allocation result root, the consumed amount, the
   consumed claimable artifact, a single-use consumption key, and the terminal
   lifecycle state — so consumption is content-addressed and recomputable, and
   no amount or claimable artifact can be substituted without breaking
   verification.

   Mirrors the force-authorisation-consumption.v1/.v2 receipt conventions
   (resolver-sim.benchmark.researcher-force-authorisation): statuses
   :consumed | :failed-after-consumption | :rolled-back-after-consumption, and a
   required terminal-evidence hash for failed/rolled-back attempts."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version
  "Version of the claim-consumption receipt artifact."
  "claim-consumption-receipt.v1")

(def ^:const claim-consumption-statuses
  "Terminal claim-consumption statuses. All are terminal — reuse is not
   permitted."
  #{:consumed :failed-after-consumption :rolled-back-after-consumption})

(defn valid-claim-consumption-status?
  [s]
  (contains? claim-consumption-statuses s))

(def ^:const receipt-status-rules
  "Status/root validation rules. For :failed-after-consumption the terminal
   evidence hash must be present; if none was captured use
   {:status :not-captured :reason-code kw} to make the absence semantic."
  {:consumed                      {:claim-amount :required :terminal-evidence :optional}
   :failed-after-consumption      {:claim-amount :optional :terminal-evidence :required}
   :rolled-back-after-consumption {:claim-amount :required :terminal-evidence :required}})

(defn claim-consumption-conflict-key
  "The canonical key the claim-consumption cutpoint transition contends on
   atomically (cancellation-window contract 6 analogue): cancellation of a
   probabilistic-allocation round and the claim-consumption transition must
   serialize over exactly one of these so exactly one may win.
   (compare-and-transition! {:target/id ... :claim/id ...
   :expected-state :result-accepted} :claim-consumption-started)."
  [target-id claim-id lifecycle-window]
  {:target/id target-id
   :claim/id claim-id
   :lifecycle/profile-id (:profile/id lifecycle-window)
   :lifecycle/profile-version (:profile/version lifecycle-window)})

(defn build-claim-consumption-receipt
  "Build a terminal claim-consumption-receipt.v1 artifact.

   Required:
     claim-consumption/id        — qualified keyword
     claim/id                    — the claim being consumed
     allocation/result-root      — sha256 of the allocation result that produced
                                   the claimable
     claim/amount                — the consumed amount (integer base units)
     claim-consumption/consumed-claimable-hash — sha256 of the claimable artifact
                                   being consumed
     claim-consumption/status    — :consumed | :failed-after-consumption |
                                   :rolled-back-after-consumption
     claim-consumption/consumption-key — single-use consumption key

   Conditional (per status):
     claim-consumption/terminal-evidence-hash — required for
                                   :failed-after-consumption and
                                   :rolled-back-after-consumption

   Optional:
     claim-consumption/hash      — pre-computed hash (rejected on mismatch)

   Fails closed: non-integral or missing amounts, non-canonical references, and
   status/evidence mismatches are rejected.

   Returns the receipt map with :claim-consumption/hash computed."
  [{:allocation/keys [result-root]
    :claim-consumption/keys [consumed-claimable-hash status consumption-key
                             terminal-evidence-hash hash]
    :as receipt}]
  (let [id (:claim-consumption/id receipt)
        claim-id (:claim/id receipt)
        claim-amount (:claim/amount receipt)
        errors (atom [])
        rule (get receipt-status-rules status)
        add-error! (fn [msg] (swap! errors conj msg))]
    (when-not (some? id)
      (add-error! "missing :claim-consumption/id"))
    (when (and (some? id) (not (keyword? id)))
      (add-error! ":claim-consumption/id must be a keyword"))
    (when-not (some? claim-id)
      (add-error! "missing :claim/id"))
    (when-not (hash-ref/valid-sha256-ref? result-root)
      (add-error! "missing or invalid :allocation/result-root"))
    (when (or (not (integer? claim-amount)) (neg? claim-amount))
      (add-error! "claim/amount must be a non-negative integer"))
    (when-not (hash-ref/valid-sha256-ref? consumed-claimable-hash)
      (add-error! "missing or invalid :claim-consumption/consumed-claimable-hash"))
    (when-not (and (some? status) (valid-claim-consumption-status? status))
      (add-error! (str "invalid or missing :claim-consumption/status: " status)))
    (when-not (some? consumption-key)
      (add-error! "missing :claim-consumption/consumption-key"))
    (when (and rule (= :required (:claim-amount rule)) (nil? claim-amount))
      (add-error! (str "status " status " requires a claim/amount")))
    (when (and rule (= :required (:terminal-evidence rule))
               (nil? terminal-evidence-hash))
      (add-error! (str "status " status " requires :claim-consumption/terminal-evidence-hash")))
    (when (and terminal-evidence-hash
               (not (hash-ref/valid-sha256-ref? terminal-evidence-hash)))
      (add-error! "invalid :claim-consumption/terminal-evidence-hash"))
    (when (seq @errors)
      (throw (ex-info (str "Claim-consumption receipt build failed: "
                           (str/join "; " @errors))
                      {:errors (vec @errors)})))
    (let [base (cond-> {:schema-version schema-version
                        :claim-consumption/id id
                        :claim/id claim-id
                        :allocation/result-root result-root
                        :claim/amount claim-amount
                        :claim-consumption/consumed-claimable-hash
                        consumed-claimable-hash
                        :claim-consumption/status status
                        :claim-consumption/consumption-key consumption-key}
                 terminal-evidence-hash
                 (assoc :claim-consumption/terminal-evidence-hash
                        terminal-evidence-hash))
          computed (str "sha256:" (hc/domain-hash :claim-consumption-receipt base))]
      (when (and (some? hash) (not= hash computed))
        (throw (ex-info "Declared claim-consumption/hash does not match computed value"
                        {:declared hash :computed computed})))
      (assoc base :claim-consumption/hash computed))))

(defn claim-consumption-receipt-valid?
  "Quick structural validity check."
  [receipt]
  (and (= schema-version (:schema-version receipt))
       (some? (:claim-consumption/id receipt))
       (some? (:claim/id receipt))
       (some? (:allocation/result-root receipt))
       (integer? (:claim/amount receipt))
       (some? (:claim-consumption/consumed-claimable-hash receipt))
       (valid-claim-consumption-status? (:claim-consumption/status receipt))
       (some? (:claim-consumption/consumption-key receipt))
       (some? (:claim-consumption/hash receipt))))

(defn validate-claim-consumption-receipt
  "Standalone validator: schema version, required content-addressed references,
   non-negative integral amount, status/evidence rules, and self-hash
   recomputation.

   Returns {:valid? bool :errors [string]}."
  [receipt]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version receipt))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version receipt))))
    (when-not (some? (:claim-consumption/id receipt))
      (swap! errors conj "missing :claim-consumption/id"))
    (when-not (some? (:claim/id receipt))
      (swap! errors conj "missing :claim/id"))
    (when-not (hash-ref/valid-sha256-ref? (:allocation/result-root receipt))
      (swap! errors conj "invalid :allocation/result-root"))
    (let [amt (:claim/amount receipt)]
      (when (or (not (integer? amt)) (neg? amt))
        (swap! errors conj "claim/amount must be a non-negative integer")))
    (when-not (hash-ref/valid-sha256-ref?
               (:claim-consumption/consumed-claimable-hash receipt))
      (swap! errors conj "invalid :claim-consumption/consumed-claimable-hash"))
    (when-not (valid-claim-consumption-status? (:claim-consumption/status receipt))
      (swap! errors conj (str "invalid :claim-consumption/status: "
                              (:claim-consumption/status receipt))))
    (when-not (some? (:claim-consumption/consumption-key receipt))
      (swap! errors conj "missing :claim-consumption/consumption-key"))
    (let [status (:claim-consumption/status receipt)
          rule (get receipt-status-rules status)]
      (when (and rule (= :required (:terminal-evidence rule))
                 (nil? (:claim-consumption/terminal-evidence-hash receipt)))
        (swap! errors conj (str "status " status
                                " requires :claim-consumption/terminal-evidence-hash"))))
    (when (and (some? (:claim-consumption/hash receipt))
               (not= (:claim-consumption/hash receipt)
                     (str "sha256:" (hc/domain-hash :claim-consumption-receipt
                                                    (dissoc receipt
                                                            :claim-consumption/hash)))))
      (swap! errors conj "claim-consumption/hash mismatch"))
    {:valid? (empty? @errors) :errors (vec @errors)}))
