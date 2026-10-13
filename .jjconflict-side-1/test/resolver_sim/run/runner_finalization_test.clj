(ns resolver-sim.run.runner-finalization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.run.runner-finalization :as runner-finalization]))

(def input
  {:run-id "run-test"
   :runner-selection {:mode :pinned :runner-id :runner/local-bb}
   :source-provenance {:source/hash "source-implementation-hash"}
   :execution-result {:execution/termination :completed
                      :semantic/outcome :fail
                      :cli/exit-code 1
                      :bundle/root-hash "bundle-hash"}})

(deftest runner-finalization-is-immutable-and-local
  (let [artifact (runner-finalization/build input)
        validation (runner-finalization/valid? artifact)]
    (is (:valid? validation))
    (is (= :runner-local (get-in artifact [:runner/local :runtime/kind])))
    (is (= :runner/local-bb (get-in artifact [:runner/selection :runner-id])))
    (is (= :completed (get-in artifact [:execution/result :execution/termination])))
    (is (= :fail (get-in artifact [:execution/result :semantic/outcome])))
    (is (:runnable? (runner-finalization/runnable? artifact)))))

(deftest runner-finalization-wire-round-trip-is-semantic
  (let [logical (runner-finalization/build input)
        wire (runner-finalization/runner-finalization->wire logical)
        restored (runner-finalization/wire->runner-finalization wire)]
    (is (= "pinned" (get-in wire [:runner/selection :mode])))
    (is (= "local-bb" (get-in wire [:runner/selection :runner-id])))
    (is (= :runner/local-bb (get-in restored [:runner/selection :runner-id])))
    (is (= :completed (get-in restored [:execution/result :execution/termination])))
    (is (:valid? (runner-finalization/valid? restored)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported runner-finalization wire enum"
                          (runner-finalization/wire->runner-finalization
                           (assoc-in wire [:runner/selection :mode] "unknown"))))))

(deftest runner-finalization-rejects-tampering
  (let [artifact (runner-finalization/build input)]
    (is (not (:valid? (runner-finalization/valid?
                       (assoc-in artifact [:execution/result :cli/exit-code] 0)))))))

(deftest runner-finalization-wire-enums-are-strict-and-semantic
  (let [logical (runner-finalization/build input)
        wire (runner-finalization/runner-finalization->wire logical)
        unsupported (fn [path value]
                      (try
                        (runner-finalization/wire->runner-finalization (assoc-in wire path value))
                        nil
                        (catch clojure.lang.ExceptionInfo error (ex-data error))))]
    (is (= :runner/local-bb
           (get-in (runner-finalization/wire->runner-finalization wire)
                   [:runner/selection :runner-id])))
    (doseq [[path value field]
            [[[:runner/selection :mode] "invalid" :runner/mode]
             [[:runner/selection :runner-id] "remote-runner" :runner/id]
             [[:runner/local :runtime/kind] "remote" :runtime/kind]
             [[:execution/result :execution/termination] "failed" :execution/termination]
             [[:execution/result :semantic/outcome] "unknown" :semantic/outcome]]]
      (let [data (unsupported path value)]
        (is (= :runner-finalization/unsupported-enum (:code data)))
        (is (= field (:field data)))))
    ;; The persisted string representation is accepted only after explicit
    ;; normalization. Altering a normalized semantic field invalidates the
    ;; canonical content hash rather than bypassing it.
    (is (not (:valid? (runner-finalization/valid?
                       (assoc-in wire [:execution/result :semantic/outcome] "pass")))))))
