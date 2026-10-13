(ns resolver-sim.extensions.lockfile-test
  "Phase 1: lockfile pinning — build from a resolution snapshot, validate,
   and EDN round-trip."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.extensions.fixtures :as fx]
            [resolver-sim.extensions.lockfile :as lf]
            [resolver-sim.extensions.registry :as reg]
            [resolver-sim.extensions.resolution :as res]))

(defn- sample-resolution
  []
  (let [emap (-> (reg/empty-extension-map)
                 (reg/register-package fx/rate-with-cap-pack)
                 (reg/register-package fx/scaled-share-pack))
        {:keys [valid? resolution]}
        (res/resolve-requested emap
                               [[:economics/award-amount :fixture/rate-with-cap]]
                               {:schemas fx/schemas})]
    (is valid?)
    resolution))

(deftest build-and-validate-lockfile
  (let [lockfile (lf/build-lockfile (sample-resolution))]
    (is (= 1 (:lockfile/version lockfile)))
    (is (= 2 (count (:lockfile/packages lockfile))))
    (is (= 64 (count (:lockfile/hash lockfile))))
    (is (:valid? (lf/validate-lockfile lockfile)))))

(deftest tampered-lockfile-fails-validation
  (let [lockfile (lf/build-lockfile (sample-resolution))
        tampered (update-in lockfile [:lockfile/packages 0 :package/version]
                            (fn [_] "0.0.0"))
        result (lf/validate-lockfile tampered)]
    (is (not (:valid? result)))
    (is (some #{:hash-mismatch} (:errors result)))))

(deftest structurally-invalid-lockfile-fails
  (is (not (:valid? (lf/validate-lockfile {}))))
  (is (not (:valid? (lf/validate-lockfile
                     {:lockfile/version 99
                      :lockfile/packages []
                      :lockfile/resolution-root "x"
                      :lockfile/hash "x"})))))

(deftest lockfile-round-trips-through-edn
  (let [lockfile (lf/build-lockfile (sample-resolution))]
    (is (= lockfile
           (lf/parse-lockfile (lf/pr-str-lockfile lockfile))))))

(deftest lockfile-hash-deterministic
  (is (= (:lockfile/hash (lf/build-lockfile (sample-resolution)))
         (:lockfile/hash (lf/build-lockfile (sample-resolution))))))
