(ns scripts.artifact-scope-tests
  "Adversarial and collision tests for the root-confined artifact API.

   Verifies failure classification (escape rejected / conflict / closed /
   incomplete) rather than merely that an exception occurred.

   Run:
     clojure -M:test:with-sew -m scripts.artifact-scope-tests

   Exits 0 when all checks pass, 1 otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [scripts.artifact-scope :as a]))

(def results (atom []))
(defn- check [name pass detail]
  (swap! results conj {:name name :pass pass :detail detail})
  (println (str (if pass "  PASS  " "  FAIL  ") name)))

(defn- temp-root []
  (let [d (str (System/getProperty "java.io.tmpdir") "/artifact-tests-"
               (java.util.UUID/randomUUID))]
    (.mkdirs (io/file d))
    d))

(defn- scope-config [root run-id ns idx]
  {:run-id run-id :namespace ns :namespace-root (str root "/ns-" idx)
   :scope-id (str run-id "-" idx)})

(defn run-checks
  []
  (let [root (temp-root)
        run-id (str "art-test-" (java.util.UUID/randomUUID))
        cfg-a (scope-config root run-id 'ns-a 0)
        cfg-b (scope-config root run-id 'ns-b 1)]
    (a/write-owner-marker! root {:run-id run-id :namespace :run-root})
    (a/write-owner-marker! (:namespace-root cfg-a) {:run-id run-id :namespace 'ns-a})
    (a/write-owner-marker! (:namespace-root cfg-b) {:run-id run-id :namespace 'ns-b})

    ;; 1. missing scope binding
    (check "write! without a scope is rejected"
           (try (a/write! {:logical-id :x :relative-path "a.edn" :content "x"})
                false
                (catch clojure.lang.ExceptionInfo e
                  (str/includes? (str (ex-message e)) "no artifact scope bound")))
           :missing-scope)

    ;; 2. path escapes rejected
    (let [escapes [[:traversal "../../foreign.edn"]
                   [:traversal "a/../../foreign.edn"]
                   [:absolute "/etc/passwd"]
                   [:windows-abs "C:\\evil\\x.edn"]
                   [:backslash "sub\\..\\evil.edn"]]]
      (doseq [[label p] escapes]
        (check (str "escape rejected: " label)
               (try
                 (a/resolve-confined (:namespace-root cfg-a) p)
                 false
                 (catch clojure.lang.ExceptionInfo e
                   (= :escape (:reason (ex-data e)))))
               p)))

    ;; 3. symlink escape rejected
    (let [outside (str root "/outside-dir")
          link (str (:namespace-root cfg-a) "/link-out")]
      (.mkdirs (io/file outside))
      (io/make-parents link)
      (java.nio.file.Files/createSymbolicLink (java.nio.file.Paths/get link (make-array String 0))
                                              (java.nio.file.Paths/get outside (make-array String 0))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (check "symlink escape rejected"
             (try (a/resolve-confined (:namespace-root cfg-a) "link-out/x.edn")
                  false
                  (catch clojure.lang.ExceptionInfo e
                    (= :escape (:reason (ex-data e)))))
             :symlink))

    ;; 4. confined write within a scope
    (let [[r scope-a] (a/with-scope cfg-a (fn [] (a/write! {:logical-id :e1
                                                            :relative-path "evidence/e1.edn"
                                                            :content {:a 1 :b #{2 3}}})))]
      (check "confined write publishes and records manifest"
             (and (= :published (:status r))
                  (.exists (io/file (:namespace-root cfg-a) "evidence/e1.edn"))
                  (= 1 (count (:artifacts @scope-a))))
             (:status r))
      (check "manifest records logical id and hash"
             (= #{:e1} (set (map :logical-id (:artifacts @scope-a))))
             (select-keys (first (:artifacts @scope-a)) [:logical-id :content-hash :publication-status])))

    ;; 5. idempotent reuse of identical content
    (let [[r2 _] (a/with-scope cfg-a (fn [] (a/write! {:logical-id :e1
                                                       :relative-path "evidence/e1.edn"
                                                       :content {:a 1 :b #{2 3}}})))]
      (check "identical content is idempotent reuse"
             (= :reused (:status r2))
             (:status r2)))

    ;; 6. different bytes under same identity → conflict
    (check "different content under same identity is a conflict"
           (try
             (a/with-scope cfg-a (fn [] (a/write! {:logical-id :e1
                                                   :relative-path "evidence/e1.edn"
                                                   :content {:a 999}})))
             false
             (catch clojure.lang.ExceptionInfo e
               (= :content-addressed-conflict (:reason (ex-data e)))))
           :conflict)

    ;; 7. cross-namespace write into another namespace's root is impossible
    ;;    (relative paths confined to own root; absolute path rejected)
    (check "cannot write into another namespace's root via absolute path"
           (try
             (a/with-scope cfg-b (fn [] (a/write! {:logical-id :evil
                                                   :relative-path (str (:namespace-root cfg-a) "/x.edn")})))
             false
             (catch clojure.lang.ExceptionInfo e
               (= :escape (:reason (ex-data e)))))
           :cross-ns)

    ;; 8. writer-after-close rejected
    (let [scope (atom nil)
          _ (a/with-scope cfg-a (fn [] (reset! scope a/*scope*)))
          _ (a/finalize-scope! @scope false)]
      (check "write after scope close rejected"
             (try
               (binding [a/*scope* @scope] (a/write! {:logical-id :late
                                                      :relative-path "late.edn"
                                                      :content "late"}))
               false
               (catch clojure.lang.ExceptionInfo e
                 (str/includes? (str (ex-message e)) "not active")))
             :closed))

    ;; 9. closure verification flags undeclared files + temp files (non-strict)
    (let [cfg-d (scope-config root run-id 'ns-d 3)
          nsd-root (:namespace-root cfg-d)
          _ (.mkdirs (io/file (str nsd-root "/sub")))
          scope-d (atom nil)]
      (a/write-owner-marker! nsd-root {:run-id run-id :namespace 'ns-d})
      (a/with-scope cfg-d
        (fn []
          (reset! scope-d a/*scope*)
          (a/write! {:logical-id :d1 :relative-path "d1.edn" :content "d1"})))
      ;; bypass the API: write directly + drop a temp file
      (spit (io/file nsd-root "sneaky.edn") "sneaky")
      (spit (io/file nsd-root "sub/.tmp-partial.edn") "partial")
      (let [manifest (a/finalize-scope! @scope-d false)]
        (check "undeclared direct write is reported at closure"
               (contains? (set (:undeclared-files manifest)) "sneaky.edn")
               (:undeclared-files manifest))
        (check "temporary files are reported at closure"
               (some #(str/starts-with? (.getName (io/file (str nsd-root "/" %))) ".tmp-")
                     (:temporary-files manifest))
               (:temporary-files manifest))))

    ;; 10. strict closure fails on undeclared files
    (let [cfg-e (scope-config root run-id 'ns-e 4)
          nse-root (:namespace-root cfg-e)
          scope-e (atom nil)]
      (a/write-owner-marker! nse-root {:run-id run-id :namespace 'ns-e})
      (a/with-scope cfg-e
        (fn []
          (reset! scope-e a/*scope*)
          (a/write! {:logical-id :e1 :relative-path "e1.edn" :content "e1"})))
      (spit (io/file nse-root "rogue.edn") "rogue")
      (check "strict closure hard-fails on undeclared files"
             (try
               (a/finalize-scope! @scope-e true)
               false
               (catch clojure.lang.ExceptionInfo e
                 (some #(= :undeclared-files (:type %)) (:problems (ex-data e)))))
             :strict-undeclared))

    ;; 11. safe-delete refuses unowned / mismatched run roots
    (check "cleanup refuses unowned root"
           (try (a/safe-delete! (str root "/ns-f") run-id)
                false
                (catch clojure.lang.ExceptionInfo e
                  (str/includes? (str (ex-message e)) "unowned")))
           :unowned)
    (check "cleanup refuses mismatched run-id"
           (try (a/safe-delete! (:namespace-root cfg-a) "wrong-run-id")
                false
                (catch clojure.lang.ExceptionInfo e
                  (str/includes? (str (ex-message e)) "run-id mismatch")))
           :mismatch)

    (a/safe-delete! root run-id)))

(defn -main
  [& _]
  (run-checks)
  (let [fails (remove :pass @results)]
    (println)
    (if (seq fails)
      (do
        (doseq [f fails] (println (str "FAILED: " (:name f) " — " (pr-str (:detail f)))))
        (println (str (count fails) "/" (count @results) " checks failed"))
        (System/exit 1))
      (println (str "artifact-scope tests: all " (count @results) " checks passed")))))
