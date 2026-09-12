(ns resolver-sim.benchmark.authority-state-snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.authority-state-snapshot :as snapshot]))

(defn- root [suffix]
  (str "sha256:" (apply str (repeat (- 64 (count suffix)) "0")) suffix))

(defn- snapshot-input []
  {:authority-store/format snapshot/store-format
   :authority-store/id (root "01")
   :chain-instance-genesis/root (root "02")
   :publication/version 7
   :current-authoritative-state-envelope/root (root "03")
   :current-configuration-head/root (root "04")
   :current-chain-configuration/root (root "05")
   :current-authority-semantics-policy/root (root "06")
   :current-governed-authority-semantics/root (root "07")
   :current-authority-material-manifest/root (root "08")
   :activation-lineage-index/root (root "09")
   :issued-fence-index/root (root "0a")
   :terminal-result-index/root (root "0b")
   :receipt-index/root (root "0c")})

(defn- built []
  (snapshot/build-snapshot (snapshot-input)))

(deftest rooted-snapshot-commits-an-exact-closed-dependency-manifest
  (let [{:keys [snapshot dependency-manifest]} (built)]
    (is (:valid? (snapshot/validate-snapshot snapshot)))
    (is (:valid? (snapshot/validate-dependency-manifest dependency-manifest)))
    (is (= (:authority-state-snapshot/root snapshot)
           (snapshot/snapshot-root snapshot)))
    (is (= (:authority-state-dependency-manifest/root dependency-manifest)
           (snapshot/dependency-manifest-root dependency-manifest)))
    (is (= (snapshot/snapshot-dependency-roots snapshot)
           (set (:dependency/roots dependency-manifest))))
    (is (= {:snapshot snapshot :dependency-manifest dependency-manifest}
           (built)))))

(deftest closure-readback-requires-every-exact-root-and-semantic-join
  (let [{:keys [snapshot dependency-manifest]} (built)
        objects (into {(:authority-state-dependency-manifest/root dependency-manifest)
                       dependency-manifest}
                      (map (fn [root] [root {:root root}])
                           (:dependency/roots dependency-manifest)))
        read-object #(get objects %)
        verify-object (fn [root object] (= root (:root object)))
        verify-joins (fn [candidate resolved]
                       (and (= (:current-authoritative-state-envelope/root candidate)
                               (:root (get resolved (:current-authoritative-state-envelope/root candidate))))
                            (= (count resolved) (count (:dependency/roots dependency-manifest)))))]
    (is (= snapshot
           (:snapshot (snapshot/verify-snapshot-closure!
                       snapshot read-object verify-object verify-joins))))
    (testing "a missing mandatory dependency cannot become current"
      (let [missing-root (:current-configuration-head/root snapshot)]
        (is (= :missing-snapshot-dependency
               (:reason (ex-data
                         (try
                           (snapshot/verify-snapshot-closure!
                            snapshot #(when-not (= missing-root %) (get objects %))
                            verify-object verify-joins)
                           (catch clojure.lang.ExceptionInfo error error))))))))
    (testing "a substituted or invalid object fails before semantic admission"
      (let [bad-root (:current-chain-configuration/root snapshot)]
        (is (= :invalid-snapshot-dependency
               (:reason (ex-data
                         (try
                           (snapshot/verify-snapshot-closure!
                            snapshot #(if (= bad-root %) {:root (root "ff")} (get objects %))
                            verify-object verify-joins)
                           (catch clojure.lang.ExceptionInfo error error))))))))
    (testing "a semantic join verifier remains mandatory"
      (is (= :invalid-authority-state-snapshot-joins
             (:reason (ex-data
                       (try
                         (snapshot/verify-snapshot-closure!
                          snapshot read-object verify-object (constantly false))
                         (catch clojure.lang.ExceptionInfo error error)))))))))

(deftest snapshot-and-manifest-shapes-are-closed-and-self-rooted
  (let [{:keys [snapshot dependency-manifest]} (built)]
    (is (false? (:valid? (snapshot/validate-snapshot (assoc snapshot :extra true)))))
    (is (false? (:valid? (snapshot/validate-snapshot
                          (assoc snapshot :authority-state-snapshot/root (root "ee"))))))
    (is (false? (:valid? (snapshot/validate-dependency-manifest
                          (assoc dependency-manifest :dependency/roots [])))))
    (is (false? (:valid? (snapshot/validate-dependency-manifest
                          (assoc dependency-manifest :extra true)))))))
