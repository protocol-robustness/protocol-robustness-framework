(ns resolver-sim.testing.artifact-contract
  "Test-only helper for self-rooted artifact contracts.")

(defmacro defrooted-artifact-contract-tests
  "Define common tests for a self-rooted artifact.

   Required options are :name, :build, :valid?, :root, :recompute, and :tamper.
   The tamper function receives a built artifact and changes committed content
   without changing its stored root."
  [{:keys [name build valid? root recompute tamper]}]
  (let [valid-test (symbol (str name "-valid"))
        deterministic-test (symbol (str name "-root-is-deterministic"))
        tamper-test (symbol (str name "-tampering-is-rejected"))]
    `(do
       (clojure.test/deftest ~valid-test
         (let [artifact# ~build]
           (clojure.test/is (:valid? (~valid? artifact#)))
           (clojure.test/is (= (~root artifact#)
                               (~recompute artifact#)))))
       (clojure.test/deftest ~deterministic-test
         (clojure.test/is (= (~root (~build))
                             (~root (~build)))))
       (clojure.test/deftest ~tamper-test
         (let [artifact# ~build]
           (clojure.test/is (not (:valid? (~valid? (~tamper artifact#))))))))))
