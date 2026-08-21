(ns resolver-sim.composition.v1-conformance-test
  "Phase-2 production-side conformance for Semantic Composition V1.

   This test independently verifies that the production V1 module reproduces
   the clean-room golden vectors exactly — compact form, canonical bytes, and
   composition-root.  It consumes the clean-room conformance EDN only as data.

   Dependency direction:
     production semantic objects → production projection → v1 compact value
     → independent production encoder → golden vectors (verification)

   The production never requires prf-clean-room.composition; it has its own
   independently implemented projection and reuses only its own canonical
   encoder (resolver-sim.hash.canonical)."
   (:require [clojure.test :refer [deftest is testing]]
             [clojure.edn :as edn]
             [resolver-sim.composition.v1 :as v1]))

;; ── Golden vector data source ───────────────────────────────────
;; Consumed as data only: the EDN file is copied from prf-clean-room and
;; read at test time.  No code dependency on prf-clean-room exists.

(def ^:private golden-vectors
  "Lazy stream of clean-room conformance vectors, read as EDN data."
  (delay
   (edn/read-string
    (slurp "etc/conformance/cleanroom/semantic-composition-v1.edn"))))

(defn- vector-by-id [id]
  (some #(when (= id (:case/id %)) %) @golden-vectors))

;; ── Independence assertion ──────────────────────────────────────

(deftest production-module-does-not-require-clean-room
  (testing "The V1 namespace has no reference to prf-clean-room"
    (let [ns-name (str (ns-name *ns*))
          clean-room-refs (filter #(re-find #"clean-room|prf-clean-room" (str %))
                                  (keys (ns-publics 'resolver-sim.composition.v1)))]
      (is (empty? clean-room-refs)
          "V1 module must not reference prf-clean-room"))))

;; ── Golden vector conformance ───────────────────────────────────
;; For every applicable golden vector:
;;   production compact form    == golden compact form
;;   production canonical bytes == golden canonical bytes
;;   production composition-root == golden composition-root

(deftest golden-compact-forms-match
  (doseq [{:keys [case/id source compact]} @golden-vectors]
    (is (= compact (v1/compactly source))
        (str "compact mismatch for " id))))

(deftest golden-canonical-bytes-match
  (doseq [{:keys [case/id compact canonical-bytes]} @golden-vectors]
    (is (= canonical-bytes (v1/canonical-bytes-hex compact))
        (str "canonical-bytes mismatch for " id))))

(deftest golden-composition-roots-match
  (doseq [v @golden-vectors
          :let [id (:case/id v)
                compact (:compact v)
                golden-root (:composition/root v)]]
    (is (= golden-root (v1/composition-root compact))
        (str "composition-root mismatch for " id))))

(deftest golden-identify-returns-compact-and-root
  (testing "identify preserves compactness and root for non-golden sources"
    (let [source {:composition/version 1 :composition/family :ideal-pro-rata
                  :composition/dimensions {:rounding-policy :largest-remainder
                                           :claimant-context [{:account :escrow :direction :add}]}}
          identified (v1/identify source)
          compact (:composition/compact identified)
          root (:composition/root identified)]
      (is (= (v1/compactly source) compact))
      (is (= (v1/composition-root compact) root)))))

;; ── Semantic invariants (independent re-implementation) ───────

(deftest floor-and-carry-normalizes-to-largest-remainder
  (testing ":floor-and-carry and :largest-remainder produce identical compact and root"
  (let [floor-and-carry {:composition/version 1 :composition/family :ideal-pro-rata
                         :composition/dimensions {:rounding-policy :floor-and-carry
                                                    :claimant-contexts [{:account :escrow :direction :add}
                                                                        {:account :escrow :direction :add}]}}
          largest-remainder {:composition/version 1 :composition/family :ideal-pro-rata
                             :composition/dimensions {:rounding-policy :largest-remainder
                                                      :claimant-contexts [{:account :escrow :direction :add}]}}
          a (v1/identify floor-and-carry)
          b (v1/identify largest-remainder)]
      (is (= (:composition/compact a) (:composition/compact b)))
      (is (= (:composition/root a) (:composition/root b))))))

(deftest identical-contexts-collapse-regardless-of-count
  (testing "one, three identical, and non-uniform contexts"
    (let [one {:composition/version 1 :composition/family :ideal-pro-rata
               :composition/dimensions {:rounding-policy :largest-remainder
                                        :claimant-contexts [{:account :escrow :direction :add}]}}
          three {:composition/version 1 :composition/family :ideal-pro-rata
                 :composition/dimensions {:rounding-policy :largest-remainder
                                          :claimant-contexts [{:account :escrow :direction :add}
                                                              {:account :escrow :direction :add}
                                                              {:account :escrow :direction :add}]}}
          non-uniform {:composition/version 1 :composition/family :ideal-pro-rata
                       :composition/dimensions {:rounding-policy :largest-remainder
                                                :claimant-contexts [{:account :escrow :direction :add}
                                                                    {:account :escrow :direction :sub}]}}]
      (is (= (v1/compactly one) (v1/compactly three))
          "identical contexts collapse to one claimant-context")
      (is (= (v1/composition-root (v1/compactly one))
             (v1/composition-root (v1/compactly three))))
      (is (not= (v1/compactly non-uniform) (v1/compactly one))
          "non-uniform contexts remain distinct"))))

(deftest non-uniform-contexts-stay-ordered
  (testing "Context order is material when non-uniform"
    (let [a {:composition/version 1 :composition/family :ideal-pro-rata
             :composition/dimensions {:rounding-policy :largest-remainder
                                      :claimant-contexts [{:account :escrow :direction :add}
                                                          {:account :escrow :direction :sub}]}}
          b {:composition/version 1 :composition/family :ideal-pro-rata
             :composition/dimensions {:rounding-policy :largest-remainder
                                      :claimant-contexts [{:account :escrow :direction :sub}
                                                          {:account :escrow :direction :add}]}}]
      (is (not= (v1/compactly a) (v1/compactly b)))
      (is (not= (v1/composition-root (v1/compactly a))
                (v1/composition-root (v1/compactly b)))))))

(deftest authorisation-all-four-boolean-points-are-distinct
  (testing "All four forbidden? x authorized? combinations produce distinct roots"
    (let [points (for [f [false true] a [false true]]
                   (v1/compactly
                    {:composition/version 1
                     :composition/family :authorisation-usability-classification
                     :composition/dimensions {:forbidden? f :authorized? a}}))
          roots (map v1/composition-root points)]
      (is (= 4 (count (set roots)))
          "all four forbidden?xauthorized? combinations produce distinct roots"))))

(deftest execution-and-diagnostic-keys-rejected
  (testing "compactly rejects :execution keys"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v1/compactly
                  {:composition/version 1 :composition/family :ideal-pro-rata
                   :composition/dimensions {:rounding-policy :floor}
                   :execution {:parallelism 8}}))))
  (testing "compactly rejects :diagnostic keys"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v1/compactly
                  {:composition/version 1 :composition/family :ideal-pro-rata
                   :composition/dimensions {:rounding-policy :floor}
                    :diagnostic {:trace true}})))))

(deftest sequence-order-is-material
  (testing "A then B != B then A for composition-sequence"
    (let [ab (v1/compactly
              {:composition/version 1 :composition/family :composition-sequence
               :composition/dimensions {:purpose :example/components
                                        :components [:component/a :component/b]}})
          ba (v1/compactly
              {:composition/version 1 :composition/family :composition-sequence
               :composition/dimensions {:purpose :example/components
                                        :components [:component/b :component/a]}})]
      (is (not= ab ba))
      (is (not= (v1/composition-root ab) (v1/composition-root ba))))))

(deftest consecutive-relation-is-distinct-from-sequence
  (testing "consecutive-relation != composition-sequence"
    (let [seq-compact (v1/compactly
                       {:composition/version 1 :composition/family :composition-sequence
                        :composition/dimensions {:purpose :example/components
                                                 :components [:component/a :component/b]}})
          con-compact (v1/compactly
                       {:composition/version 1 :composition/family :composition-consecutive-relation
                        :composition/dimensions {:predecessor :state/a :successor :state/b}})]
      (is (not= seq-compact con-compact))
      (is (not= (v1/composition-root seq-compact) (v1/composition-root con-compact))))))

;; ── Production projection tests ────────────────────────────────
;; Show that actual production structures project into V1 compact forms,
;; matching the golden vectors.

(deftest production-ideal-pro-rata-policy-projects-to-golden
  (testing "A production :pro-rata policy with uniform claimant contexts
            projects to the golden ideal-pro-rata compact form"
    (let [prod-policy {:mode :pro-rata :rounding-policy :floor-and-carry}
          prod-contexts [{:account :escrow :direction :add}
                         {:account :escrow :direction :add}]
          v1-source (v1/project-ideal-pro-rata prod-policy prod-contexts)
          compact (v1/compactly v1-source)
          root (v1/composition-root compact)
          golden (vector-by-id :ideal-pro-rata-uniform-context)]
      (is (= (:compact golden) compact))
      (is (= (:canonical-bytes golden) (v1/canonical-bytes-hex compact)))
      (is (= (:composition/root golden) root))
      (is (= (:example-input golden) {:requested {:a 100 :b 100 :c 100} :available 10})
          "golden vector carries example input")))

  (testing ":floor-and-carry compacts to :largest-remainder; :floor stays :floor"
    (let [floor-and-carry (v1/identify
                           (v1/project-ideal-pro-rata
                            {:mode :pro-rata :rounding-policy :floor-and-carry}
                            [{:account :escrow :direction :add}]))
          floor (v1/identify
                 (v1/project-ideal-pro-rata
                  {:mode :pro-rata :rounding-policy :floor}
                  [{:account :escrow :direction :add}]))]
      (is (= :largest-remainder
             (get-in (:composition/compact floor-and-carry)
                     [:composition/dimensions :rounding-policy])))
      (is (= :floor
             (get-in (:composition/compact floor)
                     [:composition/dimensions :rounding-policy])))
      (is (not= (:composition/root floor-and-carry) (:composition/root floor)))))

  (testing "Non-pro-rata modes are rejected by the ideal-pro-rata adapter"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v1/project-ideal-pro-rata
                  {:mode :waterfall :rounding-policy :floor-and-carry}
                  [{:account :escrow :direction :add}])))))

(deftest production-authorisation-facts-project-to-golden
  (testing "Production cancellation evaluation :forbidden projects to
            {:forbidden? true :authorized? false}"
    (let [evaluation {:decision/classification :forbidden
                      :decision/reasons [:missing-scope-hash]}
          forbidden? (= :forbidden (:decision/classification evaluation))
          authorized? (= :authorized (:decision/classification evaluation))
          v1-source (v1/project-authorisation forbidden? authorized?)
          compact (v1/compactly v1-source)]
      (is (= {:forbidden? true :authorized? false}
             (:composition/dimensions compact)))
      (is (re-matches #"[0-9a-f]{64}" (v1/composition-root compact)))))

  (testing "Production cancellation evaluation :authorized projects to
            {:forbidden? false :authorized? true}"
    (let [evaluation {:decision/classification :authorized
                      :decision/reasons []}
          forbidden? (= :forbidden (:decision/classification evaluation))
          authorized? (= :authorized (:decision/classification evaluation))
          compact (v1/compactly
                   (v1/project-authorisation forbidden? authorized?))]
      (is (= {:forbidden? false :authorized? true}
             (:composition/dimensions compact)))))

  (testing "Forbidden-and-authorized (conflict) projects to golden :forbidden-authorized"
    (let [validation-facts {:valid? false
                            :authorized? true
                            :missing-scope-hash? false}
          forbidden? (not (:valid? validation-facts))
          authorized? (:authorized? validation-facts)
          compact (v1/compactly
                   (v1/project-authorisation forbidden? authorized?))
          golden (vector-by-id :forbidden-authorized)]
      (is (= (:compact golden) compact))
      (is (= (:composition/root golden) (v1/composition-root compact))))))

(deftest production-sequence-projects-to-golden
  (testing "Production ordered component list projects to golden :concatenate"
    (let [prod-components [:component/a :component/b]
          v1-source (v1/project-sequence :example/components prod-components)
          compact (v1/compactly v1-source)
          root (v1/composition-root compact)
          golden (vector-by-id :concatenate-ordered-components)]
      (is (= (:compact golden) compact))
      (is (= (:canonical-bytes golden) (v1/canonical-bytes-hex compact)))
      (is (= (:composition/root golden) root)))))

(deftest production-consecutive-relation-projects-to-golden
  (testing "Production predecessor→successor state relation projects to golden :consecutive"
    (let [v1-source (v1/project-consecutive-relation :state/a :state/b)
          compact (v1/compactly v1-source)
          root (v1/composition-root compact)
          golden (vector-by-id :consecutive-predecessor-successor)]
      (is (= (:compact golden) compact))
      (is (= (:canonical-bytes golden) (v1/canonical-bytes-hex compact)))
      (is (= (:composition/root golden) root))))

  (testing "State transition A→B is distinct from B→A"
    (let [ab (v1/composition-root
              (v1/compactly
               (v1/project-consecutive-relation :state/a :state/b)))
          ba (v1/composition-root
              (v1/compactly
               (v1/project-consecutive-relation :state/b :state/a)))]
      (is (not= ab ba)))))

;; ── Canonical encoding independence ────────────────────────────
;; The production encoder (hc/canonical-bytes) is the independent encoder
;; referenced by the architecture.  Its output for V1 compact values must
;; match the golden bytes byte-for-byte.

(deftest production-encoder-matches-canonical-spec
  (testing "Production canonical-bytes-hex matches golden for every vector"
    (doseq [{:keys [case/id compact canonical-bytes]} @golden-vectors]
      (is (= canonical-bytes (v1/canonical-bytes-hex compact))
          (str "encoder mismatch for " id)))))

(deftest composition-root-is-domain-separated
  (testing "The V1 domain tag is prepended to canonical bytes before SHA-256"
    (let [golden (vector-by-id :ideal-pro-rata-uniform-context)
          root (v1/composition-root (:compact golden))]
      (is (= (:composition/root golden) root))
      (is (re-matches #"^[0-9a-f]{64}$" root)
          "composition-root is 64-char lowercase hex, no sha256: prefix"))))
