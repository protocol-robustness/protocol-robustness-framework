(ns resolver-sim.contract-model.replay.frames
  "Canonical accepted-frame commitments for sequential assured replay."
  (:require [resolver-sim.hash.canonical :as hc]))

(def frame-schema :prf/replay-frame.v1)

(defn- assurance-keys [assurance]
  (select-keys assurance [:capability :status :transition/root :basis/root]))

(defn frame-eligible?
  [entry]
  (and (= :ok (:result entry))
       (= :passed (get-in entry [:transition-assurance :status]))))

(defn build-frame
  [frame-index previous-root entry]
  (let [assurance (:transition-assurance entry)
        frame {:frame/schema frame-schema
               :frame/index frame-index
               :state-before/root (:state-before/root assurance)
               :state-after/root (:state-after/root assurance)
               :event/root (:event/root assurance)
               :transition {:transition/root (:transition/root assurance)
                            :basis/root (:basis/root assurance)}
               :assurance (assurance-keys assurance)
               :frame/previous-root previous-root}
        root (hc/hash-with-intent {:hash/intent :replay-frame} frame)]
    (assoc frame :frame/root root)))

(defn accepted-frames
  "Build the sequential accepted-frame chain for a replay result.
   Deterministic batch replay intentionally has no frame contract in v1."
  [result]
  (if (= :deterministic-batch (get-in result [:execution :mode]))
    {:frame/status :unsupported
     :reason :frame/deterministic-batch-contract-not-defined}
    {:frame/status :ok
     :frames (second
              (reduce (fn [[previous-root frames] entry]
                        (if (frame-eligible? entry)
                          (let [frame (build-frame (count frames) previous-root entry)]
                            [(:frame/root frame) (conj frames frame)])
                          [previous-root frames]))
                      [nil []]
                      (:trace result)))}))

(defn frame-at [frame-stream index]
  (get-in frame-stream [:frames index]))

(defn previous-frame [frame-stream index]
  (when (pos? index) (frame-at frame-stream (dec index))))

(defn next-frame [frame-stream index]
  (frame-at frame-stream (inc index)))

(defn reconstruct-transition [frame]
  {:state-before/root (:state-before/root frame)
   :state-after/root (:state-after/root frame)
   :event/root (:event/root frame)
   :transition/root (get-in frame [:transition :transition/root])
   :basis/root (get-in frame [:transition :basis/root])
   :assurance (:assurance frame)})

(defn- frame-root [frame]
  (hc/hash-with-intent {:hash/intent :replay-frame} (dissoc frame :frame/root)))

(defn validate-frame-lineage
  "Validate a sequential frame stream, optionally against external anchors.
   Answers ONE question: is this frame stream INTERNALLY CONSECUTIVE? It does
   not know about compound actions; compound↔frame correspondence is a separate
   validator (validate-compound-frame-correspondence)."
  ([frame-stream] (validate-frame-lineage frame-stream {}))
  ([frame-stream {:keys [expected-state-before-root expected-head-frame-root
                         expected-final-state-root]}]
   (let [frames (:frames frame-stream)
         anchor-violations (cond-> []
                             (and expected-state-before-root
                                  (not= expected-state-before-root
                                        (:state-before/root (first frames))))
                             (conj {:reason :frame/initial-state-root-mismatch})
                             (and expected-head-frame-root
                                  (not= expected-head-frame-root (:frame/root (last frames))))
                             (conj {:reason :frame/head-root-mismatch})
                             (and expected-final-state-root
                                  (not= expected-final-state-root
                                        (:state-after/root (last frames))))
                             (conj {:reason :frame/final-state-root-mismatch}))
         frame-violations (vec
                           (mapcat (fn [[index frame]]
                                     (cond-> []
                                       (not= frame-schema (:frame/schema frame))
                                       (conj {:reason :frame/invalid-schema :frame/index index})
                                       (not= index (:frame/index frame))
                                       (conj {:reason :frame/non-contiguous-index :frame/index index})
                                       (not= (frame-root frame) (:frame/root frame))
                                       (conj {:reason :frame/root-mismatch :frame/index index})
                                       (and (pos? index)
                                            (not= (:frame/root (nth frames (dec index)))
                                                  (:frame/previous-root frame)))
                                       (conj {:reason :frame/previous-root-mismatch :frame/index index})
                                       (and (pos? index)
                                            (not= (:state-after/root (nth frames (dec index)))
                                                  (:state-before/root frame)))
                                       (conj {:reason :frame/state-lineage-mismatch :frame/index index})))
                                   (map-indexed vector frames)))
         violations (vec (concat (when-not (= :ok (:frame/status frame-stream))
                                   [{:reason :frame/unsupported-stream}])
                                 anchor-violations frame-violations))]
     {:valid? (empty? violations) :violations violations})))

(defn validate-compound-frame-correspondence
  "Validate that a valid replay frame stream is an EXACT state-lineage projection
   of a compound execution's canonical transition evidence — WITHOUT redefining
   either identity:

     count(frames) == count(lineage-steps) == count(member-action-roots)
     frame[i].state-before/root  == lineage-step[i].state-before/root
     frame[i].state-after/root   == lineage-step[i].state-after/root
     lineage-step[i].action/root == member-action-roots[i]

   Frame adjacency (frame[i].state-after/root == frame[i+1].state-before/root)
   is established by validate-frame-lineage — the stream must already be a
   valid, internally consecutive frame stream. The lifecycle execution lineage
   REMAINS the authority: the execution-lineage-root is never recomputed from
   the frame stream (frames are a consumer projection, not execution authority).

   RESULT SHAPE (read carefully):
     {:correspondence/valid? bool
      :findings             [{:finding/type kw :index i ...} ...]
      :compound/lineage-root <hex>}

   :compound/lineage-root is the COMMITTED authoritative root that the supplied
   frames were checked against. Reporting it on a FAILED result is an expected
   value — it is NOT an attestation that the frames correspond to it. The
   invariant: asserting an expected root is not the same as certifying the
   supplied frames match it; only :correspondence/valid? asserts that.

   Args (plain data — this validator has no dependency on the lifecycle module):
     :frame-stream         the replay frame stream
     :lineage              {:lineage/steps [{:step/index i
                                             :state-before/root hex
                                             :action/root hex
                                             :state-after/root hex} ...]
                            :lineage/root hex}
     :member-action-roots  [hex ...] committed member action roots, in order"
  [{:keys [frame-stream lineage member-action-roots]}]
  (let [frames (:frames frame-stream)
        lineage-steps (:lineage/steps lineage)
        lineage-valid (validate-frame-lineage frame-stream)
        cardinality-ok? (= (count frames) (count lineage-steps) (count member-action-roots))
        step-findings (vec
                       (mapcat (fn [index]
                                 (let [frame (nth frames index)
                                       step (nth lineage-steps index)
                                       member-root (nth member-action-roots index)]
                                   (cond-> []
                                     (not= (:state-before/root frame) (:state-before/root step))
                                     (conj {:finding/type :frame-state-before-root-mismatch
                                            :index index})
                                     (not= (:state-after/root frame) (:state-after/root step))
                                     (conj {:finding/type :frame-state-after-root-mismatch
                                            :index index})
                                     (not= (:action/root step) member-root)
                                     (conj {:finding/type :frame-action-root-mismatch
                                            :index index}))))
                               (range (min (count frames) (count lineage-steps)))))
        findings (cond-> []
                   (not (:valid? lineage-valid))
                   (conj {:finding/type :frame-stream-not-internally-valid
                          :findings (:violations lineage-valid)})
                   (not cardinality-ok?)
                   (conj {:finding/type :frame-cardinality-mismatch
                          :frame-count (count frames)
                          :lineage-step-count (count lineage-steps)
                          :member-count (count member-action-roots)})
                   true (into step-findings))]
    {:correspondence/valid? (empty? findings)
     :findings findings
     :compound/lineage-root (:lineage/root lineage)}))

(defn- validated-stream [frame-stream]
  (let [validation (validate-frame-lineage frame-stream)]
    (if (:valid? validation)
      {:status :ok}
      {:status :invalid-lineage :validation validation})))

(defn first-frame-matching
  "Observational query only. `pred` is not protocol assurance evidence."
  [frame-stream pred]
  (let [status (validated-stream frame-stream)]
    (if (= :ok (:status status))
      {:status :ok :frame (first (filter pred (:frames frame-stream)))}
      status)))

(defn frames-matching
  "Observational query only. `pred` is not protocol assurance evidence."
  [frame-stream pred]
  (let [status (validated-stream frame-stream)]
    (if (= :ok (:status status))
      {:status :ok :frames (vec (filter pred (:frames frame-stream)))}
      status)))

(defn frame-context
  [frame-stream index]
  (let [status (validated-stream frame-stream)]
    (if (= :ok (:status status))
      (when-let [frame (frame-at frame-stream index)]
        {:frame frame
         :predecessor (previous-frame frame-stream index)
         :transition (reconstruct-transition frame)
         :assurance (:assurance frame)
         :position {:frame/index index
                    :head? (= index (dec (count (:frames frame-stream))))}})
      status)))

(defn replay-frame-transition
  "Historical replay is intentionally unavailable without retained material."
  [_frame]
  {:status :not-replayable
   :missing #{:historical-executable :invocation-input :policy-application-material}})
