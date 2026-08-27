(ns resolver-sim.commands.run-benchmark-research-pack-test
  "Public --research-pack validation must complete before canonical run creation."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-pack :as research-pack]
            [resolver-sim.composition.semantic :as semantic]
            [resolver-sim.commands.run-benchmark :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.benchmark.claim-registry :as claim-registry]))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn- root [value]
  (hash-ref/sha256-ref (hc/domain-hash :research-benchmark-pack {:fixture value})))

(defn- frozen-pack []
  (research-pack/freeze-pack
   {:pack-id :test/research-pack
    :command-root (root :command)
    :assignment-root (root :assignment)
    :plan-root (root :plan)
    :members [{:member/id :test/member
               :member/contract "test.member.v1"
               :member/input-root (root :input)
               :member/parameters-root (root :parameters)
               :member/expected-outputs {}}]
    :requested-capabilities []
    :profile :development
    :resolution-options {:schemas {} :effect-schemas {}}
    :extension-map {}}))

(defn- frozen-pack-v2 []
  (research-pack/freeze-pack-v2
   {:pack-id :test/research-pack
    :command-root (root :command)
    :assignment-root (root :assignment)
    :plan-root (root :plan)
    :members [{:member/id :test/member
               :member/contract "test.member.v1"
               :member/input-root (root :input)
               :member/parameters-root (root :parameters)
               :member/expected-outputs {}}]
    :requested-capabilities []
    :profile :development
    :resolution-options {:schemas {} :effect-schemas {}}
    :extension-map {}}))

(defn- write-edn! [file value]
  (spit file (pr-str value))
  (.getPath file))

(defn- body-a-with-root-b [pack]
  (let [a (:research-pack/composition pack)
        b (assoc a :semantic-composition/policy-bindings {:policy/b :different})
        root-b (hc/domain-hash semantic/composition-domain-tag
                               (hc/project-canonical-safe
                                (dissoc b :semantic-composition/root)))
        attacked (assoc pack
                        :research-pack/composition
                        (assoc a :semantic-composition/root root-b)
                        :research-pack/composition-root root-b)]
    (assoc attacked :research-pack/root (research-pack/pack-root attacked))))

(defn- run-with-pack [pack-file run-root calls]
  (with-redefs-fn {#'command/run-with-root! (fn [& _]
                                              (swap! calls inc)
                                              {:exit-code 0})
                   #'claim-registry/load-claim-registry (constantly {})
                   #'resolver-sim.commands.run-benchmark/resolved-scenario-count (constantly 1)
                   #'resolver-sim.commands.run-benchmark/legacy-scenario-suite-manifest? (constantly false)
                   #'resolver-sim.commands.run-benchmark/empty-scenario-manifest? (constantly false)}
    #(command/run {:cmd/args ["benchmark/test"]
                   :run-root (.getPath run-root)
                   :research-pack pack-file})))

(deftest persisted-v2-research-pack-is-validated-before-run-creation
  (let [temp (.toFile (java.nio.file.Files/createTempDirectory "research-pack-v2-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
        run-root (io/file temp "canonical-run")
        calls (atom 0)]
    (try
      (let [pack (frozen-pack-v2)
            valid-file (write-edn! (io/file temp "valid-v2.edn") pack)
            invalid-file (write-edn!
                          (io/file temp "invalid-v2-resolution.edn")
                          (assoc-in pack [:research-pack/resolution
                                          :extensions/capability-providers]
                                    {:attacker/capability {:providers []}}))]
        (is (= 2 (:exit-code (run-with-pack invalid-file run-root calls))))
        (is (zero? @calls))
        (is (not (.exists run-root)))
        (is (zero? (:exit-code (run-with-pack valid-file run-root calls))))
        (is (= 1 @calls)))
      (finally
        (delete-tree! temp)))))

(deftest persisted-research-pack-is-validated-before-run-creation
  (let [temp (.toFile (java.nio.file.Files/createTempDirectory "research-pack-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
        run-root (io/file temp "canonical-run")
        calls (atom 0)]
    (try
      (let [pack (frozen-pack)
            cases [[:unreadable (.getPath (io/file temp "missing.edn"))]
                   [:malformed-edn (doto (io/file temp "malformed.edn") (spit "{") .getPath)]
                   [:pack-root-mismatch (write-edn! (io/file temp "bad-pack-root.edn")
                                                    (assoc pack :research-pack/root "sha256:bad"))]
                   [:malformed-composition (write-edn! (io/file temp "malformed-composition.edn")
                                                       (assoc pack :research-pack/composition {:bad true}))]
                   [:nested-root-mismatch (write-edn! (io/file temp "nested-root.edn")
                                                      (assoc-in pack [:research-pack/composition :semantic-composition/root]
                                                                "attacker-root"))]
                   [:outer-root-mismatch (write-edn! (io/file temp "outer-root.edn")
                                                     (assoc pack :research-pack/composition-root "attacker-root"))]
                   [:body-a-root-b (write-edn! (io/file temp "body-a-root-b.edn")
                                               (body-a-with-root-b pack))]]]
        (doseq [[label pack-file] cases]
          (testing (name label)
            (reset! calls 0)
            (let [result (run-with-pack pack-file run-root calls)]
              (is (= 2 (:exit-code result)))
              (is (zero? @calls))
              (is (not (.exists run-root))))))
        (testing "a valid persisted frozen pack reaches the shared runner"
          (let [pack-file (write-edn! (io/file temp "valid.edn") pack)
                result (run-with-pack pack-file run-root calls)]
            (is (zero? (:exit-code result)))
            (is (= 1 @calls)))))
      (finally
        (delete-tree! temp)))))
