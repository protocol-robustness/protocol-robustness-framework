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
        root (hc/domain-hash "PRF_REPLAY_FRAME_V1" frame)]
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
