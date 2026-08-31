(ns resolver-sim.assurance.held-override-lineage-test
  "HELD_OVERRIDE_LINEAGE_CONSERVATION.

   Pins the temporal/replay invariant: an authoritatively force-authorisation-
   override held mutation conserves an exact lineage from the PREDECESSOR
   authoritative configuration through selection, resolution, provider/capability,
   permit, held adjustment, and consumption — with no current-state lookup and no
   caller-asserted substitution during replay.

   Replay must use the HISTORICAL authoritative selection snapshot that was
   current for that transition, never 'which provider is selected now?'."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.assurance.held-override-selection :as selection]
            [resolver-sim.assurance.held-override-lineage :as lineage]))

(def override-key [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn- scope [dir amt]
  {:authorization/id "permit-hist"
   :authorization/type :force-authorisation
   :held/direction dir
   :token :USDC
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- permit-for [s]
  {:authorization/id (:authorization/id s)
   :authorization/type :force-authorisation
   :authorization/status :consumed
   :consumed? true
   :authorization/scope-hash (fa/force-authorisation-scope-hash
                              (fa/normalize-force-authorisation-scope s))
   :authorization/scope (fa/normalize-force-authorisation-scope s)})

(defn- head-at [n]
  (configuration-head/initial-head
   (str "sha256:" (apply str (repeat 64 (str n)))) 1))

(def historical-head (head-at 1))
(def current-head (head-at 2))

(defn- historical-selection []
  (selection/build-selection override-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root historical-head)))

(defn- historical-resolution []
  (let [root (str "sha256:" (apply str (repeat 64 "a")))]
    {:extensions/resolution-version 1
     :extensions/packages {}
     :extensions/capabilities
     {override-key {:capability/kind :assurance/force-authorisation
                    :capability/id :held-custody/override-admission-v1
                    :capability/version 1}}
     :extensions/capability-providers {}
     :extensions/dependencies []
     :extensions/schema-roots {}
     :extensions/effect-schema-roots {}
     :extensions/runtime-profile {}
     :extensions/resolution-root root}))

(defn- base-inputs []
  {:predecessor-configuration-head historical-head
   :extension-selection (historical-selection)
   :extension-resolution (historical-resolution)
   :provider-package-root "sha256:held-custody-pkg"
   :capability-key override-key
   :capability-version 1
   :permit (permit-for (scope :in 100))
   :held-adjustment {:artifact/hash "sha256:adj-historical"}
   :consumption-record {:authorization/id "permit-hist" :consumed? true}
   :successor-state-root "sha256:successor-state"})

(deftest lineage-conserves-exactly-with-historical-inputs
  (testing "a lineage built from the historical inputs is exactly conserved"
    (let [inputs (base-inputs)
          l (lineage/build-lineage inputs)]
      (is (true? (:conserved? (lineage/verify-lineage (assoc inputs :lineage l)))))))
  (testing "the lineage self-root recomputes (rooted, not asserted)"
    (let [l (lineage/build-lineage (base-inputs))]
      (is (= (:lineage/root l) (lineage/lineage-root l))))
    (testing "tampering any committed root breaks conservation"
      (let [l (lineage/build-lineage (base-inputs))
            tampered (assoc l :lineage/permit-scope-root (str "sha256:" (apply str (repeat 64 "f"))))]
        (is (false? (:conserved? (lineage/verify-lineage
                                  (assoc (base-inputs) :lineage tampered)))))))))

(deftest replay-cannot-substitute-current-selection-or-head
  (testing "replay using the CURRENT head (different from the historical predecessor)
            breaks conservation"
    (let [inputs (base-inputs)
          l (lineage/build-lineage inputs)]
      (is (false? (:conserved?
                   (lineage/verify-lineage
                    (assoc inputs :lineage l
                           :predecessor-configuration-head current-head))))
          "a current head substituted for the historical predecessor diverges the root")))
  (testing "replay using the CURRENT selection (built against the current head) breaks
            conservation"
    (let [inputs (base-inputs)
          l (lineage/build-lineage inputs)
          current-selection (selection/build-selection
                             override-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root current-head))]
      (is (false? (:conserved?
                   (lineage/verify-lineage
                    (assoc inputs :lineage l :extension-selection current-selection))))
          "current extension selection cannot be substituted for the historical one")))
  (testing "lineage is anchored to a predecessor (historical) head, never 'current now'"
    (is (true? (lineage/lineage-uses-predecessor? (lineage/build-lineage (base-inputs)))))))

(deftest no-current-state-lookup-or-caller-substitution
  (testing "verify-lineage is a pure function of the supplied (historical) inputs and
            the lineage; it never queries current extension/selection state"
    (let [inputs (base-inputs)
          l (lineage/build-lineage inputs)]
      (is (true? (:conserved? (lineage/verify-lineage (assoc inputs :lineage l))))))
    (testing "a caller cannot assert a forged lineage root as valid — the root is
              recomputed, not trusted"
      (let [inputs (base-inputs)
            forged (assoc (lineage/build-lineage inputs)
                          :lineage/predecessor-head-root
                          (str "sha256:" (apply str (repeat 64 "0"))))]
        (is (false? (:conserved? (lineage/verify-lineage (assoc inputs :lineage forged)))))))))

(deftest lineage-completeness
  "HELD_OVERRIDE_LINEAGE_COMPLETENESS: override mutation exists iff an exact valid,
   retained, discoverable, body-complete lineage exists."
  (let [l (lineage/build-lineage (base-inputs))
        inputs (assoc (base-inputs) :lineage l)]
    (testing "mutation + valid + retained + discoverable + bodies -> complete"
      (is (true? (:complete?
                  (lineage/verify-lineage-completeness
                   (assoc inputs :mutation-exists? true
                          :lineage-retained? true
                          :lineage-discoverable? true
                          :bodies-retained? true))))))
    (testing "committed override WITHOUT a retained lineage -> incomplete"
      (is (false? (:complete?
                   (lineage/verify-lineage-completeness
                    (assoc inputs :mutation-exists? true
                           :lineage-retained? false
                           :lineage-discoverable? false
                           :bodies-retained? false))))))
    (testing "retained lineage for effects NOT committed -> incomplete"
      (is (false? (:complete?
                   (lineage/verify-lineage-completeness
                    (assoc inputs :mutation-exists? false
                           :lineage-retained? true
                           :lineage-discoverable? true
                           :bodies-retained? true))))))
    (testing "missing historical body -> incomplete"
      (is (false? (:complete?
                   (lineage/verify-lineage-completeness
                    (assoc inputs :mutation-exists? true
                           :lineage-retained? true
                           :lineage-discoverable? true
                           :bodies-retained? false))))))))

(deftest state-after-binding
  "HELD_OVERRIDE_STATE_AFTER_BINDING: the lineage's consequence roots must equal
   exactly what the published successor state commits; a lineage transplanted onto
   a different successor fails."
  (let [l (lineage/build-lineage (base-inputs))
        succ-root (:lineage/successor-root l)
        adj-root (:lineage/adjustment-root l)
        cons-root (:lineage/consumption-root l)
        pred-root (:lineage/predecessor-head-root l)]
    (testing "binding holds when the successor state commits the exact lineage roots"
      (is (true? (:bound?
                  (lineage/verify-state-after-binding
                   {:lineage l
                    :predecessor-state-root pred-root
                    :successor-state-root succ-root
                    :successor-adjustment-root adj-root
                    :successor-consumption-root cons-root})))))
    (testing "a lineage transplanted onto a DIFFERENT successor state fails binding"
      (is (false? (:bound?
                   (lineage/verify-state-after-binding
                    {:lineage l
                     :predecessor-state-root pred-root
                     :successor-state-root (str "sha256:" (apply str (repeat 64 "c")))
                     :successor-adjustment-root adj-root
                     :successor-consumption-root cons-root})))))
    (testing "a lineage naming effects from another execution fails binding"
      (is (false? (:bound?
                   (lineage/verify-state-after-binding
                    {:lineage l
                     :predecessor-state-root pred-root
                     :successor-state-root succ-root
                     :successor-adjustment-root (str "sha256:" (apply str (repeat 64 "d")))
                     :successor-consumption-root (str "sha256:" (apply str (repeat 64 "e")))})))))))