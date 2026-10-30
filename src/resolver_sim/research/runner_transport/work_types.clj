(ns resolver-sim.research.runner-transport.work-types
  "Schema-based adapters shared by the runner transport's work handlers."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accounting-execution-work :as accounting-work]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.reproduction-execution-work :as reproduction-work]))

(defn observed-work-for [work]
  (cond
    (native-accounting/valid-work? work) work
    (reproduction-work/valid-work? work) work
    (observed-work/valid-work? work) work
    (accounting-work/valid-work? work) (:accounting/observed-execution-work work)
    :else nil))

(defn work-root [work]
  (cond
    (native-accounting/valid-work? work) (:research-native-accounting-work/root work)
    (reproduction-work/valid-work? work) (:research-reproduction-execution-work/root work)
    (observed-work/valid-work? work) (:research-observed-execution-work/root work)
    (accounting-work/valid-work? work) (:research-accounting-execution-work/root work)))

(defn work-root-value [work]
  ;; Read a root only after the enclosing dispatch-intent path has validated
  ;; the matching schema. Kept separate from work-root, which is a validator.
  (case (:artifact/schema work)
    "research-native-accounting-work.v1" (:research-native-accounting-work/root work)
    "research-reproduction-execution-work.v1" (:research-reproduction-execution-work/root work)
    "research-observed-execution-work.v1" (:research-observed-execution-work/root work)
    "research-accounting-execution-work.v1" (:research-accounting-execution-work/root work)
    nil))

(defn native-accounting-work-shape? [work]
  ;; Runtime dispatch/cache discriminator only, never an admission check.
  (= "research-native-accounting-work.v1" (:artifact/schema work)))

(defn accounting-cache-from-state [state]
  ;; Work bodies in the journal remain authoritative; this is a rebuilt index.
  (reduce (fn [cache work]
            (if (native-accounting-work-shape? work)
              (let [request (:accounting/request work)
                    plan (:accounting/partition-plan work)]
                (-> cache
                    (assoc-in [:requests (:accounting-request/root request)] request)
                    (assoc-in [:plans (:accounting-partition-plan/root plan)] plan)))
              cache))
          {:requests {} :plans {}} (vals (:work-items state))))

(defn accounting-reference? [value root-key]
  (and (map? value) (= #{root-key} (set (keys value)))
       (hash-ref/valid-sha256-ref? (get value root-key))))

(defn resolve-accounting-inputs [runner work]
  (if-not (native-accounting-work-shape? work)
    {:work work}
    (let [request (:accounting/request work)
          plan (:accounting/partition-plan work)
          request-ref? (accounting-reference? request :accounting-request/root)
          plan-ref? (accounting-reference? plan :accounting-partition-plan/root)
          cache @(:transport/accounting-input-cache runner)
          resolved-request (if request-ref?
                             (get-in cache [:requests (:accounting-request/root request)]) request)
          resolved-plan (if plan-ref?
                          (get-in cache [:plans (:accounting-partition-plan/root plan)]) plan)]
      (if (or (and request-ref? (nil? resolved-request))
              (and plan-ref? (nil? resolved-plan)))
        {:cache-miss? true
         :missing-roots (vec (remove nil?
                                     [(when (nil? resolved-request) (:accounting-request/root request))
                                      (when (nil? resolved-plan) (:accounting-partition-plan/root plan))]))}
        {:work (assoc work :accounting/request resolved-request
                      :accounting/partition-plan resolved-plan)}))))

(defn remember-accounting-inputs! [runner work]
  (when (native-accounting-work-shape? work)
    (swap! (:transport/accounting-input-cache runner)
           (fn [cache]
             (-> cache
                 (assoc-in [:requests (get-in work [:accounting/request :accounting-request/root])]
                           (:accounting/request work))
                 (assoc-in [:plans (get-in work [:accounting/partition-plan
                                                 :accounting-partition-plan/root])]
                           (:accounting/partition-plan work)))))))
