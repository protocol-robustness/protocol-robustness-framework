(ns resolver-sim.benchmark.replication-observation-set-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.public-result-admission :as admission]
            [resolver-sim.benchmark.replication-observation-set :as sut]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.verified-researcher-run :as verified]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private test-priv-key "test-keys/test-researcher-signing-key")
(def ^:private test-pub-key "test-keys/test-researcher-signing-key.pub")

(defn- root
  "A canonical sha256:<64-hex> reference keyed by a single hex char, so
   distinct fields are guaranteed valid and pairwise distinct."
  [c]
  (str "sha256:" (apply str (repeat 64 (first c)))))

(defn- manifest
  "A complete benchmark-outcome.v1 manifest with valid, distinct roots.
   Options: :generated-case-set-root (independent-sampling),
   :plan-root (nil to drop a required exact-scope field),
   :results (operational results — outside exact scope but inside outcome-hash),
   :force-authorisation (an FA section map)."
  [& {:keys [generated-case-set-root plan-root results force-authorisation]
      :or {generated-case-set-root (root "6") plan-root (root "4")}}]
  (outcome/build-manifest
   (cond-> {:benchmark/content-root (root "c")
            :benchmark/model-root (root "1")
            :benchmark/evaluation-policy-root (root "2")
            :execution/model-instance-root (root "3")
            :execution/plan-root plan-root
            :execution/parameter-domain-root (root "5")
            :execution/sampling-policy-root (root "7")
            :execution/generated-case-set-root generated-case-set-root
            :results/operational (or results {:conservation :pass})}
     force-authorisation (assoc :execution/force-authorisation force-authorisation))))

(defn- signed-report
  "A genuinely signed researcher run report (real Ed25519 signing)."
  [m researcher run]
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

(defn- build-verified
  "Run sut/build through the real shared verification pipeline (real crypto,
   structure, manifest binding, scope, duplicates)."
  [entries]
  (sut/build entries))

(defn- verify-with [observation entries]
  (sut/verify observation entries))

(defn- entry [m researcher run & {:keys [manifest] :or {manifest m}}]
  {:report (signed-report m researcher run)
   :manifest manifest
   :public-key-path test-pub-key})

(defn member [root researcher run outcome & {:keys [runner source distribution environment]}]
  {:report-root root
   :manifest-root (str "manifest-" root)
   :outcome-root outcome
   :report {:researcher/id researcher :run/id run
            :runner {:runner/id runner
                     :source-tree-hash source
                     :distribution-hash distribution
                     :environment-hash environment}}
   :manifest {:id (str "m-" root)}})

(defn build-with [members]
  (with-redefs-fn {#'sut/verify-member! identity
                   #'outcome/exact-replication-scope? (fn [_ _] true)}
    #(sut/build members)))

(defn build-real-scope
  "Stub only member verification (no crypto), keep the real exact-scope
   predicate so scope admissibility is exercised genuinely."
  [members]
  (with-redefs-fn {#'sut/verify-member! identity}
    #(sut/build members)))

(defn- distinct-runner-entry [m researcher run env-suffix]
  {:report-root (str "report-" researcher "-" run)
   :manifest-root (:researcher-run-report/outcome-manifest-hash
                   (rrr/build-report {:outcome-manifest m}))
   :outcome-root (outcome/outcome-hash m)
   :report {:researcher/id researcher :run/id run
            :runner {:runner/id (str "runner-" researcher)
                     :source-tree-hash (root "8")
                     :distribution-hash (root "9")
                     :environment-hash (str "sha256:" env-suffix)}}
   :manifest m})

;; ═══════════════════════════════════════════════════════════════════════════
;; Canonicality & permutation (Task 3)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest permutation-is-canonical
  (let [a (member "a" "r1" "run1" "o1" :runner "s1" :source "d1" :distribution "e1" :environment "f1")
        b (member "b" "r2" "run2" "o1" :runner "r2" :source "s2" :distribution "d2" :environment "e2")
        x (build-with [a b])
        y (build-with [b a])]
    (is (= x y))
    (is (= "a" (:reference/member x)))
    (is (= (:replication-observation-set/root x)
           (sut/observation-root (dissoc x :replication-observation-set/root))))))

(deftest any-input-order-yields-identical-canonical-observation
  (let [members [(member "a" "r1" "run1" "o1" :runner "s1" :source "d1" :distribution "e1" :environment "f1")
                 (member "b" "r2" "run2" "o1" :runner "r2" :source "s2" :distribution "d2" :environment "e2")
                 (member "c" "r3" "run3" "o2" :runner "r3" :source "s3" :distribution "d3" :environment "f3")
                 (member "d" "r4" "run4" "o2" :runner "r4" :source "s4" :distribution "d4" :environment "f4")]
        base (build-with members)
        orders (rest (take 7 (iterate (fn [v] (concat (drop 1 v) [(first v)])) members)))]
    (doseq [order orders]
      (let [o (build-with order)]
        (is (= base o) "permuted order must yield identical canonical observation")
        (is (= (:reference/member base) (:reference/member o)))
        (is (= (mapv :researcher-run-report/root (:members base))
               (mapv :researcher-run-report/root (:members o))))
        (is (= (get-in base [:outcome-agreement :groups])
               (get-in o [:outcome-agreement :groups])))
        (is (= (:provenance-diversity base) (:provenance-diversity o)))
        (is (= (:replication-observation-set/root base)
               (:replication-observation-set/root o)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Outcome agreement statuses
;; ═══════════════════════════════════════════════════════════════════════════

(deftest outcome-statuses
  (testing "unanimous"
    (is (= :unanimous (get-in (build-with [(member "a" "r1" "1" "o")
                                           (member "b" "r2" "2" "o")])
                              [:outcome-agreement :status]))))
  (testing "disagreement"
    (is (= :disagreement (get-in (build-with [(member "a" "r1" "1" "o1")
                                              (member "b" "r2" "2" "o2")
                                              (member "c" "r3" "3" "o1")])
                                 [:outcome-agreement :status]))))
  (testing "all distinct"
    (is (= :all-distinct (get-in (build-with [(member "a" "r1" "1" "o1")
                                              (member "b" "r2" "2" "o2")])
                                 [:outcome-agreement :status]))))
  (testing "singleton"
    (is (= :insufficient (get-in (build-with [(member "a" "r1" "1" "o1")])
                                 [:outcome-agreement :status])))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Membership & duplicate rejection
;; ═══════════════════════════════════════════════════════════════════════════

(deftest rejects-membership-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no-members" (sut/build [])))
  (let [a (member "a" "r1" "1" "o")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate-report-root"
                          (build-with [a a])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate-researcher-run"
                          (build-with [a (assoc a :report-root "b")])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate-researcher-run"
                          (build-with [(member "a" "r1" "1" "o")
                                       (member "b" "r1" "1" "o")])))
    (with-redefs-fn {#'sut/verify-member! identity
                     #'outcome/exact-replication-scope? (fn [x y] (= x y))}
      (fn []
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible-exact"
                              (sut/build [a (member "b" "r2" "2" "o")])))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Adversarial exact-scope matrix (Task 1)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest independent-sampling-member-is-rejected
  (let [a (distinct-runner-entry (manifest) "r1" "run1" "e1")
        indep (distinct-runner-entry (manifest :generated-case-set-root (root "f"))
                                     "r2" "run2" "e2")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible-exact"
                          (build-real-scope [a indep])))))

(deftest matching-missing-required-exact-scope-field-still-rejects
  (let [a (distinct-runner-entry (manifest :plan-root nil) "r1" "run1" "e1")
        b (distinct-runner-entry (manifest :plan-root nil) "r2" "run2" "e2")]
    (is (false? (outcome/exact-replication-scope? (:manifest a) (:manifest b))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible-exact"
                          (build-real-scope [a b])))))

(deftest different-provenance-is-admissible-and-changes-diversity-only
  (let [m (manifest)
        a (distinct-runner-entry m "r1" "run1" "e1")
        b (distinct-runner-entry m "r2" "run2" "e2")
        obs (build-real-scope [a b])
        base-div (build-real-scope [(distinct-runner-entry m "r1" "run1" "e1")
                                    (distinct-runner-entry m "r2" "run2" "e2")])
        alt-div (build-real-scope [(distinct-runner-entry m "r1" "run1" "e1")
                                   (distinct-runner-entry m "r2" "run2" "other-env")])]
    (is (= :unanimous (get-in obs [:outcome-agreement :status])))
    (is (= ["r1" "r2"] (get-in obs [:provenance-diversity :researcher-ids])))
    (is (= ["runner-r1" "runner-r2"] (get-in obs [:provenance-diversity :runner-ids])))
    (is (= (:reference/member base-div) (:reference/member alt-div)))
    (is (= (:outcome-agreement base-div) (:outcome-agreement alt-div)))
    (is (not= (:provenance-diversity base-div) (:provenance-diversity alt-div)))))

(deftest different-force-authorisation-provenance-same-executed-content-root-is-exact
  (let [fa-a {:authorisation-hash (root "a") :reservation-hash (root "b")
              :consumption-key (root "c") :execution-attempt-id :x
              :executed-content-root (root "f") :status :consumed}
        fa-b {:authorisation-hash (root "d") :reservation-hash (root "e")
              :consumption-key (root "g") :execution-attempt-id :y
              :executed-content-root (root "f") :status :consumed}
        m-a (manifest :force-authorisation fa-a)
        m-b (manifest :force-authorisation fa-b)]
    (is (false? (outcome/same-authorisation-provenance? m-a m-b)))
    (is (= (get-in m-a [:execution/force-authorisation :executed-content-root])
           (get-in m-b [:execution/force-authorisation :executed-content-root])))
    (is (true? (outcome/exact-replication-scope? m-a m-b))
        "different FA provenance with same effective executed-content-root is admissible")
    (let [obs (build-real-scope [(distinct-runner-entry m-a "r1" "run1" "e1")
                                 (distinct-runner-entry m-b "r2" "run2" "e2")])]
      (is (= 2 (count (:members obs))))
      (is (= 2 (:completed (get-in obs [:outcome-agreement])))))))

(deftest disagreement-is-a-successfully-verifiable-observation
  (let [m-a (manifest)
        m-b (manifest :results {:conservation :pass :quota :fail})
        e1 (entry m-a "r1" "run1")
        e2 (entry m-b "r2" "run2")
        e3 (entry m-a "r3" "run3")
        obs (build-verified [e1 e2 e3])]
    (is (= :disagreement (get-in obs [:outcome-agreement :status])))
    (is (= 2 (get-in obs [:outcome-agreement :distinct-outcome-roots])))
    (is (= 3 (:completed (get-in obs [:outcome-agreement]))))
    (is (:valid? (verify-with obs [e1 e2 e3])))))

(deftest reference-compatibility-implies-pairwise-compatibility
  (let [m (manifest)
        members [(distinct-runner-entry m "r1" "run1" "e1")
                 (distinct-runner-entry m "r2" "run2" "e2")
                 (distinct-runner-entry m "r3" "run3" "e3")
                 (distinct-runner-entry m "r4" "run4" "e4")]
        obs (build-real-scope members)]
    (doseq [[i a] (map-indexed vector members)
            [j b] (map-indexed vector members)]
      (when (< i j)
        (is (true? (outcome/exact-replication-scope? (:manifest a) (:manifest b)))
            (str "pairwise compatibility for members " i " and " j))))
    (is (= (count members) (count (:members obs))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tamper resistance through the real verification path (Task 1)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest tampered-report-hash-signature-rejects
  (let [m (manifest)
        e1 (entry m "r1" "run1")
        e2 (entry m "r2" "run2")
        obs (build-verified [e1 e2])
        tampered [(assoc-in e1 [:report :researcher/id] "evil") e2]
        hash-tampered [(assoc-in e1 [:report :researcher-run-report/hash] (root "f")) e2]
        sig-tampered [(assoc-in e1 [:report :researcher/signature :value] "tampered") e2]
        sig-removed [(update e1 :report dissoc :researcher/signature) e2]]
    (is (:valid? (verify-with obs [e1 e2])))
    (is (false? (:valid? (verify-with obs tampered))))
    (is (false? (:valid? (verify-with obs hash-tampered))))
    (is (false? (:valid? (verify-with obs sig-tampered))))
    (is (false? (:valid? (verify-with obs sig-removed))))
    (is (= :invalid-report-signature (:reason (verify-with obs tampered))))))

(deftest report-manifest-tampering-rejects
  (let [m (manifest)
        other (manifest :generated-case-set-root (root "f"))
        e1 (entry m "r1" "run1")
        e2 (entry m "r2" "run2")
        obs (build-verified [e1 e2])
        binding-tampered [(entry m "r1" "run1" :manifest other) e2]]
    (is (:valid? (verify-with obs [e1 e2])))
    (is (= :report-manifest-mismatch (:reason (verify-with obs binding-tampered))))))

(deftest report-outcome-hash-mismatch-rejects
  (let [m (manifest)
        other (manifest :generated-case-set-root (root "f"))
        e1 (entry m "r1" "run1")
        e2 (entry m "r2" "run2")
        obs (build-verified [e1 e2])
        ;; report carries the outcome-hash of m; bind it to a manifest with a
        ;; different outcome but keep the report↔manifest binding valid.
        outcome-tampered [(entry m "r1" "run1" :manifest other) e2]]
    (is (not= (outcome/outcome-hash m) (outcome/outcome-hash other)))
    (with-redefs [rrr/verify-against-manifest (fn [_ _] {:valid? true :mismatches []})]
      (let [r (verify-with obs outcome-tampered)]
        (is (false? (:valid? r)))
        (is (= :outcome-hash-mismatch (:reason r)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Caller-supplied derived fields are never trusted (Task 1)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest caller-cannot-tamper-derived-fields
  (let [entries [(member "a" "r1" "1" "o1")
                 (member "b" "r2" "2" "o1")]
        x (build-with entries)
        tampered (assoc-in x [:outcome-agreement :status] :disagreement)]
    (with-redefs-fn {#'sut/verify-member! identity
                     #'outcome/exact-replication-scope? (fn [_ _] true)}
      (fn []
        (is (:valid? (sut/verify x entries)))
        (is (false? (:valid? (sut/verify tampered
                                         (map #(assoc % :outcome-root "o2") entries)))))))))

(deftest tampering-each-derived-field-causes-verify-to-reject
  (let [entries [(member "a" "r1" "1" "o1")
                 (member "b" "r2" "2" "o1")
                 (member "c" "r3" "3" "o2")]
        base (build-with entries)
        tamperers
        [(assoc-in base [:outcome-agreement :groups 0 :members] ["x"])      ;; groups
         (assoc-in base [:outcome-agreement :status] :unanimous)             ;; status
         (assoc-in base [:provenance-diversity :researcher-ids] ["evil"])    ;; diversity
         (assoc base :reference/member "c")                                  ;; reference/member
         (assoc-in base [:members 0 :researcher-run-report/root] "x")         ;; member field
         (assoc base :replication-observation-set/root (root "f"))]           ;; observation root
        assert-rejects
        (fn [tampered]
          (with-redefs-fn {#'sut/verify-member! identity
                           #'outcome/exact-replication-scope? (fn [_ _] true)}
            (fn [] (is (false? (:valid? (sut/verify tampered entries)))))))]
    (is (:valid? (with-redefs-fn {#'sut/verify-member! identity
                                  #'outcome/exact-replication-scope? (fn [_ _] true)}
                   #(sut/verify base entries))))
    (doseq [tampered tamperers] (assert-rejects tampered))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Identity vocabulary — */root aliases are exact projections (Task 2)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest member-roots-are-exact-projections-of-canonical-hashes
  (let [m (manifest)
        e1 (entry m "r1" "run1")
        e2 (entry m "r2" "run2")
        obs (build-verified [e1 e2])
        report (:report e1)
        member-row (some #(when (= (:researcher-run-report/hash report)
                                   (:researcher-run-report/root %))
                            %)
                         (:members obs))]
    (is (some? member-row) "observation must contain a row for the report")
    (is (= (:researcher-run-report/hash report)
           (:researcher-run-report/root member-row)))
    (is (= (:researcher-run-report/outcome-manifest-hash report)
           (:outcome-manifest/root member-row)))
    (is (= (:benchmark-outcome/hash m)
           (:outcome-manifest/root member-row)))
    (is (= (:researcher-run-report/outcome-hash report)
           (:outcome/root member-row)))
    (is (= (outcome/outcome-hash m)
           (:outcome/root member-row)))
    ;; the only novel root is the observation's own commitment
    (is (string? (:replication-observation-set/root obs)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Diversity & authorship boundary (Task 4)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest diversity-and-assurance-are-derived
  (let [x (build-with [(member "a" "r2" "2" "o" :runner "z" :source "s2" :distribution "d2" :environment "e2")
                       (member "b" "r1" "1" "o" :runner "a" :source "s1" :distribution "d1" :environment "e1")])]
    (is (= ["r1" "r2"] (get-in x [:provenance-diversity :researcher-ids])))
    (is (= ["a" "z"] (get-in x [:provenance-diversity :runner-ids])))
    (is (= :verified-against-supplied-key
           (get-in x [:authorship-assurance :signature-verification])))
    (is (= :unresolved
           (get-in x [:authorship-assurance :researcher-to-key-binding])))))

(deftest valid-signature-under-supplied-key-retains-unresolved-binding
  (let [m (manifest)
        obs (build-verified [(entry m "r1" "run1") (entry m "r2" "run2")])
        assurance (:authorship-assurance obs)]
    (is (= :verified-against-supplied-key (:signature-verification assurance)))
    (is (= :unresolved (:researcher-to-key-binding assurance)))))

(deftest authorship-boundary-is-pinned-literally
  (let [m (manifest)
        obs (build-verified [(entry m "r1" "run1") (entry m "r2" "run2")])]
    (is (= {:signature-verification :verified-against-supplied-key
            :researcher-to-key-binding :unresolved}
           (:authorship-assurance obs))
        "signature verified against supplied key; researcher-to-key binding unresolved")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Gate A — shared verified-researcher-run primitive
;; ═══════════════════════════════════════════════════════════════════════════

(deftest real-crypto-triple-regression
  (testing "the exact same report/manifest/key verifies through the shared
            primitive, builds a public-result admission, and builds+verifies a
            replication observation"
    (let [m (manifest)
          sr (signed-report m "r1" "run1")
          pub (slurp test-pub-key)
          shared (verified/verify {:report sr :manifest m :public-key pub})
          admission (admission/build sr m pub)
          obs (sut/build [{:report sr :manifest m :public-key-path test-pub-key}])]
      (is (= (:report-root shared) (:report/root admission)))
      (is (= (:report-root shared) (:researcher-run-report/root (first (:members obs)))))
      (is (= (:manifest-root shared) (:manifest/root admission)))
      (is (= (:outcome-root shared) (:outcome/root admission)))
      (is (= (:researcher-run-report/hash sr) (:report-root shared)))
      (is (true? (:valid? (sut/verify obs [{:report sr :manifest m
                                            :public-key-path test-pub-key}]))))
      (is (true? (:valid? (admission/verify admission sr m pub)))))))

(deftest preimage-behavior-is-pinned
  (testing "the signed preimage always has :researcher-run-report/hash nil,
            regardless of the report's hash field state (absent / nil / populated)"
    (let [m (manifest)
          base (:report (rrr/sign-report! (rrr/build-report {:outcome-manifest m
                                                             :researcher-id "r1"
                                                             :runner-info {:runner/id "r1"
                                                                           :source-tree-hash (root "8")
                                                                           :distribution-hash (root "9")
                                                                           :environment-hash (root "a")}
                                                             :evidence-refs {:evidence-dag-root (root "b")
                                                                             :event-evidence-root (root "c")
                                                                             :execution-log-root (root "d")}
                                                             :run-id "run1"})
                                          test-priv-key))
          populated (verified/signed-preimage base)
          nil-hash (verified/signed-preimage (assoc base :researcher-run-report/hash nil))
          absent (verified/signed-preimage (dissoc base :researcher-run-report/hash))]
      (is (= nil (:researcher-run-report/hash populated)))
      (is (= nil (:researcher-run-report/hash nil-hash)))
      (is (= nil (:researcher-run-report/hash absent)))
      (is (= populated nil-hash) "populated and nil hash preimages coincide")
      (is (= populated absent) "populated and absent hash preimages coincide")
      (is (nil? (:researcher/signature populated)) "signature never part of preimage")
      (is (= (str "sha256:" (hc/domain-hash
                             :researcher-run-report populated))
             (:researcher-run-report/hash base))
          "committed report-hash equals the signed-preimage reconstruction"))))
