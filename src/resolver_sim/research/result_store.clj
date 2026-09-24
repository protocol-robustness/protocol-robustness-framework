(ns resolver-sim.research.result-store
  "Atomic in-memory content-addressed storage for completed research cells.

   This is deliberately a research-local store abstraction. It preserves every
   valid result by its content root and never overwrites a cell binding."
  (:require [resolver-sim.research.execution-cell-runner :as runner]))

(def ^:const store-format "research-result-store.v1")

(defrecord ResultStore [state])

(defn new-store []
  (->ResultStore (atom {:result-store/format store-format
                        :by-result-root {}
                        :by-cell-root {}
                        :conflicts {}})))

(defn snapshot [store]
  @(:state store))

(defn result-by-root [store result-root]
  (get-in (snapshot store) [:by-result-root result-root]))

(defn result-by-cell [store cell-root]
  (get-in (snapshot store) [:by-cell-root cell-root]))

(defn cell-status [store cell-root]
  (let [state (snapshot store)]
    (cond (contains? (:conflicts state) cell-root) :conflicting
          (contains? (:by-cell-root state) cell-root) :complete
          :else :missing)))

(defn put-result!
  "Atomically add a valid completed result.

   `:stored` means the cell had no prior binding, `:duplicate` means the same
   content root was already present, and `:conflict` preserves a second valid
   result root while marking that cell non-reproducible. No branch overwrites
   an existing cell binding."
  [store result]
  (let [validation (runner/validate-result result)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot store an invalid execution cell result" validation)))
    (let [cell-root (:research-execution-cell/root result)
          result-root (:research-execution-cell-result/root result)]
      (loop []
        (let [before @(:state store)
              existing (get-in before [:by-cell-root cell-root])
              existing-root (:research-execution-cell-result/root existing)
              status (cond (nil? existing) :stored
                           (= existing-root result-root) :duplicate
                           :else :conflict)
              after (case status
                      :stored (-> before
                                  (assoc-in [:by-result-root result-root] result)
                                  (assoc-in [:by-cell-root cell-root] result))
                      :duplicate before
                      :conflict (-> before
                                    (assoc-in [:by-result-root result-root] result)
                                    (update-in [:conflicts cell-root] (fnil conj #{}) existing-root)
                                    (update-in [:conflicts cell-root] conj result-root)))]
          (if (compare-and-set! (:state store) before after)
            {:store/status status
             :research-execution-cell/root cell-root
             :research-execution-cell-result/root result-root}
            (recur)))))))

(defn missing-cells
  "Return manifest cells that lack a non-conflicting completed result." 
  [store execution-manifest]
  (->> (:dispatch/cells execution-manifest)
       (filter #(not= :complete (cell-status store (:research-execution-cell/root %))))
       (sort-by :research-execution-cell/root)
       vec))

(defn conflicts [store]
  (into (sorted-map) (:conflicts (snapshot store))))
