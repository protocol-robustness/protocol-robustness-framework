(ns resolver-sim.protocols.sew.held-mutation-admission-test
  "End-to-end production-wiring regression for held-mutation admission.

   Proves the CURRENT physical seam (Slice A): with the physical held-custody
   package registered, a real `admit-and-add-held!` for a
   :force-authorisation-override operation carries the exact permit provenance
   into `accounting/add-held`, which performs its own exact-scope verification
   and consumes exactly that permit — producing one held mutation and one
   consumption. The rejected twin proves that a rejected admission changes
   nothing across the protocol-held world surface.

   This is a regression of the current seam, NOT a claim of authoritative
   extension selection (that is Slice C)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.assurance.held-override-selection :as selection]
            [resolver-sim.assurance.held-override-publication :as publication]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.held-mutation-admission :as admission]
            [prf.extensions.held-custody.manifest :as manifest]))

(def override-capability-key
  [:assurance/force-authorisation :held-custody/override-admission-v1])

(def fa-reason :replay-fixture-setup)

(defn- current-head []
  (configuration-head/initial-head (str "sha256:" (apply str (repeat 64 "a"))) 1))

(defn- historical-selection []
  (selection/build-selection override-capability-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root (current-head))))

(defn- scope-map-accounting-derives
  "The exact :authorization/scope accounting derives for a force-auth add-held,
   so the permit can be constructed to reconcile at execution time."
  [id token amount direction reason extra]
  (let [components (held-policy/position-components token reason (or extra {}))
        scope-fields (merge {:authorization/id id
                             :authorization/type :force-authorisation
                             :held/direction direction
                             :token token
                             :amount amount
                             :held/account (:held/account components)
                             :held/position-id (:held/position-id components)
                             :owner/address (:owner/address components)
                             :held/reason reason}
                            (select-keys (or extra {}) [:held/workflow-id]))]
    ;; position-bound? is false when the permit scope omits :held/position-id,
    ;; so accounting dissocs position-id before projecting.
    (held-adjustment/project-held-adjustment-scope
     (dissoc scope-fields :held/position-id))))

(defn- force-auth-permit
  "A force-authorisation grant record whose scope reconciles with what accounting
   derives for the given add-held, so execution verifies and consumes it."
  [id token amount direction reason extra]
  (let [sm (scope-map-accounting-derives id token amount direction reason extra)]
    {:authorization/id id
     :authorization/type :force-authorisation
     :authorization/status :active
     :consumed? false
     :authorization/scope-hash (fa/force-authorisation-scope-hash sm)
     :authorization/scope sm
     :starts-at 0
     :expires-at 1000}))

(defn- extension-resolution []
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        schemas {:prf/held-custody-override-admission-input.v1 root
                 :prf/held-custody-override-admission-result.v1 root
                 :prf/held-custody-override-admission-verification.v1 root}]
    (resolution/resolve-requested
     (registry/register-package (registry/empty-extension-map) manifest/package)
     [override-capability-key]
     {:schemas schemas})))

(defn- world-with-grant [permit]
  (assoc (types/empty-world)
         :force-authorisations {(:authorization/id permit) permit}
         :force-authorisations/consumed {}))

(defn- with-package-registered [f]
  (registry/register-package! manifest/package)
  (try (f)
       (finally (try (registry/unregister-package! manifest/package)
                     (catch Throwable _ nil)))))

(defn- world-equality
  "Project the protocol-held world surface the reviewer requires for equality."
  [world]
  (select-keys world [:total-held
                      :held/positions
                      :held-ledger/index
                      :held-adjustments
                      :force-authorisations
                      :force-authorisations/consumed]))

(deftest override-proceeds-and-consumes-exactly-one-permit
  (with-package-registered
    (fn []
      (let [token :USDC
            amount 100
            extra {:owner/address "0xrecipient" :held/workflow-id 0}
            permit (force-auth-permit "fa-e2e" token amount :in fa-reason extra)
            world (world-with-grant permit)
            admission-opts {:operation-id :held-custody/force-auth-mutation
                            :scope (scope-map-accounting-derives
                                    "fa-e2e" token amount :in fa-reason extra)
                            :permits [permit]
                            :consumption-registry {}
                            :now-ts 500
                            :configuration-head (current-head)
                            :extension-resolution (extension-resolution)
                            :held-override/selection (historical-selection)
                            :held-override/provider-root "sha256:held-custody-pkg"
                            :held-override/capability-key override-capability-key
                            :held-override/capability-version 1}
            world' (admission/admit-and-add-held!
                    world token amount
                    {:action "add-held" :reason fa-reason :extra extra}
                    admission-opts)]
        (testing "exactly one held mutation"
          (is (= 1 (count (:held-adjustments world')))))
        (testing "exactly one permit is consumed"
          (is (= #{"fa-e2e"} (set (keys (:force-authorisations/consumed world'))))))
        (testing "the consumed permit is the exact one selected"
          (is (= "fa-e2e" (get-in world' [:force-authorisations/consumed "fa-e2e" :authorization/id]))))
        (testing "the mutation committed the authorization provenance"
          (is (= :force-authorisation
                 (get-in world' [:held-adjustments 0 :authorization/provenance :authorization/type]))))
        (testing "total-held reflects the ingress"
          (is (= amount (get-in world' [:total-held token]))))
        (testing "the successor state commits the lineage root and retains the historical bodies"
          (is (string? (:held-override/lineage-root world')))
          (is (contains? (:held-override/bodies world') (:held-override/lineage-root world'))))
        (testing "historically auditable END-TO-END from the authoritative result only"
          (let [audit (publication/audit-from-successor world')]
            (is (true? (:audited? audit))
                (pr-str audit))))))))

(deftest rejected-admission-changes-nothing
  (with-package-registered
    (fn []
      (testing "override disabled in authoritative extension-resolution -> reject, no mutation/consumption"
        (let [token :USDC
              amount 100
              extra {:owner/address "0xrecipient" :held/workflow-id 0}
              permit (force-auth-permit "fa-reject" token amount :in fa-reason extra)
              world (world-with-grant permit)
              before (world-equality world)
              threw? (atom false)
              ex (try
                   (admission/admit-and-add-held!
                    world token amount
                    {:action "add-held" :reason fa-reason :extra extra}
                    {:operation-id :held-custody/force-auth-mutation
                     :scope (scope-map-accounting-derives
                             "fa-reject" token amount :in fa-reason extra)
                     :permits [permit]
                     :consumption-registry {}
                     :now-ts 500
                     :configuration-head {}                        ; invalid head -> override disabled
                     :extension-resolution (extension-resolution)})
                   (catch clojure.lang.ExceptionInfo e
                     (reset! threw? true)
                     e))]
          (is (true? @threw?) "reject throws before mutation")
          (is (= :held-custody/admission-rejected (:type (ex-data ex))))
          (testing "protocol-held world surface is unchanged"
            (is (= before (world-equality world))))))
      (testing "a consumed permit -> reject, no new mutation/consumption"
        (let [token :USDC
              amount 100
              extra {:owner/address "0xrecipient" :held/workflow-id 0}
              permit (force-auth-permit "fa-consumed" token amount :in fa-reason extra)
              consumed (assoc permit :consumed? true)
              world (world-with-grant consumed)
              before (world-equality world)
              threw? (atom false)]
          (try
            (admission/admit-and-add-held!
             world token amount
             {:action "add-held" :reason fa-reason :extra extra}
             {:operation-id :held-custody/force-auth-mutation
              :scope (scope-map-accounting-derives
                      "fa-consumed" token amount :in fa-reason extra)
              :permits [consumed]
              :consumption-registry {}
              :now-ts 500
              :configuration-head (current-head)
              :extension-resolution (extension-resolution)})
            (catch clojure.lang.ExceptionInfo _
              (reset! threw? true)))
          (is (true? @threw?) "consumed permit rejects before mutation")
          (is (= before (world-equality world))))))))