(ns partial-fill-accounting-review
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.assurance.subject-decisions :as d]
            [resolver-sim.accounting.partial-fill-review :as a]
            [resolver-sim.hash.canonical :as canonical])
  (:import [java.security KeyPairGenerator]
           [java.util Base64]
           [java.nio.file Files]))

(def manifest-pin "sha256:48afd9aa8553076ba24b017344d07dc3cb7fa2756a5e591caf4f2881feb05798")
(def default-source "examples/partial-fill/evidence/v3-live-context")
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

(defn subject-view [s report]
  {:root (:artifact/root s) :phase (name (:phase s)) :synthetic (:synthetic? s)
   :sourceRoot (:source/root s) :economicPolicyRoot (:economic-policy/root s)
   :evidenceRoot (:evidence/root s) :allocations (mapv :allocated (:rows s))
   :unmet (reduce + (map :unmet (:rows s))) :restricted (get-in s [:capital :restricted])
   :verification {:passed (:verified? report) :eligible (:eligible? report)
                  :root (d/root "subject-verification.v1" report)
                  :conservation (get-in report [:checks :conservation])
                  :restriction (get-in report [:checks :restricted-capital])
                  :recipientPolicy (get-in report [:checks :recipient-policy])}})

(defn policy-view [p initial]
  {:root (:artifact/root p) :domain (str (:domain p)) :purpose (str (:purpose p))
   :validationRoot (:validation-root p)
   :reviewer (:reviewer p) :ratifier (:ratifier p) :initialHistoryRoot (:history/root initial)})

(defn summary [source policies subjects histories verifier]
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
     :sourceManifest manifest-pin :sourceEvidenceRoot (:evidence/root (a/evidence-material source))
     :limitations (mapv name a/limitations)
     :candidates {:A (subject-view original (verifier original)) :B (subject-view invalid (verifier invalid))
                  :realized (subject-view settled (verifier settled))}
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

(defn verify! [directory source-directory pins]
  (let [source (a/load-evidence! source-directory manifest-pin)
        verifier (a/verifier source)
        policies (read-edn directory "policies.edn") subjects (read-edn directory "subjects.edn")
        histories (read-edn directory "histories.edn")
        checkpoints (read-edn directory "checkpoints.edn")
        verification (read-edn directory "verification.edn")
        artifacts (concat [a/validation-definition] (vals policies) (vals subjects) (mapcat val histories))
        manifest (json/read-str (slurp (io/file directory "manifest.json")) :key-fn keyword)]
    (d/ensure! (and (= "eae.accounting-review-manifest.v1" (:schema manifest))
                    (= manifest-pin (:sourceManifest manifest))) :bundle-manifest-scope)
    (d/ensure! (= (:policy-roots pins) (into {} (for [[phase p] policies] [phase (:artifact/root p)]))) :policy-trust-pin-mismatch)
    (d/ensure! (= (:checkpoints pins) checkpoints) :checkpoint-trust-pin-mismatch)
    (d/ensure! (= a/validation-definition (read-edn directory "validation-method.edn")) :validation-method-mismatch)
    (doseq [[file entry] (:files manifest)]
      (let [path (name file)
            _ (d/ensure! (and (nil? (namespace file)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" path)) :unsafe-bundle-path)
            bytes (Files/readAllBytes (.toPath (io/file directory path)))]
        (d/ensure! (and (= (:sha256 entry) (a/file-root bytes)) (= (:bytes entry) (alength bytes))) :bundle-file-integrity)))
    (d/ensure! (= verification (into {} (for [[id s] subjects] [id (verifier s)]))) :verification-artifact-mismatch)
    (doseq [[phase p] policies]
      (let [state (d/reconstruct-at p verifier subjects (histories phase) (checkpoints phase))]
        (d/ensure! (some? (:authoritative state)) :missing-ratified-statement)))
    (d/ensure! (= (summary source policies subjects histories verifier)
                 (json/read-str (slurp (io/file directory "summary.json")) :key-fn keyword)) :summary-mismatch)
    (d/ensure! (= (canonical-preimages artifacts)
                 (json/read-str (slurp (io/file directory "canonical-preimages.json")) :key-fn keyword)) :canonical-preimages-mismatch)
    (let [evm (json/read-str (slurp (io/file directory "evm-input.json")) :key-fn keyword)
          record (last (:allocation histories))]
      (d/ensure! (= record (read-edn directory "accounting-ratification.edn")) :evm-record-substitution)
      (d/ensure! (= (:accountingRatificationRoot evm) (str "0x" (subs (:artifact/root record) 7))) :evm-root-mismatch)
      (d/ensure! (= (:recordFileSha256 evm)
                   (str "0x" (subs (a/file-root (Files/readAllBytes (.toPath (io/file directory "accounting-ratification.edn")))) 7)))
                 :evm-record-integrity))
    (println "PASS: independently replayed signed allocation review, invalid-candidate rejection and realized accounting adoption; local scope only.")))

(defn generate! [directory source-directory]
  (d/ensure! (not (.exists (io/file directory))) :output-already-exists)
  (.mkdirs (io/file directory))
  (let [source (a/load-evidence! source-directory manifest-pin) verifier (a/verifier source)
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
        artifacts (concat [a/validation-definition] (vals policies) (vals subjects) (mapcat val histories))
        pins {:policy-roots (into {} (for [[phase p] policies] [phase (:artifact/root p)])) :checkpoints checkpoints}]
    (doseq [[file data] {"policies.edn" policies "subjects.edn" subjects "histories.edn" histories
                        "checkpoints.edn" checkpoints "verification.edn" verification}]
      (write-edn directory file data))
    (spit (io/file directory "summary.json") (json/write-str (summary source policies subjects histories verifier) :indent true))
    (spit (io/file directory "canonical-preimages.json") (json/write-str (canonical-preimages artifacts) :indent true))
    (write-edn directory "trust-pins.edn" pins)
    (write-edn directory "validation-method.edn" a/validation-definition)
    ;; One actual authenticated record can be supplied to the real EVM proof
    ;; gate as an adversarial input. It is NOT a capital authorization.
    (write-edn directory "accounting-ratification.edn" (last (:allocation histories)))
    (spit (io/file directory "evm-input.json")
          (json/write-str {:accountingRatificationRoot (str "0x" (subs (:artifact/root (last (:allocation histories))) 7))
                           :recordFileSha256 (str "0x" (subs (a/file-root (Files/readAllBytes (.toPath (io/file directory "accounting-ratification.edn")))) 7))}
                          :indent true))
    (doseq [[output input] {"subject-decisions.clj" "src/resolver_sim/assurance/subject_decisions.clj"
                           "partial-fill-review.clj" "src/resolver_sim/accounting/partial_fill_review.clj"
                           "run.clj" "examples/partial-fill/accounting-review/run.clj"
                           "README.md" "examples/partial-fill/accounting-review/README.md"
                           "ui-description.md" "examples/partial-fill/accounting-review/ui-description.md"
                           "subject-decisions-test.clj" "test/resolver_sim/assurance/subject_decisions_test.clj"
                           "partial-fill-review-test.clj" "test/resolver_sim/accounting/partial_fill_review_test.clj"
                           "architecture.md" "docs/architecture/subject-decisions-and-accounting.md"}]
      (io/copy (io/file input) (io/file directory output)))
    (let [files (into (sorted-map) (for [file (.listFiles (io/file directory))
                                       :when (.isFile file)
                                       :let [bytes (Files/readAllBytes (.toPath file))]]
                                   [(.getName file) {:sha256 (a/file-root bytes) :bytes (alength bytes)}]))]
      (spit (io/file directory "manifest.json")
            (json/write-str {:schema "eae.accounting-review-manifest.v1" :sourceManifest manifest-pin
                             :scope "Local fixture authority and accounting only; no capital authorization"
                             :files files} :indent true)))
    (verify! directory source-directory pins)))

(let [[command directory source-directory pins-file] *command-line-args*
      source-directory (or source-directory default-source)]
  (case command
    "generate" (generate! directory source-directory)
    "verify" (verify! directory source-directory
                      (edn/read-string (slurp (or pins-file "examples/partial-fill/accounting-review/trust-pins.edn"))))
    (throw (ex-info "Usage: clojure -M examples/partial-fill/accounting-review/run.clj generate|verify OUTPUT [V3_SOURCE]" {}))))
