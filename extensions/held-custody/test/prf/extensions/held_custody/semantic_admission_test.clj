(ns prf.extensions.held-custody.semantic-admission-test
  "Tests for the semantic-operation admission boundary and the production
   add-held reachability / architecture gate.

   Establishes:
     - admission class is pinned to SEMANTIC OPERATION identity, never the bare
       action name (the add-held caveat is resolved explicitly);
     - the SINGLE production admission decision (`admit-held-mutation`) is the
       structural enforcement point: only :proceed classifications mutate or
       consume; rejected classifications change nothing;
     - an unknown operation fails closed to :never-overrideable;
     - an architecture gate fails if a NEW raw low-level held-ingress callsite
       is introduced in Sew without being sanctioned / routed through the
       admission boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]
            [prf.extensions.held-custody.semantic-admission :as admission]
            [prf.extensions.held-custody.manifest :as manifest]))

(def override-capability-key
  [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn- scope [direction amt]
  {:authorization/id "permit-fa"
   :authorization/type :force-authorisation
   :held/direction direction
   :token :USDC
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- permit-for [s]
  {:authorization/id (:authorization/id s)
   :authorization/type :force-authorisation
   :authorization/status :active
   :consumed? false
   :authorization/scope-hash (fa/force-authorisation-scope-hash
                              (fa/normalize-force-authorisation-scope s))
   :authorization/scope (fa/normalize-force-authorisation-scope s)
   :starts-at 0
   :expires-at 1000})

(def fa-scope (scope :in 100))
(def fa-permit (permit-for fa-scope))
(def fa-permit-2 (assoc (permit-for fa-scope) :authorization/id "permit-fa-second"))

(defn- current-head []
  (configuration-head/initial-head (str "sha256:" (apply str (repeat 64 "a"))) 1))

(defn- override-resolution []
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        schemas {:prf/held-custody-override-admission-input.v1 root
                 :prf/held-custody-override-admission-result.v1 root
                 :prf/held-custody-override-admission-verification.v1 root}]
    (resolution/resolve-requested
     (registry/register-package (registry/empty-extension-map) manifest/package)
     [override-capability-key]
     {:schemas schemas})))

(defn- extension-resolution []
  (override-resolution))

(defn- extension-resolution-disabled []
  (assoc-in (override-resolution)
            [:resolution :extensions/capabilities]
            (dissoc (:extensions/capabilities (:resolution (override-resolution)))
                    override-capability-key)))

;; ── semantic operation classification resolves the add-held caveat ──────────

(deftest admission-class-is-pinned-to-semantic-operation-not-action
  (testing "the SAME :add-held action maps to different classes by operation id"
    (is (= :force-authorisation-override
           (admission/semantic-operation-class :held-custody/force-auth-mutation)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/escrow-principal-deposited)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/appeal-bond-posted)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/resolver-yield-accrued)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/deferred-yield-reserved)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/yield-accrued)))
    (is (= :ordinary
           (admission/semantic-operation-class :sew/bounty-custody-reserve))))
  (testing "every classified add-held operation is documented as such"
    (is (every? #(= :add-held (:action (admission/semantic-operation %)))
                (keys admission/semantic-operation-classes))))
  (testing "an unknown operation fails closed to :never-overrideable"
    (is (= :never-overrideable (admission/semantic-operation-class :some/new-operation)))))

;; ── single production admission decision ────────────────────────────────────

(deftest ordinary-ingress-proceeds-without-force-permit
  (doseq [op [:sew/escrow-principal-deposited :sew/appeal-bond-posted
              :sew/resolver-yield-accrued :sew/deferred-yield-reserved
              :sew/yield-accrued]]
    (let [d (admission/admit-held-mutation
             {:operation-id op :scope fa-scope :permits []})]
      (is (= :proceed-ordinary (:admission d)) (str "ordinary: " op))
      (is (= :ordinary (:semantic-operation-class d))))))

(deftest force-authorisation-override-exact-permit-proceeds
  (let [d (admission/admit-held-mutation
           {:operation-id :held-custody/force-auth-mutation
            :scope fa-scope :permits [fa-permit]
            :consumption-registry {} :now-ts 500
            :configuration-head (current-head)
            :extension-resolution (extension-resolution)})]
    (is (= :proceed-force-authorised (:admission d)))
    (is (= :forbidden-authorized (:classification d)))
    (is (= fa-permit (:permit d))
        "the exact usable permit is the provenance supplied to accounting")))

(deftest force-authorisation-override-rejects-before-mutation
  (testing "no usable permit -> reject :forbidden"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits []
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :reject (:admission d)))
      (is (= :forbidden (:classification d)))
      (is (some #{:missing-force-authorisation} (:blocking-reasons d)))))
  (testing "more than one usable exact permit -> :ambiguous-force-authorisation"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits [fa-permit fa-permit-2]
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :reject (:admission d)))
      (is (= :ambiguous-force-authorisation (:classification d)))
      (is (= 2 (:usable-permit-count d)))))
  (testing "override disabled in authoritative configuration -> reject :forbidden"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits [fa-permit]
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution-disabled)})]
      (is (= :reject (:admission d)))
      (is (= :forbidden (:classification d)))
      (is (some #{:force-authorisation-override-disabled} (:blocking-reasons d)))))
  (testing "consumed permit -> reject before mutation"
    (let [consumed (assoc fa-permit :consumed? true)
          d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits [consumed]
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :reject (:admission d)))
      (is (= :forbidden (:classification d)))
      (is (some #{:authorisation-already-consumed} (:blocking-reasons d))))))

(deftest never-overrideable-rejects-always
  (let [d (admission/admit-held-mutation
           {:operation-id :some/unknown-operation :scope fa-scope
            :permits [fa-permit]
            :configuration-head (current-head)
            :extension-resolution (extension-resolution)})]
    (is (= :reject (:admission d)))
    (is (= :never-overrideable (:classification d)))))

;; ── atomicity regressions ───────────────────────────────────────────────────

(deftest rejected-classification-mutates-nothing
  (testing "a reject decision carries no permit to consume and no proceed marker"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits []
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :reject (:admission d)))
      (is (not (contains? d :permit)))
      (is (not= :proceed-force-authorised (:admission d)))))
  (testing "the gate is pure: a rejected decision never mutates world or consumes"
    (let [world {} 
          d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits []
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :reject (:admission d)))
      (is (= world {}))))) ; admission is a pure decision; no world/consumption side effects

(deftest proceed-force-authorised-identifies-exactly-one-permit
  (testing "a proceed decision names exactly one exact permit for consumption"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope fa-scope :permits [fa-permit]
              :consumption-registry {} :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})]
      (is (= :proceed-force-authorised (:admission d)))
      (is (= (:authorization/id fa-permit) (:authorization/id (:permit d)))))))

;; ── architecture / reachability gate ────────────────────────────────────────

(def ^:private sew-root "protocols_src/resolver_sim/protocols/sew")

(def ^:private low-level-call-re
  "A raw low-level held-ingress call: (acct/add-held ... or (accounting/add-held ...
   (optionally namespace-qualified before the alias)."
  #"\([A-Za-z0-9._/]+/add-held\b")

(def ^:private sanctioned-low-level-files
  "Files that MAY contain a raw low-level add-held CALL. After production wiring,
   this is only the designated admission boundary (held_mutation_admission.clj)
   plus the accounting implementation that owns the primitive (accounting.clj).
   lifecycle.clj / resolution.clj / pro_rata_application.clj / with_bounty.clj
   must route held ingress through the admission boundary, NOT call the primitive
   directly. The previous multi-file sanctioned list was TRANSITIONAL and is now
   retired: any producer file that reintroduces a raw add-held call fails here."
  #{"held_mutation_admission.clj" "accounting.clj"})

(defn- repo-root
  "Walk up from the CWD to the repository root (a directory containing
   protocols_src/), so the gate is robust to the working directory."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (if (and dir (.isDirectory dir) (.exists (io/file dir "protocols_src")))
      (.getCanonicalPath dir)
      (when-let [parent (.getParentFile dir)]
        (recur parent)))))

(defn- raw-add-held-callers
  "The set of Sew source files that call the low-level add-held primitive directly."
  [root]
  (let [all-sew (->> (io/file (str root "/" sew-root))
                     (.listFiles)
                     (filter #(.isFile %))
                     (map #(.getName %))
                     (filter #(str/ends-with? % ".clj")))]
    (set (for [f all-sew
               :let [text (slurp (str root "/" sew-root "/" f))]
               :when (re-seq low-level-call-re text)]
           f))))

(deftest raw-low-level-held-ingress-only-from-the-admission-boundary
  "Architecture gate: after production wiring, raw `add-held` is callable only
   from the designated accounting/admission boundary, NOT directly from
   lifecycle / resolution / pro-rata / bounty. A producer file that reintroduces
   a raw `(acct/add-held ...)` call fails this gate — catching a silent bypass."
  (let [root (repo-root)
        callers (raw-add-held-callers root)]
    (testing "every raw low-level add-held caller is the sanctioned boundary"
      (is (empty? (remove sanctioned-low-level-files callers))
          "a raw low-level held-ingress call appeared outside the admission
           boundary; route it through resolver-sim.protocols.sew.held-mutation-admission"))
    (testing "the production producers do NOT call the primitive directly (transitional
              multi-file list retired)"
      (doseq [f ["lifecycle.clj" "resolution.clj" "pro_rata_application.clj"
                 "with_bounty.clj"]]
        (is (not (contains? callers f))
            (str f " must route held ingress through the admission boundary, not raw add-held"))))
    (testing "the boundary is actually enforcing (the admission wrapper owns the call)"
      (is (contains? callers "held_mutation_admission.clj")))))

(deftest unknown-operation-is-never-overrideable
  (testing "a future/unknown semantic operation cannot execute via any permit"
    (is (= :never-overrideable
           (:semantic-operation-class
            (admission/admit-held-mutation
             {:operation-id :some/unregistered :scope fa-scope
              :permits [fa-permit]
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)}))))))