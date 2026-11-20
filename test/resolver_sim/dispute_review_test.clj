(ns resolver-sim.dispute-review-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.dispute-review :as review]
            [resolver-sim.commands.dispute-review :as commands])
  (:import [java.nio.file Files]))

(defn- temp-dir [] (Files/createTempDirectory "prf-dispute-review-test" (make-array java.nio.file.attribute.FileAttribute 0)))
(defn- sha256 [file]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and 0xff %))
                    (.digest d (Files/readAllBytes (.toPath file)))))))

(defn- package-file [package name content]
  (let [file (io/file (:directory package) name)]
    (spit file content)
    file))

(deftest independent-identities-and-review-directories
  (let [base (temp-dir)
        req {:target "same target" :scope "same scope"}
        a (review/initialize! req base)
        b (review/initialize! req base)]
    (is (not= (:id a) (:id b)))
    (is (not= (:directory a) (:directory b)))
    ;; Directory instance IDs are excluded from content identity.
    (is (= (:review/canonical-root (:manifest a))
           (:review/canonical-root (assoc (:manifest b) :review/instance-id (:id a)))))))

(deftest verification-distinguishes-missing-failed-and-passed-evidence
  (let [base (temp-dir)
        evidence-file (.toFile (.resolve base "evidence.edn"))
        _ (spit evidence-file "{:frozen true}\n")
        request {:target "fixture claim" :scope "bounded reconstruction"
                 :evidence [{:path "evidence.edn" :sha256 (sha256 evidence-file)
                             :trust-class :locally-consistent}
                            {:path "absent.edn" :sha256 (apply str (repeat 64 "0"))
                             :trust-class :unestablished}]}
        created (review/initialize! request base)
        _ (package-file created "evidence.edn" "{:frozen true}\n")
        result (review/verify (:directory created))]
    (is (= 1 (get-in result [:evidence :absent])))
    (is (= :incomplete (:qualification result)))
    ;; A changed body must fail the canonical identity check.
    (spit (io/file (:directory created) "manifest.edn")
          (str (pr-str (assoc (:manifest created) :review/scope "tampered")) "\n"))
    (is (= :failed (:content-identity (review/verify (:directory created)))))))

(deftest verification-rejects-changed-pinned-source-and-never-qualifies-incomplete
  (let [base (temp-dir)
        source (.toFile (.resolve base "snapshot.edn"))
        _ (spit source "frozen")
        digest (sha256 source)
        created (review/initialize! {:target "world observation" :scope "read-only"
                                     :source-refs [{:path "snapshot.edn" :sha256 digest}]
                                     :reproduction []}
                                    base)]
    (package-file created "snapshot.edn" "frozen")
    (is (= :passed (get-in (review/verify (:directory created)) [:source-identities :status])))
    (spit (io/file (:directory created) "snapshot.edn") "changed")
    (let [result (review/verify (:directory created))]
      (is (= :failed (get-in result [:source-identities :status])))
      (is (= :incomplete (:qualification result)))
      (is (= :review-only (:authority result))))))

(deftest reproducibility-requires-matching-recorded-result-and-verification-is-read-only
  (let [base (temp-dir)
        target-file (.toFile (.resolve base "authoritative-state.edn"))
        _ (spit target-file "{:protocol/state :unchanged}")
        before (slurp target-file)
        staged-result (.toFile (.resolve base "replay-result.edn"))
        _ (spit staged-result "{:value 2}\n")
        package (review/initialize! {:target "state claim" :scope "read-only fixture"
                                     :source-refs [{:path "authoritative-state.edn" :sha256 (sha256 target-file)}]
                                     :findings [{:id "local-replay"
                                                 :claim "The frozen fixture reconstruction matches the expected value"
                                                 :assessment :inconclusive
                                                 :expected {:value 2} :observed {:value 2}
                                                 :evidence-refs [] :reproduction-id "replay"
                                                 :counterexample nil
                                                 :trust-class :locally-consistent
                                                 :coverage-limitations ["Local fixture only"]}]
                                     :reproduction [{:id "replay" :command "bb -f replay.clj"
                                                     :input-refs []
                                                     :result-ref {:path "replay-result.edn" :sha256 (sha256 staged-result)}
                                                     :expected {:value 2} :observed {:value 2}}]}
                                    base)
        _ (package-file package "authoritative-state.edn" before)
        result-file (io/file (:directory package) "replay-result.edn")
        _ (spit result-file "{:value 2}\n")
        verified (review/verify (:directory package))]
    (is (= :passed (get-in verified [:reproducibility :status])))
    (is (= before (slurp target-file)))
    (spit result-file "{:value 3}\n")
    (is (= :failed (get-in (review/verify (:directory package))
                           [:reproducibility :status])))))

(deftest reproduction-result-identity-must-pass-before-reproduction-can-pass
  (let [base (temp-dir)
        staged (.toFile (.resolve base "recorded.edn"))
        _ (spit staged "{:value 2}\n")
        package (review/initialize! {:target "replay result" :scope "identity check"
                                     :reproduction [{:id "replay" :command "bb -f replay.clj"
                                                     :input-refs []
                                                     :result-ref {:path "recorded.edn"
                                                                  :sha256 (apply str (repeat 64 "0"))}
                                                     :expected {:value 2} :observed {:value 2}}]}
                                    base)]
    (package-file package "recorded.edn" "{:value 2}\n")
    (let [result (review/verify (:directory package))]
      (is (= :failed (get-in result [:reproducibility :status])))
      (is (= :identity-mismatch (get-in result [:reproducibility :checks 0 :reason])))
      (is (= 1 (:exit-code (binding [*out* (java.io.StringWriter.)]
                             (commands/verify-review {:cmd/raw-args ["--review" (:directory package)]}))))))))

(deftest references-cannot-escape-review-directory-and-cli-fails-on-failed-checks
  (let [base (temp-dir)
        outside (.toFile (.resolve base "outside.edn"))
        _ (spit outside "private")
        package (review/initialize! {:target "bounded paths" :scope "path confinement"
                                     :source-refs [{:path "../outside.edn" :sha256 (sha256 outside)}]
                                     :evidence [{:path "local.edn" :sha256 (apply str (repeat 64 "0"))
                                                 :trust-class :locally-consistent}]}
                                    base)
        _ (package-file package "local.edn" "changed")
        result (review/verify (:directory package))
        cli-result (binding [*out* (java.io.StringWriter.)]
                     (commands/verify-review {:cmd/raw-args ["--review" (:directory package)]}))]
    (is (= :failed (get-in result [:source-identities :status])))
    (is (= :path-outside-review (get-in result [:source-identities :checks 0 :reason])))
    (is (= 1 (get-in result [:evidence :failed])))
    (is (= 1 (:exit-code cli-result)))))

(deftest subject-and-policy-identities-fail-closed-when-pinned-files-change
  (let [base (temp-dir)
        subject-source (.toFile (.resolve base "subject.edn"))
        policy-source (.toFile (.resolve base "policy.edn"))
        _ (spit subject-source "{:case 1}")
        _ (spit policy-source "{:policy :pinned}")
        subject-hash (sha256 subject-source)
        policy-hash (sha256 policy-source)
        package (review/initialize!
                 {:target "case reconstruction" :scope "exact case instance"
                  :subject {:subject/kind :dispute-case :subject/id "case-1"
                            :subject/instance-id "case-instance-1"
                            :subject/root (str "sha256:" subject-hash)
                            :subject/reference {:path "subject.edn" :sha256 subject-hash}}
                  :policy {:id "review-policy-1"
                           :reference {:path "policy.edn" :sha256 policy-hash}
                           :status :pinned}}
                 base)]
    (package-file package "subject.edn" "{:case 1}")
    (package-file package "policy.edn" "{:policy :pinned}")
    (is (= :passed (:status (:subject-identity (review/verify (:directory package))))))
    (is (= :passed (:status (:policy-identity (review/verify (:directory package))))))
    (let [manifest-file (io/file (:directory package) "manifest.edn")
          original (edn/read-string (slurp manifest-file))
          altered (assoc-in original [:review/subject :subject/root]
                            (str "sha256:" (apply str (repeat 64 "0"))))
          altered (assoc altered :review/canonical-root (review/canonical-root altered))]
      (spit manifest-file (str (pr-str altered) "\n"))
      (let [result (review/verify (:directory package))]
        (is (= :failed (:status (:subject-identity result))))
        (is (= 1 (:exit-code (binding [*out* (java.io.StringWriter.)]
                               (commands/verify-review {:cmd/raw-args ["--review" (:directory package)]}))))))
      (spit manifest-file (str (pr-str original) "\n")))
    (spit (io/file (:directory package) "policy.edn") "{:policy :changed}")
    (is (= :failed (:status (:policy-identity (review/verify (:directory package))))))
    (spit (io/file (:directory package) "subject.edn") "{:case 2}")
    (is (= :failed (:status (:subject-identity (review/verify (:directory package))))))))

(deftest findings-must-reference-exact-package-evidence-and-reproduction
  (let [base (temp-dir)
        staged-evidence (.toFile (.resolve base "fact.edn"))
        staged-result (.toFile (.resolve base "result.edn"))
        _ (spit staged-evidence "{:fact 1}")
        _ (spit staged-result "{:result 1}")
        evidence {:path "fact.edn" :sha256 (sha256 staged-evidence)
                  :trust-class :externally-authenticated}
        reproduction {:id "replay" :command "bb -f replay.clj"
                      :input-refs []
                      :result-ref {:path "result.edn" :sha256 (sha256 staged-result)}
                      :expected {:result 1} :observed {:result 1}}
        finding {:id "fact-supported" :claim "fact is one" :expected 1 :observed 1
                 :evidence-refs [evidence] :reproduction-id "replay"
                 :counterexample nil :assessment :supported
                 :trust-class :externally-authenticated
                 :coverage-limitations ["Synthetic fixture"]}
        package (review/initialize! {:target "fact" :scope "one fact"
                                     :evidence [evidence] :findings [finding]
                                     :reproduction [reproduction]}
                                    base)]
    (package-file package "fact.edn" "{:fact 1}")
    (package-file package "result.edn" "{:result 1}")
    (is (= :passed (:schema (review/verify (:directory package)))))
    (let [manifest (review/verify (:directory package))]
      (is (= 2 (get-in manifest [:evidence :passed])))
      (is (= :not-checked (get-in manifest [:trust-classification :external-authentication])))
      (is (= 1 (get-in manifest [:trust-classification :declared :externally-authenticated]))))
    (let [manifest-file (io/file (:directory package) "manifest.edn")
          manifest (edn/read-string (slurp manifest-file))
          bad-finding (assoc finding :evidence-refs [(assoc evidence :role :unlisted)])
          tampered (assoc manifest :review/findings [bad-finding])
          tampered (assoc tampered :review/canonical-root (review/canonical-root tampered))]
      (spit manifest-file (str (pr-str tampered) "\n"))
      (let [result (review/verify (:directory package))]
        (is (= :failed (:schema result)))
        (is (= :passed (:content-identity result)))))))
