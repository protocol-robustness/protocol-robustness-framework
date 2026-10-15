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
