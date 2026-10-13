(ns resolver-sim.research.shortfall-publication-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.shortfall-publication :as publication]
            [resolver-sim.research.shortfall-pilot :as pilot]
            [resolver-sim.network.shortfall-pilot-adapter :as pilot-adapter]
            [resolver-sim.research.shortfall-pilot-test :as fixture]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges-test :as keys]
            [resolver-sim.hash.reference :as reference])
  (:import [java.nio.file Files]
           [java.util Base64]))

(defn- setup []
  (let [f (#'fixture/fixture)
        dir (str (Files/createTempDirectory "shortfall-publication-" (make-array java.nio.file.attribute.FileAttribute 0)))
        key (#'keys/keys!)]
    (doseq [[name value] [["configuration.edn" (:config f)] ["pilot.edn" (:profile f)]
                          ["history.edn" []] ["evidence.edn" (:evidence f)]]]
      (spit (str dir "/" name) (pr-str value)))
    {:f f :dir dir :key key :revision pilot/method-root}))

(defn- setup-controlled []
  (let [f (#'fixture/fixture)
        dir (str (Files/createTempDirectory "shortfall-controlled-publication-"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        key (#'keys/keys!) policy (challenges/build-control-policy (:config f) [])
        controlled-profile (pilot-adapter/build-controlled-profile (:config f) (:profile f) policy [])]
    (doseq [[name value] [["configuration.edn" (:config f)] ["pilot.edn" (:profile f)]
                          ["control-policy.edn" policy] ["history.edn" []]
                          ["evidence.edn" (:evidence f)]]]
      (spit (str dir "/" name) (pr-str value)))
    {:f f :dir dir :key key :policy policy :profile controlled-profile
     :revision pilot/method-root}))

(defn- sign [p key]
  ;; Same Ed25519 bytes as the existing signer; ephemeral keys, no fixture secret.
  (let [signed (#'keys/signed {:action/root (:publication/root p)} key)]
    (assoc p :publication/signature (:action/signature signed))))

(defn- prepare [{:keys [dir key revision]} previous]
  (sign (publication/build-publication (:public key) revision dir previous) key))

(defn- prepare-controlled [{:keys [dir key revision]} previous]
  (sign (publication/build-controlled-publication (:public key) revision dir previous) key))

(defn- reroot [p]
  (assoc p :publication/root (publication/publication-root p)))

(deftest signed-origin-retained-successor-and-byte-exact-manifest
  (let [{:keys [dir key f] :as s} (setup)
        a (prepare s nil) origin (:publication/root a) state (str dir "/reader.edn")]
    (is (= a (publication/verify-publication! (:public key) origin nil a dir)))
    (is (= (reference/sha256-ref-file (str dir "/history.edn")) (get-in a [:files "history.edn"])))
    (is (:trust/changed? (publication/advance-file! (:public key) origin state a dir)))
    (is (false? (:trust/changed? (publication/advance-file! (:public key) nil state a dir))))
    (spit (str dir "/history.edn") (pr-str (#'keys/enroll f [] :alice)))
    (let [b (prepare s a)]
      (is (= 1 (:publication/sequence b)))
      (is (= (:publication/root a) (:publication/previous-root b)))
      (is (:trust/changed? (publication/advance-file! (:public key) nil state b dir)))
      (is (= b (edn/read-string (slurp state))))
      (let [before (slurp state)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (publication/advance-file! (:public key) nil state a dir)))
        (is (= before (slurp state)))))))

(deftest rejects-forgery-file-tamper-unpinned-origin-and-stale-checkpoint
  (let [{:keys [dir key] :as s} (setup) a (prepare s nil) origin (:publication/root a)
        wrong (#'keys/keys!)]
    (is (thrown? clojure.lang.ExceptionInfo (publication/verify-publication! (:public key) nil nil a dir)))
    (is (thrown? clojure.lang.ExceptionInfo (publication/verify-publication! (:public wrong) origin nil a dir)))
    (doseq [bad [(assoc a :publication/signature (apply str (repeat 128 "0")))
                 (assoc a :publication/root pilot/method-root)
                 (sign (reroot (assoc-in a [:checkpoint :history/root] pilot/method-root)) key)
                 (sign (reroot (assoc a :pilot/root pilot/method-root)) key)]]
      (is (thrown? clojure.lang.ExceptionInfo (publication/verify-publication! (:public key) (:publication/root bad) nil bad dir))))
    (doseq [name publication/files]
      (let [path (str dir "/" name) old (slurp path)]
        ;; Even whitespace-only byte substitution changes the signed manifest.
        (spit path (str old "\n"))
        (is (thrown? clojure.lang.ExceptionInfo (publication/verify-publication! (:public key) origin nil a dir)))
        (spit path old)))))

(deftest signed-forks-retained-history-prefix-protected-and-no-latestness-claim
  (let [{:keys [dir key f] :as s} (setup) a (prepare s nil)
        alice (#'keys/enroll f [] :alice) bob (#'keys/enroll f [] :bob)]
    (spit (str dir "/history.edn") (pr-str alice))
    (let [b (prepare s a)]
      (spit (str dir "/history.edn") (pr-str bob))
      (let [c (prepare s a)
            conflict (publication/equivocation (:public key) b c)]
        (is (= :conflicting (:status conflict)))
        (is (= [b c] (:publications conflict)))
        (is (nil? (publication/equivocation (:public key) b b)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (publication/verify-publication! (:public key) nil b c dir)))
        ;; Higher publication sequence cannot hide a history fork or rollback.
        (is (thrown? clojure.lang.ExceptionInfo (prepare s b)))
        (spit (str dir "/history.edn") "[]")
        (is (thrown? clojure.lang.ExceptionInfo (prepare s b))))
      (spit (str dir "/history.edn") (pr-str alice))
      (doseq [bad [(sign (reroot (assoc b :publication/sequence 3)) key)
                   (sign (reroot (assoc b :publication/previous-root pilot/method-root)) key)
                   (sign (reroot (assoc b :source/revision-root (:artifact/root (:profile f)))) key)]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (publication/verify-publication! (:public key) nil a bad dir)))))))

(deftest malformed-retained-state-never-silently-reboots-trust
  (let [{:keys [dir key] :as s} (setup) a (prepare s nil) state (str dir "/reader.edn")]
    (spit state "nil")
    (is (thrown? clojure.lang.ExceptionInfo
                 (publication/advance-file! (:public key) (:publication/root a) state a dir)))
    (is (= "nil" (slurp state)))))

(deftest real-existing-signer-and-public-only-material-selection
  (let [{:keys [dir key] :as s} (setup)
        private-path (Files/createTempFile "shortfall-operator-test-" ".pem"
                                           (make-array java.nio.file.attribute.FileAttribute 0))
        unsigned (publication/build-publication (:public key) (:revision s) dir nil)]
    (try
      (spit (.toFile private-path)
            (str "-----BEGIN PRIVATE KEY-----\n"
                 (.encodeToString (Base64/getEncoder) (.getEncoded (:private key)))
                 "\n-----END PRIVATE KEY-----\n"))
      ;; An unrelated file is neither scanned nor included in public commitments.
      (spit (str dir "/unrelated.edn") "{:not :pilot-material}")
      (let [signed (publication/sign-publication unsigned (str private-path))]
        (is (= signed (publication/authenticate! (:public key) signed)))
        (is (= (set publication/files) (set (keys (:files signed)))))
        (is (= unsigned (publication/build-publication (:public key) (:revision s) dir nil)))
        (is (thrown? clojure.lang.ExceptionInfo (publication/sign-publication signed (str private-path)))))
      (finally (Files/deleteIfExists private-path)))))

(deftest controlled-publication-binds-policy-history-and-separate-reader-trust
  (let [{:keys [dir key f policy] :as s} (setup-controlled)
        a (prepare-controlled s nil) origin (:publication/root a)
        state (str dir "/controlled-reader.edn")]
    (is (= publication/controlled-schema (:artifact/schema a)))
    (is (= (:artifact/root policy) (:control-policy/root a)))
    (is (= (set publication/controlled-files) (set (keys (:files a)))))
    (is (= a (publication/verify-controlled-publication! (:public key) origin nil a dir)))
    (is (:trust/changed? (publication/advance-controlled-file! (:public key) origin state a dir)))
    (is (false? (:trust/changed? (publication/advance-controlled-file! (:public key) nil state a dir))))
    (let [alice (#'keys/enroll f [] :alice)]
      (spit (str dir "/history.edn") (pr-str alice))
      (let [b (prepare-controlled s a)]
        (is (= 1 (:publication/sequence b)))
        (is (= (:publication/root a) (:publication/previous-root b)))
        (is (:trust/changed? (publication/advance-controlled-file! (:public key) nil state b dir)))
        (is (= b (edn/read-string (slurp state))))
        (let [before (slurp state)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (publication/advance-controlled-file! (:public key) nil state a dir)))
          (is (= before (slurp state))))))))

(deftest controlled-publication-rejects-policy-and-history-substitution
  (let [{:keys [dir key f policy] :as s} (setup-controlled)
        a (prepare-controlled s nil) origin (:publication/root a)
        wrong-key (:public (#'keys/keys!))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (publication/verify-controlled-publication! wrong-key origin nil a dir)))
    (let [alice (#'keys/enroll f [] :alice)
          bob (#'keys/enroll f [] :bob)]
      (spit (str dir "/history.edn") (pr-str alice))
      (let [b (prepare-controlled s a)]
        (spit (str dir "/history.edn") (pr-str bob))
        (let [c (prepare-controlled s a)]
          (is (= :conflicting (:status (publication/controlled-equivocation (:public key) b c))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (publication/verify-controlled-publication! (:public key) nil b c dir)))))
      (spit (str dir "/history.edn") "[]"))
    (let [path (str dir "/control-policy.edn") original-policy (slurp (str dir "/control-policy.edn"))]
      (spit path (pr-str (assoc policy :control/authority :untrusted)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (publication/build-controlled-publication (:public key) (:revision s) dir nil)))
      (spit path (str original-policy "\n"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (publication/verify-controlled-publication! (:public key) origin nil a dir))))))
