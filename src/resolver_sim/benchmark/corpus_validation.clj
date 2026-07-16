(ns resolver-sim.benchmark.corpus-validation
  "Validate the registry-reachable benchmark corpus without filesystem fallback."
  (:require [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.resource-path :as resource-path]
            [resolver-sim.scenario.suites :as suites]))

(defn- resource-ref [path]
  (if (or (.startsWith path "resource:") (.startsWith path "classpath:")) path
      (str "resource:" path)))

(defn validate-corpus!
  "Return a summary or throw with all discovered registry-reachable corpus errors.
   Every supported benchmark must use a registered :benchmark/scenario-suite
   whose scenario inputs are resolvable as classpath resources."
  []
  (let [errors (atom [])
        registry (resource-path/edn-read resource-path/canonical-registry-path)
        manifests (atom [])]
    (doseq [pack (:packs registry)]
      (let [pack-path (resource-path/pack-registry-path (:pack/registry pack))
            pack-registry (try (resource-path/edn-read pack-path)
                               (catch Throwable error
                                 (swap! errors conj {:type :missing-pack-registry :pack (:pack/id pack)
                                                     :path pack-path :error (.getMessage error)})
                                 nil))]
        (doseq [benchmark (:benchmarks pack-registry)]
          (let [manifest-path (resource-path/relative-to pack-path (:benchmark/file benchmark))
                manifest (try (resource-path/edn-read manifest-path)
                              (catch Throwable error
                                (swap! errors conj {:type :missing-benchmark-manifest
                                                    :benchmark (:benchmark/id benchmark)
                                                    :path manifest-path :error (.getMessage error)})
                                nil))]
            (when manifest
              (swap! manifests conj [manifest-path manifest])
              (if-let [suite-key (:benchmark/scenario-suite manifest)]
                (if-let [paths (suites/suite-paths suite-key)]
                  (doseq [path paths]
                    (try
                      (input-source/source path)
                      (catch Throwable error
                        (swap! errors conj {:type :unresolvable-suite-input
                                            :benchmark (:benchmark/id manifest)
                                            :suite suite-key :path path :error (.getMessage error)}))))
                  (swap! errors conj {:type :unknown-suite
                                      :benchmark (:benchmark/id manifest) :suite suite-key}))
                (when (seq (:scenario-suites manifest))
                  (swap! errors conj {:type :filesystem-suite-unsupported
                                      :benchmark (:benchmark/id manifest)
                                      :paths (:scenario-suites manifest)}))))))))
    (let [ids (map (comp :benchmark/id second) @manifests)
          duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
      (when (seq duplicate-ids)
        (swap! errors conj {:type :duplicate-benchmark-ids :ids duplicate-ids})))
    (when (seq @errors)
      (throw (ex-info "Benchmark corpus validation failed" {:errors @errors})))
    {:packs (count (:packs registry))
     :benchmarks (count @manifests)
     :status :passed}))
