(ns resolver-sim.notebooks.speds.risk-test
  "P1: Scenario Risk Projection (risk-projection.v1).
   Verifies the exit criteria: canonical rows name their evidence object and
   field, deltas are derived not invented, coverage never hides unmeasured
   scenarios, corpus statistics are not dressed up as VaR, verification
   status is separated from provenance, and the root commitment re-verifies."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.notebook-support.speds.risk :as risk]
            [resolver-sim.notebook-support.speds.risk-render :as render]))

(defn- write-json!
  [dir file m]
  (let [f (io/file dir file)]
    (.mkdirs (.getParentFile f))
    (spit f (json/write-str m))
    f))

(defn- node
  "Minimal event-evidence node map (JSON string keys → parsed keywords).
   World before/after hashes are rotations of the (64-hex) evidence hash so
   the P3 world-field well-formedness check passes."
  [& {:keys [scenario ev-seq chain-seq type hash post total]}]
  (let [post (or post
                 (if total
                   {"escrow/after" {"total-held" total}}
                   {"resolver-stake" 75}))]
    {"scenario/id" scenario
     "evidence/type" type
     "evidence/hash" hash
     "evidence/chain-seq" chain-seq
     "event/seq" ev-seq
     "world/before-hash" (str (subs hash 32) (subs hash 0 32))
     "world/after-hash" hash
     "inputs" (if total {"escrow/token" "USDC"} {})
     "post-state" post}))

(defn- chain-link!
  "Compute the link-v1 chain fields for one scenario's nodes (sorted by
   chain-seq) using the repo's chain-link-hash, and add them back as JSON keys.
   This is the same scheme the simulator injects, so the P3 chain verifier
   accepts the fixture."
  [nodes]
  (loop [prev nil, rs (sort-by #(get % "evidence/chain-seq") nodes), out []]
    (if (empty? rs)
      out
      (let [n (first rs)
            seq  (get n "evidence/chain-seq")
            hash (get n "evidence/hash")
            self (chain/chain-link-hash hash seq prev)
            linked (assoc n
                          "evidence/chain-hash-scheme" "link-v1"
                          "evidence/chain-prev-hash" prev
                          "evidence/chain-self-hash" self)]
        (recur self (rest rs) (conj out linked))))))

(defn- bundle-dirs
  "Write a small deterministic bundle into temp dirs:
   aaa-zero — measured, sorts FIRST (guards delta computation: scn-a's first
              row must keep a nil delta even though a previous row exists)
   scn-a    — multi-event measured (create, create, release)
   scn-b    — single-event measured (create)
   scn-c    — evidence present, no total-held (slashing only) → not-measured
   scn-a has a trace; the others do not."
  []
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "risk-test-" (System/nanoTime)))
        ev  (io/file tmp "event-evidence")
        tr  (io/file tmp "traces")]
    (.mkdirs ev)
    (.mkdirs tr)
    (let [files [["aaa-zero-1.json" (node :scenario "aaa-zero" :ev-seq 0 :chain-seq 1
                                          :type "escrow-created" :hash (apply str (repeat 32 "aa")) :total 100)]
                 ["scn-a-1.json" (node :scenario "scn-a" :ev-seq 0 :chain-seq 1
                                       :type "escrow-created" :hash (apply str (repeat 32 "a1")) :total 985)]
                 ["scn-a-2.json" (node :scenario "scn-a" :ev-seq 1 :chain-seq 2
                                       :type "escrow-created" :hash (apply str (repeat 32 "a2")) :total 2955)]
                 ["scn-a-3.json" (node :scenario "scn-a" :ev-seq 2 :chain-seq 3
                                       :type "escrow-released" :hash (apply str (repeat 32 "a3"))
                                       :post {"finalize/after" {"total-held" 0}})]
                 ["scn-b-1.json" (node :scenario "scn-b" :ev-seq 0 :chain-seq 1
                                       :type "escrow-created" :hash (apply str (repeat 32 "b1")) :total 500)]
                 ["scn-c-1.json" (node :scenario "scn-c" :ev-seq 0 :chain-seq 1
                                       :type "slashing" :hash (apply str (repeat 32 "c1")))]]]
      (doseq [[file m] (->> files
                            (map (fn [[f m]] (assoc m "_file" f)))
                            (group-by #(get % "scenario/id"))
                            (mapcat (fn [[_ nodes]]
                                      (map (fn [n] [(get n "_file") (dissoc n "_file")])
                                           (chain-link! nodes))))
                            (into []))]
        (write-json! ev file m)))
    (write-json! tr "scn-a.trace.json"
                 {"scenario-id" "scn-a"
                  "events" [{"seq" 0 "time" 1000 "action" "create_escrow"}
                            {"seq" 1 "time" 2000 "action" "create_escrow"}
                            {"seq" 2 "time" 3000 "action" "execute_resolution"}]})
    {:bundle-dir (.getAbsolutePath ev)
     :trace-dir (.getAbsolutePath tr)}))

(deftest project-is-deterministic
  (testing "identical bundle input yields identical projection"
    (let [opts (bundle-dirs)]
      (is (= (risk/project opts) (risk/project opts))))))

(deftest rows-name-their-evidence-object-and-field
  (testing "every row is :derived and names an evidence hash + exact field path"
    (let [p (risk/project (bundle-dirs))
          rows (:rows (:projection p))]
      (is (= 5 (count rows)))
      (doseq [row rows]
        (is (= :derived (:claim/basis row)))
        (is (str/starts-with? (:evidence/hash (:source row)) "sha256:"))
        (is (contains? (:source row) :evidence/path))
        (is (contains? (:source row) :evidence/file))
        (is (= :observed (-> row :source :observation-basis)))))))

(deftest timestamps-join-where-trace-exists
  (testing ":event/at resolves from the trace and is :not-measured otherwise"
    (let [p (risk/project (bundle-dirs))
          scn-a (->> (:rows (:projection p)) (filter #(= "scn-a" (:scenario/id %))))
          scn-b (->> (:rows (:projection p)) (filter #(= "scn-b" (:scenario/id %))))]
      (is (= [1000 2000 3000] (mapv :event/at scn-a)))
      (is (= [:not-measured] (mapv :event/at scn-b))))))

(deftest deltas-are-derived-not-invented
  (testing "deltas equal observed amount differences; first row has nil delta even when another scenario precedes it"
    (let [p (risk/project (bundle-dirs))
          scn-a (->> (:rows (:projection p)) (filter #(= "scn-a" (:scenario/id %))))]
      (is (= [985 2955 0] (mapv :amount scn-a)))
      (is (= [nil 1970 -2955] (mapv :delta scn-a))))))

(deftest coverage-never-hides-unmeasured-scenarios
  (testing "measured + not-measured accounts for the whole corpus"
    (let [p (risk/project (bundle-dirs))
          cov (:coverage p)]
      (is (= 4 (:scenario-count cov)))
      (is (= 3 (:measured-scenario-count cov)))
      (is (= 1 (:not-measured-scenario-count cov)))
      (is (= ["scn-c"] (:not-measured-scenarios cov)))
      (is (= (+ (:measured-scenario-count cov) (:not-measured-scenario-count cov))
             (:scenario-count cov))))))

(deftest metrics-are-scenario-local-and-unquestionably-valid
  (testing "per-scenario metrics computed within scenario only"
    (let [p (risk/project (bundle-dirs))
          by-id (into {} (map (juxt :scenario/id identity)) (:per-scenario (:metrics p)))
          a (get by-id "scn-a") b (get by-id "scn-b")]
      (is (= 2955 (:peak-observed-exposure a)))
      (is (= 2955 (:max-observed-event-loss a)))
      (is (= 2955 (:peak-drawdown a)))
      (is (= 500 (:peak-observed-exposure b)))
      (is (= 0 (:max-observed-event-loss b)))
      (is (= {:scenario/id "scn-a", :max-observed-event-loss 2955}
             (:worst-observed-scenario (:metrics p)))))))

(deftest no-var-claims-without-a-distribution
  (testing "corpus statistics are never exposed as VaR; distribution stays :not-measured"
    (let [p (risk/project (bundle-dirs))
          metric-keys (->> (concat (keys (:metrics p))
                                   (mapcat keys (:per-scenario (:metrics p))))
                           (map name)
                           (filter #(str/includes? % "var")))]
      (is (= :not-measured (:status (:distribution-policy p))))
      (is (true? (:var-claims-absent (:distribution-policy p))))
      (is (empty? metric-keys)))))

(deftest evidence-provenance-is-separated-from-integrity
  (testing "chain verification is established in v1; content integrity and world-transition recomputation are not"
    (let [p (risk/project (bundle-dirs))
          e (:evidence p)]
      (is (= :verified (:traceability e)))
      (is (= :verified (:chain-verification e)))
      (is (= :not-measured (:integrity e)))
      (is (= :not-measured (:world-transition-verification e)))
      (is (= :verified (:world-hash-fields e)))
      (is (str/starts-with? (:verification-root e) "sha256:")))))

(deftest p3-chain-verification-covers-every-scenario
  (testing "every scenario chain in the corpus verifies; the root commits to the verified evidence set"
    (let [p (risk/project (bundle-dirs))
          detail (get-in p [:evidence :chain-verification-detail])]
      (is (= 4 (:scenario-count detail)))
      (is (= 4 (:verified-scenario-count detail)))
      (is (empty? (:invalid-scenarios detail)))
      (is (= (count (:evidence-roots (:source p)))
             (count (distinct (:evidence-roots (:source p)))))))))

(deftest p3-tampering-a-chain-link-fails-chain-verification
  (testing "corrupting a node's chain-self-hash makes chain verification fail without touching rows"
    (let [opts (bundle-dirs)
          clean (risk/project opts)
          tampered (risk/project (update opts :bundle-dir
                                         (fn [dir]
                                           (let [f (io/file dir "scn-b-1.json")
                                                 m (json/read-str (slurp f) :key-fn keyword)
                                                 bad (assoc m :evidence/chain-self-hash
                                                            (apply str (repeat 64 "0")))
                                                 key-fn (fn [k] (if (keyword? k)
                                                                  (if-let [ns (namespace k)]
                                                                    (str ns "/" (name k))
                                                                    (name k))
                                                                  (str k)))]
                                             (spit f (json/write-str bad :key-fn key-fn))
                                             dir))))
          bad-detail (get-in tampered [:evidence :chain-verification-detail])]
      (is (= :verified (:chain-verification (:evidence clean))))
      (is (= :failed (:chain-verification (:evidence tampered))))
      (is (seq (:invalid-scenarios bad-detail)))
      (is (= (:rows (:projection clean)) (:rows (:projection tampered))))
      (is (= (:risk-projection/root clean) (:risk-projection/root tampered))
          "evidence status is outside the semantic root; tampered chain status must not change the risk claim"))))

(deftest root-commitment-re-verifies
  (testing "recomputed commitment matches the stored risk-projection root"
    (let [p (risk/project (bundle-dirs))]
      (is (= :pass (:status (risk/verify-root p))))
      (is (str/starts-with? (:canonical/hash (:risk-projection/root p)) "sha256:")))))

(deftest mutation-removing-a-node-changes-rows-and-root
  (testing "dropping a release node reduces rows, changes metrics, and the root moves"
    (let [opts (bundle-dirs)
          full (risk/project opts)
          reduced (risk/project (update opts :bundle-dir
                                        (fn [dir]
                                          (io/delete-file (io/file dir "scn-a-3.json"))
                                          dir)))
          rows (->> (:rows (:projection reduced))
                    (filter #(= "scn-a" (:scenario/id %))))]
      (is (= 2 (count rows)))
      (is (= [nil 1970] (mapv :delta rows)))
      (is (not= (:risk-projection/root full) (:risk-projection/root reduced)))
      (is (= :pass (:status (risk/verify-root reduced)))))))

(deftest risk-card-renders-honestly
  (testing "the risk card renders required labels, shows VaR as NOT MEASURED, and is deterministic"
    (let [p (risk/project (bundle-dirs))
          html (render/render-card-html p)]
      (is (str/starts-with? html "<!doctype html>"))
      (is (str/includes? html "SCENARIO RISK PROJECTION"))
      (is (str/includes? html "QUANTITY"))
      (is (str/includes? html "escrow/total-held"))
      (is (str/includes? html "VaR p95"))
      (is (str/includes? html "VaR p99"))
      (is (str/includes? html "NOT MEASURED"))
      (is (str/includes? html "VERIFIED"))
      (is (str/includes? html "Chain verification"))
      (is (= html (render/render-card-html p))))))
