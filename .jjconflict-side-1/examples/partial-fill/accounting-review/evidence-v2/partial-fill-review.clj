(ns resolver-sim.accounting.partial-fill-review
  "Bounded accounting consumer of subject-decisions. Evidence is pinned and
   rechecked; pre-settlement allocations and realized local payments are distinct.

   Economic values are DERIVED from the pinned observations and the observed
   policy rather than hardcoded: total/restricted/allocatable capital, per-
   recipient allocations (via the independent exact verifier), funded and
   remaining liabilities, the restricted refund, settlement outflows and terminal
   balances. Window checks compare against policy/observation-derived thresholds
   so the adapter generalises to a modified but internally consistent fixture."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.assurance.subject-decisions :as decisions]
            [resolver-sim.pro-rata.exact-verifier :as exact])
  (:import [java.nio.file Files]))

(def schema "partial-fill-accounting-statement.v1")
(def domain :accounting/partial-fill-v3)
(def scope "Local Foundry V3 call-boundary accounting; chain 31337; observed accounts and this operation only.")
(def limitations
  [:external-beneficial-ownership-unverified :outside-obligation-completeness-unverified
   :local-synthetic-test-token-only :no-public-chain-execution :not-an-accounting-audit])
(def purposes {:allocation :accounting/allocation-classification
               :realized :accounting/realized-settlement})
(def validation-definition
  (decisions/rooted "partial-fill-accounting-verification-method.v1"
                    {:domain domain :subject-schema schema
                     :source :manifest-pinned-v3-local-observations
                     :recipient-verifier exact/frozen-spec-version
                     :recipient-verifier-corpus (exact/corpus-identity)
                     :rules [:primary-log-coverage :custody-conservation :policy-restricted-capital
                             :policy-available-capital :recipient-policy-and-caps
                             :claim-liability-reconciliation :exact-terminal-refund
                             :purpose-and-uncertainty-boundaries]
                     :qualifies-capital-execution false}))
(def validation-root (:artifact/root validation-definition))

(defn file-root [bytes]
  (reference/sha256-ref (canonical/bytes->hex (canonical/hash-bytes bytes))))

(defn- parse-json [bytes]
  (json/read-str (String. ^bytes bytes "UTF-8") :key-fn keyword))

(defn- lower [x] (str/lower-case (str x)))

(defn load-evidence!
  "Expected manifest hash is independently pinned by the caller. No proofs,
   source files, chain records or commitments are rewritten."
  [directory expected-manifest]
  (let [manifest-bytes (Files/readAllBytes (.toPath (io/file directory "manifest.json")))
        _ (decisions/ensure! (= expected-manifest (file-root manifest-bytes)) :manifest-pin-mismatch)
        manifest (json/read-str (String. ^bytes manifest-bytes "UTF-8"))
        paths (keys (get manifest "files"))]
    (decisions/ensure! (every? #(and (string? %) (not (str/starts-with? % "/"))
                                    (not (some #{".." "."} (str/split % #"/")))) paths)
                       :unsafe-evidence-path)
    {:manifest-bytes manifest-bytes :expected-manifest expected-manifest
     :files (into {} (for [path paths]
                       [path (Files/readAllBytes (.toPath (io/file directory path)))]))}))

(defn- log-json [text prefix]
  (mapv #(json/read-str (second (str/split % (re-pattern prefix) 2)) :key-fn keyword)
        (filter #(str/includes? % prefix) (str/split-lines text))))

(def participant-balance-keys [:reserveBalance :allocatorBalance :aliceBalance :bobBalance :carolBalance])

(defn derive-values
  "Pure derivation of economic values from parsed observations and the observed
   policy. No economic literal from the retained fixture appears here."
  [source policy transfers terminal]
  (let [claimants (get-in source [:realized-statement-input :allocation-context :claimants])
        available (parse-long (get-in source [:realized-statement-input :available]))
        gross (parse-long (get-in source [:economic-reserve-state :gross-balance]))
        restricted (long (:restrictedAmount policy))
        rows (mapv (fn [c] {:name (:claim-id c)
                            :claim-id (:claim-id c)
                            :beneficiary (:economic-owner-id c)
                            :requested (parse-long (:amount c))})
                   claimants)
        request {:amount available :rounding :floor-with-largest-remainder
                 :ordering-policy :input-order :cap-treatment :unallocated
                 :items (mapv (fn [r] {:id (:claim-id r) :weight (:requested r) :cap (:requested r)}) rows)}
        reconstruction (exact/reconstruct request)
        rows (mapv #(let [n (get-in reconstruction [:rows (:claim-id %) :allocated])]
                      (assoc % :allocated n :unmet (- (:requested %) n))) rows)
        funded (first transfers)
        refund (last transfers)
        withdrawals (subvec (vec transfers) 1 (dec (count transfers)))
        withdrawal-by-beneficiary (into {} (for [t withdrawals] [(lower (:to t)) (:amount t)]))]
    (decisions/ensure! (= (count withdrawals) (count rows)) :withdrawal-coverage)
    {:capital {:deposited gross :allocatable available :restricted restricted}
     :rows rows
     :funded (:amount funded)
     :withdrawal-by-beneficiary withdrawal-by-beneficiary
     :restricted-refund {:beneficiary (lower (:beneficialOwnerAndRefundBeneficiary policy))
                         :amount (:amount refund)}
     :remaining-liability (:outstandingLiability terminal)
     :reserve-balance (:reserveBalance terminal)
     :allocator-balance (:allocatorBalance terminal)
     :terminal-beneficiary-balance (:beneficiaryBalance terminal)}))

(defn evidence-material
  "Verify every retained file digest/length on every resolution, then rejoin
   the primary log, source, getters and transfers. Flags in a summary do not
   substitute for those checks. This does not execute SP1 or a chain client."
  [{:keys [manifest-bytes expected-manifest files]}]
  (decisions/ensure! (= expected-manifest (file-root manifest-bytes)) :manifest-pin-mismatch)
  (let [manifest (json/read-str (String. ^bytes manifest-bytes "UTF-8"))
        entries (get manifest "files")
        _ (decisions/ensure! (= (set (keys entries)) (set (keys files))) :evidence-file-coverage)
        digests (into {} (for [[path entry] entries
                               :let [bytes (get files path) digest (file-root bytes)]]
                           (do (decisions/ensure! (and (= (alength ^bytes bytes) (get entry "bytes"))
                                                       (= digest (str "sha256:" (get entry "sha256"))))
                                                  :evidence-file-integrity)
                               [path digest])))
        read #(parse-json (get files %))
        source (read "witness.json")
        policy (read "protected-capital-policy.json")
        funding (read "funding-observation.json")
        accounting-states (read "accounting-states.json")
        transfers (read "transfers.json")
        terminal (read "terminal-observation.json")
        log (String. ^bytes (get files "v3-accounting-qualification.log") "UTF-8")
        observations (log-json log "PARTIAL_FILL_V3_ACCOUNTING_STATE_JSON ")
        replay (read "rust-replay.json")
        derived (derive-values source policy transfers terminal)
        {:keys [rows capital funded]} derived
        deposit (:deposited capital) available (:allocatable capital) restricted (:restricted capital)
        reserve (lower (:reserve policy)) allocator (lower (:allocator policy))
        carol (lower (:beneficialOwnerAndRefundBeneficiary policy))
        expected-transfers (vec (concat [[reserve allocator funded]]
                                        (map #(vector allocator (lower (:beneficiary %)) (:allocated %)) rows)
                                        [[reserve (get-in derived [:restricted-refund :beneficiary])
                                          (get-in derived [:restricted-refund :amount])]]))]
    (decisions/ensure! (and (= "eae.partial-fill.v3-live-context-evidence.v1" (get manifest "schema"))
                                (= (:chainId funding) (:chainId terminal))
                                (= (str (:chainId funding)) (get-in source [:prepared-operation :chain-id]))
                                (= deposit (parse-long (get-in source [:economic-reserve-state :gross-balance])))
                                (= available (parse-long (get-in source [:realized-statement-input :available])))
                                (= restricted (:restrictedAmount policy))
                                (= available (:availableCapital funding))
                                (= deposit (:observedTransferAmount funding) (:reserveBalanceAfterDeposit funding))
                                (= (reduce + 0 (map :requested rows))
                                   (+ (reduce + 0 (map :allocated rows)) (reduce + 0 (map :unmet rows))))
                                (= deposit (+ restricted (reduce + 0 (map :allocated rows))))
                                (= available (reduce + 0 (map :allocated rows)))
                                (= (read "rust-replay.json") (read "clojure-replay.json"))
                                (= (str "0x" (:realized-statement-root replay))
                                   (get-in source [:prepared-operation :policy-root]))
                                (= (get-in source [:prepared-operation :operation-id]) (:operation-id replay))
                                (= (str "0x" (:public-values replay)) (:public_values (read "proof.json")))
                                (= (mapv #(select-keys % [:claim-id :beneficiary :allocated]) rows)
                                   (mapv #(hash-map :claim-id (:claim-id %) :beneficiary (:beneficiary %)
                                                    :allocated (parse-long (:amount %))) (:ordered-entitlements replay)))
                                (= {:mode "pro-rata" :rounding-policy "largest-remainder"}
                                   (get-in source [:realized-statement-input :policy]))
                                (= "unmet-request" (get-in source [:realized-statement-input :fail-action-policy :mode]))
                                (= (lower (:reserve policy)) (get-in source [:prepared-operation :reserve]))
                                (= (lower (:token policy)) (get-in source [:prepared-operation :token]))
                                (= (lower (:policyRoot policy)) (str "0x" (get-in source [:economic-reserve-state :policy-root])))
                                (= (:policyRoot policy) (:policyRoot funding) (:policyRoot terminal))
                                (= (:operationId policy) (:operationId terminal)
                                   (get-in source [:prepared-operation :operation-id]))
                                (true? (:sponsorTransferObserved funding))
                                (= (:refundBeneficiary funding) (:beneficialOwnerAndRefundBeneficiary policy)))
                     :source-policy-funding-mismatch)
    (decisions/ensure! (and (= 8 (count observations))
                                (= observations (mapv :observation (:states accounting-states)))
                                (= ["opening" "proof_admitted" "funded" "activated" "after_alice_claim"
                                    "after_bob_claim" "allocator_closed" "reserve_finalized"]
                                   (mapv :stage observations))
                                (= [transfers] (log-json log "PARTIAL_FILL_TRANSFERS_JSON "))
                                (= [terminal] (log-json log "PARTIAL_FILL_V3_TERMINAL_JSON "))
                                (= expected-transfers (mapv #(vector (lower (:from %)) (lower (:to %)) (:amount %)) transfers))
                                (every? #(= (lower (:token policy)) (lower (:token %))) transfers))
                     :primary-observation-mismatch)
    (doseq [[i observation] (map-indexed vector observations)]
      (decisions/ensure! (= deposit (reduce + 0 (map observation participant-balance-keys)))
                         :custody-reconciliation-failed)
      (when (>= i 3)
        (decisions/ensure! (and (= funded (+ (:claimedAmount observation) (:outstandingLiability observation)))
                                    (= (:allocatorBalance observation) (:outstandingLiability observation)))
                           :claim-liability-reconciliation-failed))
      (when (<= 2 i 6) (decisions/ensure! (= restricted (:reserveBalance observation)) :restricted-capital-consumed)))
    (decisions/ensure! (and (:reserveFinalized terminal) (= "Closed" (:operationState terminal))
                                (= "Finalized" (:ratificationState terminal))
                                (= 0 (:outstandingLiability terminal) (:reserveBalance terminal) (:allocatorBalance terminal))
                                (= restricted (:protectedCapitalRefunded terminal))
                                (= (:beneficiaryBalance terminal) (:carolBalance (last observations)))
                                (= carol (lower (:refundBeneficiary terminal)))) :terminal-refund-mismatch)
    {:evidence/root (decisions/root "partial-fill-accounting-evidence.v1"
                                    {:manifest expected-manifest :files digests})
     :source source :policy policy :funding funding :rows rows :observations observations
     :transfers transfers :terminal terminal :derived derived}))

(def statement-fields
  #{:artifact/schema :artifact/root :domain :purpose :phase :predecessor/root
    :evidence/root :source/root :economic-policy/root :operation/id
    :capital :rows :settlement :scope :limitations :synthetic?})

(defn- synthetic-rows [rows]
  (mapv #(-> % (assoc :allocated (* 4/5 (:requested %)) :unmet (* 1/5 (:requested %)))
            (update :allocated long) (update :unmet long)) rows))

(defn statement
  "Create a candidate, never an acceptance. Synthetic B changes only proposed
   interpretation fields, and has no fabricated settlement or transfer data.
   Every economic value is derived from the pinned evidence/policy; the only
   synthetic change is the proposed 4/5 allocation with zero restricted capital."
  ([evidence phase] (statement evidence phase false nil))
  ([evidence phase synthetic? predecessor]
   (decisions/ensure! (and (contains? purposes phase) (or (not synthetic?) (= :allocation phase))) :invalid-phase)
   (let [{:keys [rows source policy derived], evidence-root :evidence/root} (evidence-material evidence)
         rows (if synthetic? (synthetic-rows rows) rows)
         capital (if synthetic?
                   {:deposited (get-in derived [:capital :deposited])
                    :allocatable (get-in derived [:capital :deposited])
                    :restricted 0}
                   (:capital derived))
         settlement (when (= phase :realized)
                      (assoc (select-keys derived [:restricted-refund :remaining-liability
                                                   :reserve-balance :allocator-balance])
                             :withdrawals
                             (mapv (fn [row]
                                     {:name (:name row) :beneficiary (:beneficiary row)
                                      :paid (get-in derived [:withdrawal-by-beneficiary (lower (:beneficiary row))])})
                                   rows)))]
     (decisions/rooted schema
                       {:domain domain :purpose (purposes phase) :phase phase :predecessor/root predecessor
                        :evidence/root evidence-root
                        :source/root (get-in source [:prepared-operation :policy-root])
                        :economic-policy/root (:policyRoot policy) :operation/id (:operationId policy)
                        :capital capital
                        :rows rows
                        :settlement settlement
                        :scope scope :limitations limitations :synthetic? synthetic?}))))

(defn cross-history-consistent?
  "Independent consistency check for the separately signed allocation and
   realized-settlement statements, which share no signed predecessor link. A
   consumer MAY only connect them when they name the same operation, source
   evidence and economic policy. No immutable record is modified or required."
  [allocation realized]
  (boolean
   (and (map? allocation) (map? realized)
        (= (:operation/id allocation) (:operation/id realized))
        (= (:evidence/root allocation) (:evidence/root realized))
        (= (:source/root allocation) (:source/root realized))
        (= (:economic-policy/root allocation) (:economic-policy/root realized))
        (= (:phase allocation) :allocation)
        (= (:phase realized) :realized))))

(defn verify-statement [evidence subject]
  (let [material (evidence-material evidence)
        expected (statement evidence (:phase subject) false (:predecessor/root subject))
        check (fn [value] (true? value))
        rows (:rows subject)
        sum-alloc (reduce + 0 (map :allocated rows))
        checks
        (try
          {:statement-identity (and (= statement-fields (set (keys subject)))
                                     (= (:artifact/root subject) (decisions/artifact-root subject)))
           :evidence-and-scope (= (select-keys expected [:domain :purpose :phase :scope :limitations :evidence/root :source/root :economic-policy/root :operation/id])
                                   (select-keys subject [:domain :purpose :phase :scope :limitations :evidence/root :source/root :economic-policy/root :operation/id]))
           :conservation (and (every? #(= (:requested %) (+ (:allocated %) (:unmet %))) rows)
                              (= (get-in subject [:capital :deposited])
                                 (+ (get-in subject [:capital :restricted]) sum-alloc))
                              (= (get-in subject [:capital :allocatable]) sum-alloc)
                              (= (reduce + 0 (map :requested rows))
                                 (+ sum-alloc (reduce + 0 (map :unmet rows)))))
           :restricted-capital (= (:capital expected) (:capital subject))
           :observation-classification (false? (:synthetic? subject))
           :recipient-policy (= (:rows expected) (:rows subject))
           :settlement-coverage (= (:settlement expected) (:settlement subject))}
          (catch Exception _ {:malformed-statement false}))
        verified? (every? check (vals checks))]
    {:verified? verified? :eligible? verified?
     :evidence/root (:evidence/root material) :checks checks :purpose (:purpose subject)
     :scope scope :limitations limitations
     :authority-boundary :local-fixture-policy-only :capital-authority false}))

(defn verifier [evidence]
  (with-meta #(verify-statement evidence %) {:validation/root validation-root}))
