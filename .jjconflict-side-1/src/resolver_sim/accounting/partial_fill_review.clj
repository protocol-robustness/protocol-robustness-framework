(ns resolver-sim.accounting.partial-fill-review
  "Bounded accounting consumer of subject-decisions. Evidence is pinned and
   rechecked; pre-settlement allocations and realized local payments are distinct."
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
                     :rules [:primary-log-coverage :custody-conservation :restricted-capital-10
                             :source-bound-70-available :recipient-policy-and-caps
                             :claim-liability-reconciliation :exact-terminal-refund
                             :purpose-and-uncertainty-boundaries]
                     :qualifies-capital-execution false}))
(def validation-root (:artifact/root validation-definition))

(defn file-root [bytes]
  (reference/sha256-ref (canonical/bytes->hex (canonical/hash-bytes bytes))))

(defn- parse-json [bytes]
  (json/read-str (String. ^bytes bytes "UTF-8") :key-fn keyword))

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
        states (:states (read "accounting-states.json"))
        transfers (read "transfers.json")
        terminal (read "terminal-observation.json")
        log (String. ^bytes (get files "v3-accounting-qualification.log") "UTF-8")
        observations (log-json log "PARTIAL_FILL_V3_ACCOUNTING_STATE_JSON ")
        claimants (get-in source [:realized-statement-input :allocation-context :claimants])
        rows (mapv (fn [name c] {:name name :claim-id (:claim-id c)
                                :beneficiary (:economic-owner-id c)
                                :requested (parse-long (:amount c))})
                   ["Alice" "Bob" "Carol"] claimants)
        available (parse-long (get-in source [:realized-statement-input :available]))
        request {:amount available :rounding :floor-with-largest-remainder
                 :ordering-policy :input-order :cap-treatment :unallocated
                 :items (mapv (fn [r] {:id (:claim-id r) :weight (:requested r) :cap (:requested r)}) rows)}
        reconstruction (exact/reconstruct request)
        replay (read "rust-replay.json")
        rows (mapv #(let [n (get-in reconstruction [:rows (:claim-id %) :allocated])]
                      (assoc % :allocated n :unmet (- (:requested %) n))) rows)
        lower #(str/lower-case %)
        reserve (lower (:reserve policy)) allocator (lower (:allocator policy))
        carol (lower (:beneficialOwnerAndRefundBeneficiary policy))
        expected-transfers (vec (concat [[reserve allocator 70]]
                                       (map #(vector allocator (lower (:beneficiary %)) (:allocated %)) rows)
                                       [[reserve carol 10]]))]
    (decisions/ensure! (and (= "eae.partial-fill.v3-live-context-evidence.v1" (get manifest "schema"))
                                (= 31337 (:chainId funding) (:chainId terminal))
                                (= 70 available) (= [50 30 20] (mapv :requested rows))
                                (= 70 (:total-allocated reconstruction))
                                (= [35 21 14] (mapv :allocated rows))
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
                                (= 10 (:restrictedAmount policy))
                                (= ["80" "70" "10"] (mapv (:economic-reserve-state source)
                                                                           [:gross-balance :available-amount :protected-capital]))
                                (= "31337" (get-in source [:prepared-operation :chain-id]))
                                (= (lower (:reserve policy)) (get-in source [:prepared-operation :reserve]))
                                (= (lower (:token policy)) (get-in source [:prepared-operation :token]))
                                (= (lower (:policyRoot policy)) (str "0x" (get-in source [:economic-reserve-state :policy-root])))
                                (= (:policyRoot policy) (:policyRoot funding) (:policyRoot terminal))
                                (= (:operationId policy) (:operationId terminal)
                                   (get-in source [:prepared-operation :operation-id]))
                                (= 80 (:observedTransferAmount funding) (:reserveBalanceAfterDeposit funding))
                                (true? (:sponsorTransferObserved funding))
                                (= (:refundBeneficiary funding) (:beneficialOwnerAndRefundBeneficiary policy)))
                       :source-policy-funding-mismatch)
    (decisions/ensure! (and (= 8 (count observations))
                                (= observations (mapv :observation states))
                                (= ["opening" "proof_admitted" "funded" "activated" "after_alice_claim"
                                    "after_bob_claim" "allocator_closed" "reserve_finalized"]
                                   (mapv :stage observations))
                                (= [transfers] (log-json log "PARTIAL_FILL_TRANSFERS_JSON "))
                                (= [terminal] (log-json log "PARTIAL_FILL_V3_TERMINAL_JSON "))
                                (= expected-transfers (mapv #(vector (lower (:from %)) (lower (:to %)) (:amount %)) transfers))
                                (every? #(= (lower (:token policy)) (lower (:token %))) transfers))
                       :primary-observation-mismatch)
    (doseq [[i observation] (map-indexed vector observations)]
      (decisions/ensure! (= 80 (reduce + (map observation [:reserveBalance :allocatorBalance :aliceBalance :bobBalance :carolBalance])))
                         :custody-reconciliation-failed)
      (when (>= i 3)
        (decisions/ensure! (and (= 70 (+ (:claimedAmount observation) (:outstandingLiability observation)))
                                    (= (:allocatorBalance observation) (:outstandingLiability observation)))
                           :claim-liability-reconciliation-failed))
      (when (<= 2 i 6) (decisions/ensure! (= 10 (:reserveBalance observation)) :restricted-capital-consumed)))
    (decisions/ensure! (and (:reserveFinalized terminal) (= "Closed" (:operationState terminal))
                                (= "Finalized" (:ratificationState terminal))
                                (= 0 (:outstandingLiability terminal) (:reserveBalance terminal) (:allocatorBalance terminal))
                                (= 10 (:protectedCapitalRefunded terminal))
                                (= 24 (:beneficiaryBalance terminal))
                                (= carol (lower (:refundBeneficiary terminal)))) :terminal-refund-mismatch)
    {:evidence/root (decisions/root "partial-fill-accounting-evidence.v1"
                                    {:manifest expected-manifest :files digests})
     :source source :policy policy :funding funding :rows rows :observations observations
     :transfers transfers :terminal terminal}))

(def statement-fields
  #{:artifact/schema :artifact/root :domain :purpose :phase :predecessor/root
    :evidence/root :source/root :economic-policy/root :operation/id
    :capital :rows :settlement :scope :limitations :synthetic?})

(defn statement
  "Create a candidate, never an acceptance. Synthetic B changes only proposed
   interpretation fields, and has no fabricated settlement or transfer data."
  ([evidence phase] (statement evidence phase false nil))
  ([evidence phase synthetic? predecessor]
   (decisions/ensure! (and (contains? purposes phase) (or (not synthetic?) (= :allocation phase))) :invalid-phase)
   (let [{:keys [rows source policy] :as material} (evidence-material evidence)
         rows (if synthetic?
                (mapv #(assoc % :allocated (* 4/5 (:requested %)) :unmet (* 1/5 (:requested %))) rows)
                rows)
         ;; Multiplication is integral for this frozen fixture; ratios never enter canonical artifacts.
         rows (mapv #(-> % (update :allocated long) (update :unmet long)) rows)]
     (decisions/rooted schema
                       {:domain domain :purpose (purposes phase) :phase phase :predecessor/root predecessor
                        :evidence/root (:evidence/root material)
                        :source/root (get-in source [:prepared-operation :policy-root])
                        :economic-policy/root (:policyRoot policy) :operation/id (:operationId policy)
                        :capital {:deposited 80 :allocatable (if synthetic? 80 70) :restricted (if synthetic? 0 10)}
                        :rows rows
                        :settlement (when (= phase :realized)
                                      {:withdrawals (mapv (fn [row transfer]
                                                          {:name (:name row) :beneficiary (:to transfer) :paid (:amount transfer)})
                                                        rows (subvec (:transfers material) 1 4))
                                       :restricted-refund {:beneficiary (:refundBeneficiary (:terminal material))
                                                           :amount (:amount (last (:transfers material)))}
                                       :remaining-liability 0 :reserve-balance 0 :allocator-balance 0})
                        :scope scope :limitations limitations :synthetic? synthetic?}))))

(defn verify-statement [evidence subject]
  (let [material (evidence-material evidence)
        expected (statement evidence (:phase subject) false (:predecessor/root subject))
        check (fn [value] (true? value))
        checks
        (try
          {:statement-identity (and (= statement-fields (set (keys subject)))
                                     (= (:artifact/root subject) (decisions/artifact-root subject)))
           :evidence-and-scope (= (select-keys expected [:domain :purpose :phase :scope :limitations :evidence/root :source/root :economic-policy/root :operation/id])
                                   (select-keys subject [:domain :purpose :phase :scope :limitations :evidence/root :source/root :economic-policy/root :operation/id]))
           :conservation (and (= 100 (reduce + (map :requested (:rows subject))))
                              (every? #(= (:requested %) (+ (:allocated %) (:unmet %))) (:rows subject))
                              (= 80 (+ (get-in subject [:capital :restricted])
                                       (reduce + (map :allocated (:rows subject)))))
                              (= (get-in subject [:capital :allocatable]) (reduce + (map :allocated (:rows subject)))))
           :restricted-capital (= (:capital expected) (:capital subject))
           :observation-classification (false? (:synthetic? subject))
           :recipient-policy (= (:rows expected) (:rows subject))
           :settlement-coverage (= (:settlement expected) (:settlement subject))}
          (catch Exception _ {:malformed-statement false}))
        verified? (every? check (vals checks))]
    {:verified? verified? :eligible? verified? :evidence/root (:evidence/root material)
     :checks checks :purpose (:purpose subject) :scope scope :limitations limitations
     :authority-boundary :local-fixture-policy-only :capital-authority false}))

(defn verifier [evidence]
  (with-meta #(verify-statement evidence %) {:validation/root validation-root}))
