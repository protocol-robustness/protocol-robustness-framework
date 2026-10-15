(ns partial-fill-accounting-review
  "Local generation/self-reproduction and independently-anchored qualification
   of the Partial Fill V3 accounting-review bundle.

   Two deliberately separate operations:
     * LOCAL  — recompute the bundle from pinned source evidence and check its
                internal consistency. This proves reproducibility only. It never
                confers independent trust, and the bundle's own `trust-pins.edn`
                is not accepted as an anchor.
     * ANCHORED — additionally require an *externally supplied* pins file that
                binds the bundle manifest, the source manifest, the validation
                method, the role authorities, the policy roots and the history
                checkpoints. Missing, mismatched or substituted identities fail
                closed.

   Obtain the pins from a channel you independently trust; this program cannot
   establish the provenance of the pins file you hand it, only that the bundle
   is consistent with it."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.assurance.subject-decisions :as d]
            [resolver-sim.accounting.partial-fill-review :as a]
            [resolver-sim.hash.canonical :as canonical])
  (:import [java.security KeyPairGenerator]
           [java.util Base64]
           [java.nio.file Files]))

;; The retained V3 source-evidence manifest pin. Used for generation and local
;; reproduction. Anchored verification takes this from the trusted pins instead.
(def source-manifest-pin "sha256:48afd9aa8553076ba24b017344d07dc3cb7fa2756a5e591caf4f2881feb05798")
(def default-source "examples/partial-fill/evidence/v3-live-context")

(def pins-schema "eae.accounting-review-trust-pins.v2")
(def pins-domain "accounting-review-trust-pins.v2")
(def manifest-schema "eae.accounting-review-manifest.v1")
(def qualification-schema "eae.accounting-review-qualification.v2")

(defn read-edn [directory file] (edn/read-string (slurp (io/file directory file))))
(defn write-edn [directory file value] (spit (io/file directory file) (str (pr-str value) "\n")))
(defn keypair [] (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(defn pem [pair]
  (str "-----BEGIN PUBLIC KEY-----\n" (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic pair))) "\n-----END PUBLIC KEY-----\n"))

(defn build-log [p verifier subjects actions keys]
  (reduce (fn [history [subject action]]
            (let [state (d/reconstruct p verifier subjects history)
                  request (d/decision p state verifier subject action)
                  event (d/sign-decision request (.getPrivate (keys (if (= action :ratify) :ratifier :reviewer))))]
              (d/append p verifier subjects history event))) [] actions))

(defn decision-view [event]
  {:root (:artifact/root event) :action (name (:decision event))
   :domain (str (:domain event)) :purpose (str (:purpose event))
   :policyRoot (:policy/root event) :subjectRoot (:subject/root event)
   :verificationRoot (:verification/root event) :authorityId (:authority/id event)
   :sequence (:sequence event) :previousRoot (:history/predecessor-root event)
   :acceptanceRoot (:acceptance/root event) :supersedesRoot (:supersedes/root event)
   :signatureHex (get-in event [:authorization :signature :value])
   :payloadType (get-in event [:authorization :payload :payload-type])})

(defn subject-view [p s report]
  {:root (:artifact/root s) :phase (name (:phase s)) :synthetic (:synthetic? s)
   :sourceRoot (:source/root s) :economicPolicyRoot (:economic-policy/root s)
   :evidenceRoot (:evidence/root s) :allocations (mapv :allocated (:rows s))
   :unmet (reduce + (map :unmet (:rows s))) :restricted (get-in s [:capital :restricted])
   :verification {:passed (:verified? report) :eligible (:eligible? report)
                  :root (d/verification-root p s report)
                  :conservation (get-in report [:checks :conservation])
                  :restriction (get-in report [:checks :restricted-capital])
                  :recipientPolicy (get-in report [:checks :recipient-policy])}})

(defn policy-view [p initial]
  {:root (:artifact/root p) :domain (str (:domain p)) :purpose (str (:purpose p))
   :validationRoot (:validation-root p)
   :reviewer (:reviewer p) :ratifier (:ratifier p) :initialHistoryRoot (:history/root initial)})

(defn summary [source-pin source policies subjects histories verifier]
  (let [allocation (:allocation policies) realized (:realized policies)
        by-phase (group-by :phase (vals subjects))
        original (first (filter #(not (:synthetic? %)) (:allocation by-phase)))
        invalid (first (filter :synthetic? (:allocation by-phase)))
        settled (first (:realized by-phase))
        history-view (fn [p events]
                       (let [state (d/reconstruct p verifier subjects events)]
                         {:policy (policy-view p (d/initial-state p))
                          :events (mapv decision-view events)
                          :checkpoint {:root (:history/root state) :sequence (:sequence state)}
                          :authoritativeSubject (get-in state [:authoritative :subject/root])}))]
    {:schema "eae.accounting-review-demo.v1" :localOnly true :publicChain false
     :authority "Ephemeral local fixture reviewer and separate ratifier; no external appointment established."
     :sourceManifest source-pin :sourceEvidenceRoot (:evidence/root (a/evidence-material source))
     :limitations (mapv name a/limitations)
     :candidates {:A (subject-view allocation original (verifier original))
                  :B (subject-view allocation invalid (verifier invalid))
                  :realized (subject-view realized settled (verifier settled))}
     :allocation (history-view allocation (:allocation histories))
     :realized (history-view realized (:realized histories))
     :capitalAuthority false :simulationCreatesRecords false}))

(defn canonical-preimages [artifacts]
  (mapv (fn [artifact]
          {:root (:artifact/root artifact) :schema (:artifact/schema artifact)
           :hex (canonical/bytes->hex
                 (canonical/domain-hash-preimage-bytes
                  (:artifact/schema artifact) (dissoc artifact :artifact/root :authorization)))})
        (sort-by :artifact/root artifacts)))

;; ── trusted-pin identities ───────────────────────────────────────────────────

(defn authorities-of [policies]
  (into {} (for [[phase p] policies]
             [phase {:reviewer (:reviewer p) :ratifier (:ratifier p)}])))

(defn policy-roots-of [policies]
  (into {} (for [[phase p] policies] [phase (:artifact/root p)])))

(defn build-pins [policies checkpoints bundle-manifest source-pin]
  (let [base {:schema pins-schema
              :bundle-manifest bundle-manifest
              :source-manifest source-pin
              :validation-root a/validation-root
              :authorities (authorities-of policies)
              :policy-roots (policy-roots-of policies)
              :checkpoints checkpoints}]
    (assoc base :pins/root (d/root pins-domain base))))

(defn- inside? [parent child]
  (let [p (.getCanonicalPath (io/file parent))
        c (.getCanonicalPath (io/file child))]
    (or (= p c) (str/starts-with? c (str p java.io.File/separator)))))

;; ── core replay/consistency check ────────────────────────────────────────────

(defn- core-verify!
  "Recompute the bundle from source evidence. `anchors`, when non-nil, is a map of
   independently trusted identities {:bundle-manifest :source-manifest
   :validation-root :authorities :policy-roots :checkpoints}; every one is
   compared and any mismatch fails closed. Returns a qualification map."
  [directory source-directory anchors]
  (let [source-pin (or (:source-manifest anchors) source-manifest-pin)
        manifest-bytes (Files/readAllBytes (.toPath (io/file directory "manifest.json")))
        manifest (json/read-str (String. ^bytes manifest-bytes "UTF-8") :key-fn keyword)]
    (d/ensure! (and (= manifest-schema (:schema manifest))
                    (= source-pin (:sourceManifest manifest))) :bundle-manifest-scope)
    (when anchors
      (d/ensure! (= (:bundle-manifest anchors) (a/file-root manifest-bytes)) :bundle-manifest-pin-mismatch))
    (doseq [[file entry] (:files manifest)]
      (let [path (name file)
            _ (d/ensure! (and (nil? (namespace file)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" path)) :unsafe-bundle-path)
            bytes (Files/readAllBytes (.toPath (io/file directory path)))]
        (d/ensure! (and (= (:sha256 entry) (a/file-root bytes)) (= (:bytes entry) (alength bytes))) :bundle-file-integrity)))
    (let [source (a/load-evidence! source-directory source-pin)
          verifier (a/verifier source)
          policies (read-edn directory "policies.edn") subjects (read-edn directory "subjects.edn")
          histories (read-edn directory "histories.edn")
          checkpoints (read-edn directory "checkpoints.edn")
          verification (read-edn directory "verification.edn")
          artifacts (concat [a/validation-definition] (vals policies) (vals subjects) (mapcat val histories))
          heads (into {} (for [[phase p] policies]
                           [phase (let [state (d/reconstruct-at p verifier subjects (histories phase) (checkpoints phase))]
                                    (d/ensure! (some? (:authoritative state)) :missing-ratified-statement)
                                    (get-in state [:authoritative :subject/root]))]))]
      (when anchors
        (d/ensure! (= (:validation-root anchors) a/validation-root) :validation-root-pin-mismatch)
        (d/ensure! (= (:authorities anchors) (authorities-of policies)) :authority-pin-mismatch)
        (d/ensure! (= (:policy-roots anchors) (policy-roots-of policies)) :policy-trust-pin-mismatch)
        (d/ensure! (= (:checkpoints anchors) checkpoints) :checkpoint-trust-pin-mismatch))
      (d/ensure! (= a/validation-definition (read-edn directory "validation-method.edn")) :validation-method-mismatch)
      (d/ensure! (= verification (into {} (for [[id s] subjects] [id (verifier s)]))) :verification-artifact-mismatch)
      ;; P2: the two purposes share no predecessor link; require that the two
      ;; adopted statements independently agree on operation, source evidence
      ;; and economic policy before a consumer connects them.
      (d/ensure! (a/cross-history-consistent? (get subjects (:allocation heads))
                                              (get subjects (:realized heads)))
                 :cross-history-inconsistent)
      (d/ensure! (= (summary source-pin source policies subjects histories verifier)
                    (json/read-str (slurp (io/file directory "summary.json")) :key-fn keyword)) :summary-mismatch)
      (d/ensure! (= (canonical-preimages artifacts)
                    (json/read-str (slurp (io/file directory "canonical-preimages.json")) :key-fn keyword)) :canonical-preimages-mismatch)
      (let [evm (json/read-str (slurp (io/file directory "evm-input.json")) :key-fn keyword)
            alloc-record (last (:allocation histories))
            realized-record (last (:realized histories))
            sha-of (fn [file] (str "0x" (subs (a/file-root (Files/readAllBytes (.toPath (io/file directory file)))) 7)))]
        (d/ensure! (= alloc-record (read-edn directory "accounting-ratification.edn")) :evm-record-substitution)
        (d/ensure! (= realized-record (read-edn directory "accounting-ratification-realized.edn")) :evm-realized-record-substitution)
        (d/ensure! (= (:accountingRatificationRoot evm) (str "0x" (subs (:artifact/root alloc-record) 7))) :evm-root-mismatch)
        (d/ensure! (= (:realizedAccountingRatificationRoot evm) (str "0x" (subs (:artifact/root realized-record) 7))) :evm-realized-root-mismatch)
        (d/ensure! (= (:recordFileSha256 evm) (sha-of "accounting-ratification.edn")) :evm-record-integrity)
        (d/ensure! (= (:realizedRecordFileSha256 evm) (sha-of "accounting-ratification-realized.edn")) :evm-realized-record-integrity))
      {:schema qualification-schema
       :bundle/manifest (a/file-root manifest-bytes)
       :source/manifest source-pin
       :validation-root a/validation-root
       :policy-roots (policy-roots-of policies)
       :checkpoints checkpoints
       :authoritative heads
       :summary (summary source-pin source policies subjects histories verifier)})))

(defn verify-local!
  "Recompute the bundle against the pinned source evidence. Reports LOCAL
   reproducibility only; does not consult or confer independent trust."
  [directory source-directory]
  (let [result (core-verify! directory source-directory nil)]
    (println "QUALIFICATION: LOCAL (reproducible; NOT independently anchored)")
    (println "  bundle manifest :" (:bundle/manifest result))
    (println "  source manifest :" (:source/manifest result))
    (println "  allocation head :" (get-in result [:authoritative :allocation]))
    (println "  realized head   :" (get-in result [:authoritative :realized]))
    (assoc result :qualification :local)))

(defn verify-anchored!
  "Require an externally supplied trusted-pins file and check every anchored
   identity. A pins file inside the bundle directory is refused: the bundle's own
   generated pins must never automatically confer independent trust."
  [directory source-directory pins-file]
  (d/ensure! (and (string? pins-file) (.isFile (io/file pins-file))) :missing-trusted-pins)
  (d/ensure! (not (inside? directory pins-file)) :bundle-own-pins-not-independent)
  (let [pins (edn/read-string (slurp (io/file pins-file)))]
    (d/ensure! (= pins-schema (:schema pins)) :unsupported-pins-schema)
    (d/ensure! (= (:pins/root pins) (d/root pins-domain (dissoc pins :pins/root))) :pins-self-inconsistent)
    (let [result (core-verify! directory source-directory pins)]
      (println "QUALIFICATION: ANCHORED (independently supplied pins)")
      (println "  pins root       :" (:pins/root pins))
      (println "  bundle manifest :" (:bundle/manifest result))
      (println "  source manifest :" (:source/manifest result))
      (println "  policy roots    :" (:policy-roots result))
      (println "  allocation head :" (get-in result [:authoritative :allocation]))
      (println "  realized head   :" (get-in result [:authoritative :realized]))
      (assoc result :qualification :anchored :pins/root (:pins/root pins)))))

;; ── generation ───────────────────────────────────────────────────────────────

(defn- write-bundle-manifest!
  "Write manifest.json over every top-level file except manifest.json itself and
   the bundle-local `trust-pins.edn` (whose identity is derived from the manifest,
   so it cannot be listed inside it)."
  [directory]
  (let [files (into (sorted-map)
                    (for [file (.listFiles (io/file directory))
                          :when (and (.isFile file)
                                     (not (#{"manifest.json" "trust-pins.edn" "trust-pins.sha256"} (.getName file))))]
                      (let [bytes (Files/readAllBytes (.toPath file))]
                        [(.getName file) {:sha256 (a/file-root bytes) :bytes (alength bytes)}])))]
    (spit (io/file directory "manifest.json")
          (json/write-str {:schema manifest-schema :sourceManifest source-manifest-pin
                           :scope "Local fixture authority and accounting only; no capital authorization"
                           :files files} :indent true))))

(defn generate! [directory source-directory]
  (d/ensure! (not (.exists (io/file directory))) :output-already-exists)
  (.mkdirs (io/file directory))
  (let [source (a/load-evidence! source-directory source-manifest-pin) verifier (a/verifier source)
        keys {:reviewer (keypair) :ratifier (keypair)}
        policies (into {} (for [phase [:allocation :realized]]
                            [phase (d/policy {:domain a/domain :purpose (a/purposes phase) :scope a/scope :subject-schema a/schema
                                             :validation-root a/validation-root
                                             :reviewer {:id "local-accounting-reviewer" :public-key (pem (:reviewer keys))}
                                             :ratifier {:id "local-accounting-ratifier" :public-key (pem (:ratifier keys))}})]))
        original (a/statement source :allocation) invalid (a/statement source :allocation true nil)
        realized (a/statement source :realized)
        subjects (into {} (map (juxt :artifact/root identity) [original invalid realized]))
        histories {:allocation (build-log (:allocation policies) verifier subjects
                                          [[original :accept] [invalid :reject] [original :ratify]] keys)
                   :realized (build-log (:realized policies) verifier subjects [[realized :accept] [realized :ratify]] keys)}
        checkpoints (into {} (for [[phase p] policies]
                               [phase (select-keys (d/reconstruct p verifier subjects (histories phase)) [:history/root :sequence])]))
        verification (into {} (for [[id s] subjects] [id (verifier s)]))
        artifacts (concat [a/validation-definition] (vals policies) (vals subjects) (mapcat val histories))]
    (doseq [[file data] {"policies.edn" policies "subjects.edn" subjects "histories.edn" histories
                        "checkpoints.edn" checkpoints "verification.edn" verification}]
      (write-edn directory file data))
    (spit (io/file directory "summary.json") (json/write-str (summary source-manifest-pin source policies subjects histories verifier) :indent true))
    (spit (io/file directory "canonical-preimages.json") (json/write-str (canonical-preimages artifacts) :indent true))
    (write-edn directory "validation-method.edn" a/validation-definition)
    ;; Two actual authenticated records (allocation and realized settlement) can
    ;; be supplied to the real EVM proof gate as adversarial inputs. Neither is a
    ;; capital authorization.
    (write-edn directory "accounting-ratification.edn" (last (:allocation histories)))
    (write-edn directory "accounting-ratification-realized.edn" (last (:realized histories)))
    (let [sha-of (fn [file] (str "0x" (subs (a/file-root (Files/readAllBytes (.toPath (io/file directory file)))) 7)))]
      (spit (io/file directory "evm-input.json")
            (json/write-str {:accountingRatificationRoot (str "0x" (subs (:artifact/root (last (:allocation histories))) 7))
                             :recordFileSha256 (sha-of "accounting-ratification.edn")
                             :realizedAccountingRatificationRoot (str "0x" (subs (:artifact/root (last (:realized histories))) 7))
                             :realizedRecordFileSha256 (sha-of "accounting-ratification-realized.edn")}
                            :indent true)))
    (doseq [[output input] {"subject-decisions.clj" "src/resolver_sim/assurance/subject_decisions.clj"
                           "partial-fill-review.clj" "src/resolver_sim/accounting/partial_fill_review.clj"
                           "run.clj" "examples/partial-fill/accounting-review/run.clj"
                           "README.md" "examples/partial-fill/accounting-review/README.md"
                           "ui-description.md" "examples/partial-fill/accounting-review/ui-description.md"
                           "subject-decisions-test.clj" "test/resolver_sim/assurance/subject_decisions_test.clj"
                           "partial-fill-review-test.clj" "test/resolver_sim/accounting/partial_fill_review_test.clj"
                           "AccountingReviewBoundary.t.sol" "examples/partial-fill/accounting-review/AccountingReviewBoundary.t.sol"
                           "architecture.md" "docs/architecture/subject-decisions-and-accounting.md"}]
      (io/copy (io/file input) (io/file directory output)))
    (write-bundle-manifest! directory)
    (let [bundle-manifest (a/file-root (Files/readAllBytes (.toPath (io/file directory "manifest.json"))))
          pins (build-pins policies checkpoints bundle-manifest source-manifest-pin)
          pins-bytes (str (pr-str pins) "\n")]
      (write-edn directory "trust-pins.edn" pins)
      (spit (io/file directory "trust-pins.sha256") (a/file-root (.getBytes pins-bytes "UTF-8")))
      (d/ensure! (= (:pins/root pins) (d/root pins-domain (dissoc pins :pins/root))) :pins-self-inconsistent))
    ;; Self-verification is LOCAL only; it must not be presented as anchoring.
    (verify-local! directory source-directory)))

(defn refresh-manifest!
  "Recompute `manifest.json` and the bundle-local `trust-pins.edn`/`.sha256` from
   the current bundle contents, without changing any signed record. Used after an
   auxiliary evidence file (e.g. a captured EVM boundary log) is added, so the
   retained bundle stays internally consistent. The decided identities are
   unchanged; only the bundle-manifest identity and pins root are recomputed."
  [directory source-directory]
  (write-bundle-manifest! directory)
  (let [policies (read-edn directory "policies.edn")
        checkpoints (read-edn directory "checkpoints.edn")
        bundle-manifest (a/file-root (Files/readAllBytes (.toPath (io/file directory "manifest.json"))))
        pins (build-pins policies checkpoints bundle-manifest source-manifest-pin)
        pins-bytes (str (pr-str pins) "\n")]
    (write-edn directory "trust-pins.edn" pins)
    (spit (io/file directory "trust-pins.sha256") (a/file-root (.getBytes pins-bytes "UTF-8")))
    (d/ensure! (= (:pins/root pins) (d/root pins-domain (dissoc pins :pins/root))) :pins-self-inconsistent))
  (verify-local! directory source-directory))

(let [[command directory source-directory third] *command-line-args*
      source-directory (or source-directory default-source)]
  (case command
    "generate" (generate! directory source-directory)
    "refresh-manifest" (refresh-manifest! directory source-directory)
    "verify-local" (verify-local! directory source-directory)
    ;; `verify`/`verify-anchored` require an externally supplied pins file. There
    ;; is deliberately no default: the bundle's own pins do not confer trust.
    ("verify" "verify-anchored")
    (do (d/ensure! (some? third) :trusted-pins-required)
        (verify-anchored! directory source-directory third))
    (throw (ex-info "Usage: clojure -M examples/partial-fill/accounting-review/run.clj generate|verify-local|verify-anchored OUTPUT [V3_SOURCE] [TRUSTED_PINS]" {}))))
