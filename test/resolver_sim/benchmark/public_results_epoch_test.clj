(ns resolver-sim.benchmark.public-results-epoch-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.public-result-admission :as pra]
            [resolver-sim.benchmark.public-results-epoch :as sut]
            [resolver-sim.benchmark.researcher-run-report :as rrr]))

(def ^:private test-priv-key "test-keys/test-researcher-signing-key")
(def ^:private test-pub-key "test-keys/test-researcher-signing-key.pub")

(defn- root [c]
  (str "sha256:" (apply str (repeat 64 (first c)))))

(defn- manifest [& {:keys [results generated-case-set-root]
                    :or {results {:conservation :pass}
                         generated-case-set-root (root "6")}}]
  (outcome/build-manifest
   {:benchmark/content-root (root "c")
    :benchmark/model-root (root "1")
    :benchmark/evaluation-policy-root (root "2")
    :execution/model-instance-root (root "3")
    :execution/plan-root (root "4")
    :execution/parameter-domain-root (root "5")
    :execution/sampling-policy-root (root "7")
    :execution/generated-case-set-root generated-case-set-root
    :results/operational results}))

(defn- signed-report [m researcher run]
  (:report
   (rrr/sign-report!
    (rrr/build-report
     {:outcome-manifest m
      :researcher-id researcher
      :runner-info {:runner/id (str "runner-" researcher)
                    :source-tree-hash (root "8")
                    :distribution-hash (root "9")
                    :environment-hash (root "a")}
      :evidence-refs {:evidence-dag-root (root "b")
                      :event-evidence-root (root "c")
                      :execution-log-root (root "d")}
      :run-id run})
    test-priv-key)))

(defn- admit
  "Build one admitted report: returns {:report :manifest :public-key :admission}."
  [m researcher run]
  (let [r (signed-report m researcher run)
        a (pra/build r m (slurp test-pub-key))]
    {:report r :manifest m :public-key (slurp test-pub-key) :admission a}))

(defn- chain-up-to
  "Build a head chain through the given admitted entries (0 = genesis).
   Returns {:chain [h0..hN] :basis {:head/root :through-sequence} at N}."
  [entries]
  (let [h0 (sut/genesis-head)]
    (reduce (fn [{:keys [chain]} e]
              (let [h (sut/successor-head (peek chain) (:admission/root (:admission e)))]
                {:chain (conj chain h)}))
            {:chain [h0]}
            entries)))

(defn- basis-at [chain through]
  {:head/root (:head/root (nth chain through))
   :through-sequence through})

(defn- admissions-map [entries]
  (into {} (map (fn [e] [(:admission/root (:admission e))
                         (select-keys e [:report :manifest :public-key])]))
        entries))

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. Genesis cumulative epoch
;; ═══════════════════════════════════════════════════════════════════════════

(deftest genesis-cumulative-epoch
  (let [{:keys [chain]} (chain-up-to [])
        adm {}
        e (sut/build-epoch (basis-at chain 0) chain adm)]
    (is (= 0 (:epoch/sequence e)))
    (is (empty? (:epoch/members e)))
    (is (nil? (:epoch/predecessor e)))
    (is (:valid? (sut/verify-epoch e chain adm)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. Successor includes predecessor admissions plus new
;; ═══════════════════════════════════════════════════════════════════════════

(deftest successor-cumulatively-includes-predecessor-plus-new
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest :results {:conservation :pass :quota :fail}) "r2" "run2")
        c (admit (manifest) "r3" "run3")
        entries [a b c]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e1 (sut/build-epoch (basis-at chain 1) chain adm)
        e2 (sut/build-epoch (basis-at chain 2) chain adm)
        e3 (sut/build-epoch (basis-at chain 3) chain adm)
        set1 (set (map :report/root (:epoch/members e1)))
        set2 (set (map :report/root (:epoch/members e2)))
        set3 (set (map :report/root (:epoch/members e3)))]
    (is (= 1 (count set1)))
    (is (= 2 (count set2)))
    (is (= 3 (count set3)))
    (is (set/subset? set1 set2))
    (is (set/subset? set2 set3))
    (is (= 3 (count (:epoch/members e3))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3–5. Caller-supplied / persisted member body is not trusted
;; ═══════════════════════════════════════════════════════════════════════════

(deftest persisted-member-body-is-re-enumerated
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest) "r2" "run2")
        entries [a b]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e (sut/build-epoch (basis-at chain 2) chain adm)
        [m1 m2] (:epoch/members e)]
    (testing "omission from persisted member body rejected"
      (let [omitted (assoc e :epoch/members [m1])]
        (is (= :derived-epoch-mismatch (:reason (sut/verify-epoch omitted chain adm))))))
    (testing "extra member rejected"
      (let [extra (assoc e :epoch/members [m1 m2 m2])]
        (is (= :derived-epoch-mismatch (:reason (sut/verify-epoch extra chain adm))))))
    (testing "reordered persisted members rejected / noncanonical"
      (let [reordered (assoc e :epoch/members [m2 m1])]
        (is (= :derived-epoch-mismatch (:reason (sut/verify-epoch reordered chain adm))))))
    (testing "member data tampered (report swapped) rejected at re-enumeration"
      (let [a1 (:admission a)]
        (is (= :admission-verification-mismatch
               (:reason (sut/verify-epoch e chain
                                          {(:admission/root a1)
                                           {:report (:report b) :manifest (:manifest b)
                                            :public-key (:public-key b)}}))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 6. Ordering-chain tamper rejected
;; ═══════════════════════════════════════════════════════════════════════════

(deftest ordering-chain-tamper-rejected
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest) "r2" "run2")
        entries [a b]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e (sut/build-epoch (basis-at chain 2) chain adm)
        tampered-chain (assoc-in chain [1 :head/admission-root] (root "f"))]
    (is (= :invalid-admission-head
           (:reason (sut/verify-epoch e tampered-chain adm))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 7. Wrong head / basis root rejected
;; ═══════════════════════════════════════════════════════════════════════════

(deftest wrong-head-basis-root-rejected
  (let [a (admit (manifest) "r1" "run1")
        {:keys [chain]} (chain-up-to [a])
        adm (admissions-map [a])
        wrong-basis {:head/root (root "f") :through-sequence 1}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wrong-admission-head"
                          (sut/build-epoch wrong-basis chain adm)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 8. Predecessor fork / non-extension rejected
;; ═══════════════════════════════════════════════════════════════════════════

(deftest predecessor-fork-non-extension-rejected
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest) "r2" "run2")
        entries [a b]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e1 (sut/build-epoch (basis-at chain 1) chain adm)
        e2 (sut/build-epoch (basis-at chain 2) chain adm :predecessor (:epoch/root e1))
        forked (assoc e2 :epoch/predecessor (root "f"))]
    (is (:valid? (sut/verify-cumulative-lineage e2 e1 chain adm)))
    (is (= :predecessor-link-mismatch
           (:reason (sut/verify-cumulative-lineage forked e1 chain adm))))
    (let [e1-same-seq (sut/build-epoch (basis-at chain 1) chain adm
                                       :predecessor (:epoch/root e1))]
      (is (= :sequence-not-advancing
             (:reason (sut/verify-cumulative-lineage e1-same-seq e1 chain adm)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 9. Duplicate report / admission rejected
;; ═══════════════════════════════════════════════════════════════════════════

(deftest duplicate-admission-rejected
  (let [a (admit (manifest) "r1" "run1")
        ar (:admission/root (:admission a))
        h0 (sut/genesis-head)
        h1 (sut/successor-head h0 ar)
        h2 (sut/successor-head h1 ar)
        chain [h0 h1 h2]
        adm {ar {:report (:report a) :manifest (:manifest a) :public-key (:public-key a)}}]
    ;; the same admission admitted twice is rejected — via the stronger
    ;; report-root rule (identical admission implies identical report root)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate-report-root"
                          (sut/build-epoch (basis-at chain 2) chain adm)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 10. Divergent outcomes both retained
;; ═══════════════════════════════════════════════════════════════════════════

(deftest divergent-outcomes-both-retained
  (let [agreeing (admit (manifest) "r1" "run1")
        divergent (admit (manifest :results {:conservation :pass :quota :fail}) "r2" "run2")
        entries [agreeing divergent]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e (sut/build-epoch (basis-at chain 2) chain adm)]
    (is (= 2 (count (:epoch/members e))))
    (is (apply not= (map :report/root (:epoch/members e)))
        "divergent reports are both valid members; nothing is agreement-filtered")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 10b. Participation classes: presentation is never identity assurance
;; ═══════════════════════════════════════════════════════════════════════════

(deftest epoch-exposes-separate-participation-classes
  (let [anon (admit (manifest) "anonymous" "run1")
        declared (admit (manifest) "r2" "run2")
        declared2 (admit (manifest :results {:conservation :pass :quota :fail})
                         "r3" "run3")
        entries [anon declared declared2]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e (sut/build-epoch (basis-at chain 3) chain adm)]
    (is (= {:anonymous 1 :declared-unresolved 2 :authenticated 0}
           (:epoch/participation e)))
    (is (some (fn [m]
                (= :anonymous
                   (:presentation (:admission/participation m))))
              (:epoch/members e))
        "an anonymous member is present and classified separately")
    (is (every? (fn [m]
                  (= :unresolved
                     (:identity-assurance (:admission/participation m))))
                (:epoch/members e))
        "V1 never exposes :authenticated assurance")))

(deftest epoch-participation-is-derived-not-supplied
  (let [a (admit (manifest) "anonymous" "run1")
        {:keys [chain]} (chain-up-to [a])
        adm (admissions-map [a])
        e (sut/build-epoch (basis-at chain 1) chain adm)
        reclassified (assoc e :epoch/participation
                            {:anonymous 0 :declared-unresolved 1 :authenticated 0})]
    (is (= {:anonymous 1 :declared-unresolved 0 :authenticated 0}
           (:epoch/participation e)))
    (is (= :derived-epoch-mismatch (:reason (sut/verify-epoch reclassified chain adm))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 11–12. Epoch set-root / root tamper rejected
;; ═══════════════════════════════════════════════════════════════════════════

(deftest epoch-set-root-and-root-tamper-rejected
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest) "r2" "run2")
        entries [a b]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        e (sut/build-epoch (basis-at chain 2) chain adm)]
    (is (= :derived-epoch-mismatch
           (:reason (sut/verify-epoch (assoc e :epoch/set-root (root "f")) chain adm))))
    (is (= :derived-epoch-mismatch
           (:reason (sut/verify-epoch (assoc e :epoch/root (root "f")) chain adm))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 13. Determinism: same basis → same members / set root
;; ═══════════════════════════════════════════════════════════════════════════

(deftest same-basis-deterministically-produces-same-epoch
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest) "r2" "run2")
        entries [a b]
        {:keys [chain]} (chain-up-to entries)
        adm (admissions-map entries)
        basis (basis-at chain 2)
        e1 (sut/build-epoch basis chain adm)
        e2 (sut/build-epoch basis chain adm)]
    (is (= e1 e2))
    (is (= (:epoch/members e1) (:epoch/members e2)))
    (is (= (:epoch/set-root e1) (:epoch/set-root e2)))
    (is (= (:epoch/root e1) (:epoch/root e2)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 14. A valid alternative head is locally valid, not globally canonical
;; ═══════════════════════════════════════════════════════════════════════════

(deftest alternative-head-is-locally-valid-not-globally-canonical
  (let [a (admit (manifest) "r1" "run1")
        b (admit (manifest :results {:conservation :pass :quota :fail}) "r2" "run2")
        a-only [a]
        both [a b]
        chain-a (:chain (chain-up-to a-only))
        chain-b (:chain (chain-up-to both))
        adm-a (admissions-map a-only)
        adm-both (admissions-map both)
        e-a (sut/build-epoch (basis-at chain-a 1) chain-a adm-a)
        e-b (sut/build-epoch (basis-at chain-b 2) chain-b adm-both)]
    ;; each is locally valid against its own basis
    (is (:valid? (sut/verify-epoch e-a chain-a adm-a)))
    (is (:valid? (sut/verify-epoch e-b chain-b adm-both)))
    (is (= 1 (count (:epoch/members e-a))))
    (is (= 2 (count (:epoch/members e-b))))
    (is (= {:head/root (:head/root (nth chain-a 1)) :through-sequence 1}
           (:epoch/admission-basis e-a)))
    (is (nil? (:epoch/global-canonical e-a))
        "an epoch does not assert global canonicality")
    (is (nil? (:epoch/canonical-head e-a)))))