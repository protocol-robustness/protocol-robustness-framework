(ns resolver-sim.assurance.subject-decisions-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.subject-decisions :as d]
            [resolver-sim.resubmission.admission-authorization :as admission])
  (:import [java.security KeyPairGenerator]
           [java.util Base64]))

(defn keypair [] (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(defn public-pem [pair]
  (str "-----BEGIN PUBLIC KEY-----\n"
       (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic pair)))
       "\n-----END PUBLIC KEY-----\n"))

(defn setup []
  (let [reviewer (keypair) ratifier (keypair)
        p (d/policy {:domain :example/calculation :purpose :example/reporting
                     :scope "local test only" :subject-schema "example-statement.v1"
                     :validation-root (d/root "example-verifier.v1" {})
                     :reviewer {:id "reviewer" :public-key (public-pem reviewer)}
                     :ratifier {:id "ratifier" :public-key (public-pem ratifier)}})
        subject (d/rooted "example-statement.v1"
                          {:domain (:domain p) :purpose (:purpose p) :amount 70
                           :predecessor/root nil})
        verifier (with-meta (fn [s] {:verified? (= 70 (:amount s)) :eligible? (= 70 (:amount s))
                                     :scope "local test only"
                                     :evidence/root (d/root "example-evidence.v1" {:amount 70})})
                   {:validation/root (:validation-root p)})]
    {:p p :subject subject :subjects {(:artifact/root subject) subject}
     :verifier verifier :reviewer reviewer :ratifier ratifier}))

(defn request [{:keys [p verifier subjects subject reviewer ratifier]} history action]
  (d/sign-decision (d/decision p (d/reconstruct p verifier subjects history) verifier subject action)
                   (.getPrivate (if (= :ratify action) ratifier reviewer))))

(defn resign [event pair]
  (d/sign-decision (assoc (dissoc event :authorization) :artifact/root (d/artifact-root event))
                   (.getPrivate pair)))

(deftest three-independent-states
  (let [{:keys [p verifier subjects subject] :as f} (setup)
        initial (d/reconstruct p verifier subjects [])
        accepted [(request f [] :accept)]
        accepted-state (d/reconstruct p verifier subjects accepted)
        ratified (conj accepted (request f accepted :ratify))]
    (is (get-in (d/verify-subject p verifier subject) [:report :verified?]))
    (is (empty? (:decisions initial)))
    (is (nil? (:authoritative initial)))
    (is (= :accept (get-in accepted-state [:decisions (:artifact/root subject) :decision])))
    (is (nil? (:authoritative accepted-state)))
    (is (= (:artifact/root subject)
           (get-in (d/reconstruct p verifier subjects ratified) [:authoritative :subject/root])))
    (is (= (d/reconstruct p verifier subjects ratified)
           (d/reconstruct p verifier (into {} (reverse (seq subjects))) (vec ratified))))))

(deftest acceptance-is-required
  (let [{:keys [p verifier subjects] :as f} (setup)
        reject [(request f [] :reject)]]
    (is (thrown? Exception (d/reconstruct p verifier subjects [(request f [] :ratify)])))
    (is (thrown? Exception (d/reconstruct p verifier subjects (conj reject (request f reject :ratify)))))))

(deftest policy-binds-the-domain-verification-method
  (let [{:keys [p verifier subject]} (setup)]
    (is (thrown? Exception (d/verify-subject p
                                             (with-meta verifier {:validation/root (d/root "different-method" {})}) subject)))))

(deftest actual-authorization-required-for-each-role
  (let [{:keys [p verifier subjects reviewer ratifier] :as f} (setup)
        accept (request f [] :accept)
        history [accept]
        ratify (request f history :ratify)]
    (doseq [event [(dissoc accept :authorization)
                   (assoc-in accept [:authorization :signature :value] (apply str (repeat 128 "0")))
                   (d/sign-decision (dissoc accept :authorization) (.getPrivate ratifier))]]
      (is (thrown? Exception (d/reconstruct p verifier subjects [event]))))
    (is (thrown? Exception
                 (d/reconstruct p verifier subjects
                                [accept (d/sign-decision (dissoc ratify :authorization) (.getPrivate reviewer))])))))

(deftest all-semantic-bindings-are-checked-even-with-valid-signature
  (let [{:keys [p verifier subjects reviewer] :as f} (setup)
        accept (request f [] :accept)]
    (doseq [[field value] [[:subject/root (d/root "other" {})]
                           [:domain :other/domain] [:purpose :other/purpose]
                           [:policy/root (d/root "other-policy" {})]
                           [:verification/root (d/root "other-verification" {})]
                           [:authority/id "other-reviewer"]]]
      (testing (str field)
        (is (thrown? Exception (d/reconstruct p verifier subjects [(resign (assoc accept field value) reviewer)])))))))

(deftest tampering-and-replay-reject
  (let [{:keys [p verifier subjects subject] :as f} (setup)
        accept (request f [] :accept)
        history [accept (request f [accept] :ratify)]]
    (is (thrown? Exception (d/reconstruct p verifier subjects [accept accept])))
    (is (thrown? Exception (d/reconstruct p verifier subjects (conj history (last history)))))
    (is (thrown? Exception (d/reconstruct p verifier subjects (conj history (request f history :ratify)))))
    (is (thrown? Exception (d/reconstruct p verifier subjects [accept (request f [accept] :accept)])))
    (is (thrown? Exception (d/reconstruct p verifier subjects (vec (reverse history)))))
    (is (thrown? Exception (d/reconstruct p verifier
                                          {(:artifact/root subject) (assoc subject :amount 80)} [accept])))
    (is (thrown? Exception (d/reconstruct p (with-meta (fn [_] {:verified? true :eligible? true
                                                                :scope (:scope p)
                                                                :evidence/root (d/root "altered-evidence" {})})
                                              {:validation/root (:validation-root p)})
                                          subjects [accept])))))

(deftest ineligible-statement-can-be-rejected-but-never-accepted
  (let [{:keys [p subject] :as f} (setup)
        invalid (d/rooted "example-statement.v1" (assoc (dissoc subject :artifact/root) :amount 80))
        f (assoc f :subject invalid :subjects {(:artifact/root invalid) invalid})
        {:keys [verifier subjects]} f]
    (is (thrown? Exception (d/reconstruct p verifier subjects [(request f [] :accept)])))
    (is (= :reject (get-in (d/reconstruct p verifier subjects [(request f [] :reject)])
                           [:decisions (:artifact/root invalid) :decision])))))

(deftest successor-adoption-and-conflicts
  (let [{:keys [p subject verifier reviewer ratifier] :as f} (setup)
        successor (d/rooted "example-statement.v1"
                            (assoc (dissoc subject :artifact/root) :predecessor/root (:artifact/root subject)))
        subjects (assoc (:subjects f) (:artifact/root successor) successor)
        f (assoc f :subjects subjects)
        a (request f [] :accept) h [a (request f [a] :ratify)]
        f2 (assoc f :subject successor)
        accepted (conj h (request f2 h :accept))
        event (request f2 accepted :ratify)
        state (d/reconstruct p verifier subjects (conj accepted event))]
    (is (= (:artifact/root successor) (get-in state [:authoritative :subject/root])))
    (is (thrown? Exception (d/reconstruct p verifier subjects
                                          (conj accepted (resign (assoc event :supersedes/root nil) ratifier)))))
    (is (thrown? Exception (d/reconstruct p verifier subjects
                                          (conj accepted (resign (assoc event :acceptance/root (:artifact/root a)) ratifier)))))
    (is (thrown? Exception (d/reconstruct p verifier subjects
                                          [(request f2 [] :accept) (request f2 [(request f2 [] :accept)] :ratify)])))
    (is (thrown? Exception (d/reconstruct p verifier subjects
                                          (conj accepted (resign (assoc event :decision :execute) reviewer)))))))

(deftest accounting-record-is-not-an-execution-authorization
  (let [f (setup) a (request f [] :accept) ratify (request f [a] :ratify)]
    ;; Existing fenced admission verifier requires its own schema, purpose,
    ;; exact reservation payload and signature. Accounting records cannot enter it.
    (is (false? (admission/verify {} ratify (apply str (repeat 64 "0")))))
    (is (= :content-adoption-only (:effects (:p f))))))

(deftest pinned-checkpoint-rejects-truncated-history
  (let [{:keys [p verifier subjects] :as f} (setup)
        a (request f [] :accept) history [a (request f [a] :ratify)]
        state (d/reconstruct p verifier subjects history)
        checkpoint (select-keys state [:history/root :sequence])]
    (is (= state (d/reconstruct-at p verifier subjects history checkpoint)))
    (is (thrown? Exception (d/reconstruct-at p verifier subjects [a] checkpoint)))))

(deftest concurrent-successors-do-not-both-become-authoritative
  (let [{:keys [p subject verifier] :as f} (setup)
        successor #(d/rooted "example-statement.v1"
                             (assoc (dissoc subject :artifact/root) :predecessor/root (:artifact/root subject) :revision %))
        left (successor 1) right (successor 2)
        subjects (into (:subjects f) (map (juxt :artifact/root identity) [left right]))
        f (assoc f :subjects subjects)
        a (request f [] :accept) h [a (request f [a] :ratify)]
        f-left (assoc f :subject left) f-right (assoc f :subject right)
        h (conj h (request f-left h :accept))
        h (conj h (request f-right h :accept))
        h (conj h (request f-left h :ratify))
        before (d/reconstruct p verifier subjects h)]
    (is (= (:artifact/root left) (get-in before [:authoritative :subject/root])))
    (is (thrown? Exception (d/append p verifier subjects h (request f-right h :ratify))))
    (is (= before (d/reconstruct p verifier subjects h)))))

;; ── V2 subject-identity and verification binding (P0) ────────────────────────
;;
;; Under V1, `advance` resolved a subject by map key and never checked that the
;; resolved subject's recomputed canonical identity equaled the event's declared
;; `:subject/root`. The reviewer demonstrated an exploit in which a signed
;; decision named decoy statement Z while the `subjects` map resolved that root
;; to a different statement Y. These tests reproduce that exploit against the
;; fixed V2 kernel and assert it now fails closed, plus the surrounding binding
;; invariants.

(defn- exploit-decoy-accept
  "Build a *validly signed* accept whose declared `:subject/root` is the canonical
   root of decoy statement Z, while `subjects` maps that root to the real,
   verifiable statement Y. Returns [spoofed-subjects signed-accept decoy]."
  [{:keys [p verifier subject reviewer]} decoy]
  (let [req (d/decision p (d/initial-state p) verifier subject :accept)
        req (assoc req :subject/root (:artifact/root decoy))
        req (assoc req :artifact/root (d/artifact-root req))
        accept (d/sign-decision req (.getPrivate reviewer))]
    [{(:artifact/root decoy) subject} accept decoy]))

(deftest v2-rejects-decoy-z-while-verifying-statement-y
  (let [{:keys [p verifier subject] :as f} (setup)
        ;; Z is a self-consistent decoy with different content (amount 999).
        z (d/rooted "example-statement.v1" (assoc (dissoc subject :artifact/root) :amount 999))
        [spoofed accept _] (exploit-decoy-accept f z)]
    (is (not= (:artifact/root z) (:artifact/root subject)))
    (is (= (:artifact/root z) (:subject/root accept)))          ; declared root names Z
    (is (= true (:verified? (verifier subject))))               ; verification actually ran on Y
    (let [e (try (d/reconstruct p verifier spoofed [accept]) nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "decoy substitution must be rejected")
      (is (= :subject-identity-mismatch (:reason (ex-data e)))))))

(deftest v2-rejects-declared-root-that-does-not-name-resolved-subject
  (let [{:keys [p verifier subjects subject reviewer]} (setup)
        req (d/decision p (d/initial-state p) verifier subject :accept)
        ;; Valid signature over a forged declared root, but no map entry with the
        ;; canonical identity behind it. Even a perfectly self-consistent signed
        ;; record is not admissible.
        forged (assoc req :subject/root (d/root "other" {}))
        forged (assoc forged :artifact/root (d/artifact-root forged))
        signed (d/sign-decision forged (.getPrivate reviewer))]
    (is (thrown? Exception (d/reconstruct p verifier subjects [signed])))))

(deftest v2-rejects-subject-object-with-forged-canonical-root
  (let [{:keys [p verifier subject]} (setup)
        forged (assoc subject :artifact/root (d/root "forged" {}))
        ;; The verifier must recompute the subject's canonical identity, not trust
        ;; the declared field inside the subject object.
        ]
    (is (thrown? Exception (d/verify-subject p verifier forged)))))

(deftest v2-verification-root-binds-subject-identity-and-policy
  (let [{:keys [p verifier subject]} (setup)
        ;; twin has different content but an identical adapter report.
        twin (d/rooted "example-statement.v1" (assoc (dissoc subject :artifact/root) :note :extra))
        report (verifier subject)
        p2 (d/policy {:domain (:domain p) :purpose (:purpose p) :scope (:scope p)
                      :subject-schema (:subject-schema p) :validation-root (:validation-root p)
                      :reviewer {:id "alternate-reviewer" :public-key (get-in p [:reviewer :public-key])}
                      :ratifier (:ratifier p)})]
    (is (= report (verifier twin)))
    (is (not= (:artifact/root subject) (:artifact/root twin)))
    (is (not= (d/verification-root p subject report)
              (d/verification-root p twin report))
        "same report, different subject identity => different verification root")
    (is (not= (d/verification-root p subject report)
              (d/verification-root p2 subject report))
        "same subject, different policy => different verification root")))

(deftest v2-verification-root-changes-when-report-is-mutated
  (let [{:keys [p verifier subject reviewer]} (setup)
        report (verifier subject)
        tampered (assoc report :eligible? false)
        good (d/sign-decision (d/decision p (d/initial-state p) verifier subject :accept)
                              (.getPrivate reviewer))]
    (is (not= (d/verification-root p subject report)
              (d/verification-root p subject tampered)))
    ;; A decision signed against a report the current verifier no longer produces
    ;; must be rejected as a verification-binding mismatch.
    (is (thrown? Exception
                 (d/reconstruct p (with-meta (fn [_] tampered)
                                    {:validation/root (:validation-root p)})
                                {(:artifact/root subject) subject} [good])))))

(deftest v2-resolved-map-key-does-not-confer-identity
  ;; The `subjects` map is an index, not an authority: changing which key points
  ;; at a subject cannot change what a signed root names.
  (let [{:keys [p verifier subject reviewer]} (setup)
        accept (d/sign-decision (d/decision p (d/initial-state p) verifier subject :accept)
                                (.getPrivate reviewer))
        bogus {(:subject/root accept) (assoc subject :amount 999 :artifact/root (:artifact/root subject))}]
    (is (thrown? Exception (d/reconstruct p verifier bogus [accept])))))
