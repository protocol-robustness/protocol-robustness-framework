(ns resolver-sim.protocols.sew.financial.compound-frame-correspondence-test
  "Compound execution ↔ replay frame-stream correspondence.

   The lifecycle module produces canonical transition evidence (compound root,
   member sequence root, execution lineage). The replay frame-stream is a
   CONSUMER projection of that evidence — the two are independently
   reconstructible views of the same execution. This suite proves the
   correspondence validator establishes:

     frame count          == compound member count
     frame[i].state-before/root == lineage-step[i].state-before/root
     frame[i].state-after/root  == lineage-step[i].state-after/root
     lineage-step[i].action/root == compound-member[i].action-root

   plus the existing frame adjacency, WITHOUT recomputing the execution-lineage
   root from the frame stream (the lineage remains the authority)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay.frames :as frames]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.lifecycle :as fl]
            [resolver-sim.protocols.sew.types :as t]))

(def policy (fl/default-policy))

(defn- solvent-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 1000})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 1000 :escrow-state :pending})))

(defn- assess [world] (solv/classify-solvency world))

(defn- chain-for [world]
  (get-in (fl/apply-verified :protocol-a [] (assess world) {})
          [:state :episode/events]))

(defn- member-exec [state action]
  (let [params (:action/params action)]
    (case (:action/type action)
      :allow-recapitalization (update-in state [:total-held :USDC]
                                         + (long (:amount params)))
      :withdraw (update-in state [:total-held :USDC]
                           - (long (:amount params))))))

(defn- run-compound
  "Execute a 3-member compound and return the committed evidence."
  []
  (let [pre (solvent-world)
        c {:action/type :action/compound
           :compound/members [{:member/request-id "r0"
                               :member/action {:action/type :allow-recapitalization
                                               :action/params {:amount 100}}}
                              {:member/request-id "r1"
                               :member/action {:action/type :allow-recapitalization
                                               :action/params {:amount 50}}}
                              {:member/request-id "r2"
                               :member/action {:action/type :withdraw
                                               :action/params {:amount 30}}}]
           :compound/pre-state-root (fl/domain-state-root pre)
           :compound/atomicity :all-or-nothing}
        d (fl/response-decision :protocol-a (chain-for pre) (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        n (fl/normalize-action c)
        result (fl/authorize-and-execute-compound
                d c "compound-1" pre head-root :protocol-a #{} member-exec)]
    (when-not (:ok? result)
      (throw (ex-info "compound execution failed" result)))
    {:compound c
     :normalized n
     :member-action-roots (mapv :member/action-root (:compound/members n))
     :lineage (:lineage result)
     :transition (:transition result)}))

(defn- frame-stream-from-lineage
  "Project a compound execution lineage into a replay frame-stream (a consumer
   projection of the canonical transition evidence). State roots come from the
   lineage; event/transition/basis roots are opaque execution material the
   correspondence validator does not compare."
  [lineage]
  (let [steps (:lineage/steps lineage)
        [_prev-root frames]
        (reduce (fn [[prev-root frames] [index step]]
                  (let [f (frames/build-frame
                           index prev-root
                           {:result :ok
                            :transition-assurance
                            {:capability :transition-assurance/v1
                             :status :passed
                             :state-before/root (:state-before/root step)
                             :state-after/root (:state-after/root step)
                             :event/root (apply str (repeat 64 \e))
                             :transition/root (apply str (repeat 64 \t))
                             :basis/root (apply str (repeat 64 \b))}})]
                    [(:frame/root f) (conj frames f)]))
                [nil []]
                (map-indexed vector steps))]
    {:frame/status :ok :frames frames}))

;; ── The projection is an internally consecutive frame stream ────────────────

(deftest compound-lineage-projects-to-a-valid-frame-stream
  (let [{:keys [lineage]} (run-compound)
        stream (frame-stream-from-lineage lineage)
        validation (frames/validate-frame-lineage stream)]
    (is (:valid? validation)
        "the projected stream is internally consecutive (frame adjacency holds)")
    (is (= (:lineage/step-count lineage) (count (:frames stream)))
        "frame count == compound member count")))

;; ── Correspondence holds for a real execution ───────────────────────────────

(deftest correspondence-holds-for-a-real-execution
  (let [{:keys [member-action-roots lineage transition]} (run-compound)
        stream (frame-stream-from-lineage lineage)
        result (frames/validate-compound-frame-correspondence
                {:frame-stream stream
                 :lineage lineage
                 :member-action-roots member-action-roots})]
    (is (true? (:correspondence/valid? result))
        "the frame stream is an exact projection of the execution")
    (is (empty? (:findings result)))
    (is (= 3 (count (:frames stream))))
    (doseq [[i step] (map-indexed vector (:lineage/steps lineage))]
      (is (= (:state-before/root step)
             (get-in stream [:frames i :state-before/root]))
          (str "frame[" i "].state-before/root == lineage-step[" i "].state-before/root"))
      (is (= (:state-after/root step)
             (get-in stream [:frames i :state-after/root]))
          (str "frame[" i "].state-after/root == lineage-step[" i "].state-after/root"))
      (is (= (nth member-action-roots i) (:action/root step))
          (str "lineage-step[" i "].action/root == compound-member[" i "].action-root")))
    (is (= (:transition/execution-lineage-root transition) (:lineage/root lineage))
        "the committed transition lineage-root is the same authority the validator reports")))

;; ── Correspondence detects tampering ────────────────────────────────────────

(deftest correspondence-detects-tampered-frame-state
  (let [{:keys [member-action-roots lineage]} (run-compound)
        stream (frame-stream-from-lineage lineage)
        forged (assoc-in stream [:frames 1 :state-after/root]
                        (apply str (repeat 64 \0)))
        result (frames/validate-compound-frame-correspondence
                {:frame-stream forged :lineage lineage :member-action-roots member-action-roots})]
    (is (false? (:correspondence/valid? result)))
    (is (some #(= :frame-state-after-root-mismatch (:finding/type %)) (:findings result)))
    (is (some #(= 1 (:index %)) (:findings result))
        "the forged step is named by index")))

(deftest correspondence-detects-wrong-member-roots
  (let [{:keys [member-action-roots lineage]} (run-compound)
        stream (frame-stream-from-lineage lineage)
        reordered (vec (reverse member-action-roots))
        result (frames/validate-compound-frame-correspondence
                {:frame-stream stream :lineage lineage :member-action-roots reordered})]
    (is (false? (:correspondence/valid? result))
        "reordering the committed member roots breaks correspondence")
    (is (some #(= :frame-action-root-mismatch (:finding/type %)) (:findings result)))))

(deftest correspondence-rejects-non-consecutive-stream
  (let [{:keys [member-action-roots lineage]} (run-compound)
        stream (frame-stream-from-lineage lineage)
        ;; break internal consecutiveness: frame[1].state-before/root != frame[0].state-after/root
        broken (assoc-in stream [:frames 1 :state-before/root]
                         (apply str (repeat 64 \f)))
        result (frames/validate-compound-frame-correspondence
                {:frame-stream broken :lineage lineage :member-action-roots member-action-roots})]
    (is (false? (:correspondence/valid? result)))
    (is (some #(= :frame-stream-not-internally-valid (:finding/type %)) (:findings result))
        "correspondence requires the stream to already be internally consecutive")))

(deftest correspondence-never-derives-lineage-root-from-frames
  (testing "the execution-lineage-root is reported as the committed authority, never
            recomputed from the (possibly tampered) frame stream"
    (let [{:keys [member-action-roots lineage]} (run-compound)
          good (frame-stream-from-lineage lineage)
          forged (assoc-in good [:frames 2 :state-after/root] (apply str (repeat 64 \0)))
          good-result (frames/validate-compound-frame-correspondence
                       {:frame-stream good :lineage lineage :member-action-roots member-action-roots})
          forged-result (frames/validate-compound-frame-correspondence
                         {:frame-stream forged :lineage lineage :member-action-roots member-action-roots})]
      (is (true? (:correspondence/valid? good-result)))
      (is (false? (:correspondence/valid? forged-result)))
      (is (= (:lineage/root lineage) (:compound/lineage-root good-result)))
      (is (= (:lineage/root lineage) (:compound/lineage-root forged-result))
          "tampering the frames does not change the reported committed lineage-root —
           it remains the EXPECTED authority, not a validation output; a failed
           correspondence still reports the same authoritative root")
      (is (seq (:findings forged-result))
          "a failed result reports findings while still exposing the expected root"))))