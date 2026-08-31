(ns resolver-sim.benchmark.research-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.research-observation :as observation]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.held-custody-test-env :as env])
  (:import [java.security KeyPairGenerator]))

(defn- public-key-hex [public-key]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (take-last 32 (.getEncoded public-key)))))

(defn- signer [researcher-id key-id]
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:researcher/id researcher-id
     :signing-key/id key-id
     :signing-key/algorithm :ed25519
     :signing-key/public-key (public-key-hex (.getPublic pair))
     :private-key (.getPrivate pair)}))

(defn- signer-key-set [signers]
  {:artifact/schema authority-state/signer-key-set-schema
   :signer-key-set/entries
   (mapv #(select-keys % [:researcher/id :signing-key/id
                          :signing-key/algorithm :signing-key/public-key]) signers)})

(defn- force-authorised-artifact []
  (let [{:keys [world context workflow-id]} (env/public-disputed-world)
        grant (sew/apply-action context world
                                {:seq 2 :time 1000 :agent "gov"
                                 :action "grant-force-authorisation"
                                 :params {:workflow-id workflow-id
                                          :reason :resolver-overcapacity}})
        authorization-id (get-in grant [:extra :authorization/id])
        execution (sew/apply-action context (:world grant)
                                    {:seq 3 :time 1000 :agent "executor"
                                     :action "execute-force-authorised-action"
                                     :params {:workflow-id workflow-id
                                              :authorization-id authorization-id
                                              :is-release true}})
        adjustment (last (get-in execution [:world :held-adjustments]))]
    {:grant grant
     :execution execution
     :artifact (get-in execution [:world :held-artifacts (:held-adjustment/id adjustment)])
     :adjustment adjustment
     :world (:world grant)
     :context context
     :workflow-id workflow-id
     :authorization-id authorization-id}))

(defn- signed-observation [signer root position]
  (observation/sign-observation
   (observation/observation-statement
    {:researcher/id (:researcher/id signer)
     :signing-key/id (:signing-key/id signer)
     :observation/subject-root root
     :position/value position})
   (:private-key signer)))

(deftest two-governed-researchers-attest-to-the-exact-force-authorised-artifact
  (let [{:keys [grant execution artifact]} (force-authorised-artifact)
        r1 (signer "17" "k1")
        r2 (signer "22" "k2")
        key-set (signer-key-set [r1 r2])
        h (:artifact/hash artifact)
        r1-observation (signed-observation r1 h 1)
        r2-observation (signed-observation r2 h 1)
        result (observation/aggregate-observations key-set [r1-observation r2-observation])]
    (testing "the real persisted authorization produces the observed canonical artifact"
      (is (:ok grant))
      (is (:ok execution))
      (is (= artifact (custody/build-held-custody-artifact
                       (last (get-in execution [:world :held-adjustments]))))))
    (testing "both canonical statements resolve through governed key material"
      (is (:valid? (observation/verify-observation key-set r1-observation)))
      (is (:valid? (observation/verify-observation key-set r2-observation))))
    (is (= :agreement (:status result)))
    (is (= h (:subject-root result)))
    (is (= [1 1] (:positions result)))))

(deftest observation-aggregation-distinguishes-disagreement-from-different-subjects
  (let [{:keys [artifact adjustment]} (force-authorised-artifact)
        r1 (signer "17" "k1")
        r2 (signer "22" "k2")
        key-set (signer-key-set [r1 r2])
        h (:artifact/hash artifact)
        h2 (:artifact/hash (custody/build-held-custody-artifact
                            (assoc adjustment :held-adjustment/id "separate-observed-adjustment")))
        disagreement (observation/aggregate-observations
                      key-set [(signed-observation r1 h 1)
                               (signed-observation r2 h -1)])
        different-subjects (observation/aggregate-observations
                            key-set [(signed-observation r1 h 1)
                                     (signed-observation r2 h2 1)])]
    (is (= :disagreement (:status disagreement)))
    (is (= :not-comparable (:status different-subjects)))
    (is (= :different-subject-roots (:reason different-subjects)))))

(deftest forged-researcher-identity-with-another-governed-key-is-rejected
  (let [{:keys [artifact]} (force-authorised-artifact)
        r1 (signer "17" "k1")
        r2 (signer "22" "k2")
        key-set (signer-key-set [r1 r2])
        forged (observation/sign-observation
                (observation/observation-statement
                 {:researcher/id "17"
                  :signing-key/id "k1"
                  :observation/subject-root (:artifact/hash artifact)
                  :position/value 1})
                (:private-key r2))]
    (is (= :signature-invalid
           (:reason (observation/verify-observation key-set forged))))))

(deftest force-authorisation-scope-is-derived-from-the-executed-adjustment
  (let [{:keys [world context workflow-id authorization-id]} (force-authorised-artifact)
        substituted (sew/apply-action context world
                                      {:seq 3 :time 1000 :agent "executor"
                                       :action "execute-force-authorised-action"
                                       :params {:workflow-id workflow-id
                                                :authorization-id authorization-id
                                                :is-release false}})]
    (is (= :force-authorisation-grant-scope-mismatch (:error substituted)))
    (is (nil? (:world substituted)))))
