(ns resolver-sim.allocation.cli-test
  "Tests for allocation CLI command registration and dispatch."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.cli.registry :as registry]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(defn- reset-registry-caches
  "Clear the registry defonce caches populated by in-process dispatch runs so
   order-sensitive registry tests elsewhere are not affected."
  []
  (reset! @#'registry/registry-cache nil)
  (reset! @#'registry/path-map-cache nil))

(use-fixtures :each (fn [f] (f) (reset-registry-caches)))

(defn- vectors-json [input]
  (json/write-str input))

(defn- run-command [& args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [exit (dispatch/run (vec args))]
        {:exit exit :out (str out) :err (str err)}))))

(deftest allocation-commands-registered
  (doseq [[id sub] [[:allocation-build-context "build-context"]
                    [:allocation-verify-proposal "verify-proposal"]
                    [:allocation-vectors "vectors"]
                    [:allocation-issue-certificate "issue-certificate"]]]
    (let [cmd (registry/get-command id)]
      (is (some? cmd) (str id))
      (is (= ["allocation" sub] (:command/path cmd)))
      (is (= :native (:command/jar-availability cmd)))
      (is (= :prf (:command/surface cmd)))
      (is (= :jvm (:command/runtime cmd))))))

(deftest allocation-vectors-command-emits-json
  (let [{:keys [exit out err]} (run-command "allocation" "vectors")]
    (is (zero? exit))
    (is (empty? err))
    (let [parsed (json/read-str out)]
      (is (= 23 (count parsed)))
      (is (= "allocation-kernel-vector.v1" (get (first parsed) "vector_version"))))))

(deftest verify-proposal-rejects-trailing-positional-args
  (let [result (run-command "allocation" "verify-proposal" "a.json" "b.json")]
    (is (not= 0 (:exit result)))
    (is (re-find #"Unexpected positional" (str (:out result) (:err result))))))

(deftest verify-proposal-accepts-stdin
  (let [input (fixtures/happy-with-committed)
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err
              *in* (java.io.StringReader. (vectors-json input))]
      (let [exit (dispatch/run ["allocation" "verify-proposal"])]
        (is (zero? exit))
        (is (= "passing" (get (json/read-str (str out)) "result/status")))))))

(deftest issue-certificate-fails-closed-for-rejected-proposal
  (let [invalid (assoc-in (fixtures/happy-with-committed)
                          ["committed" "result-root"]
                          "0x0000000000000000000000000000000000000000000000000000000000000000")
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err
              *in* (java.io.StringReader. (vectors-json invalid))]
      (let [exit (dispatch/run ["allocation" "issue-certificate"])
            result (json/read-str (str out))]
        (is (not (zero? exit)))
        (is (= "rejected" (get result "result/status")))
        (is (false? (get result "certificate/issued?")))
        (is (nil? (get result "certificate/hash"))
            "a rejected proposal must not acquire a certificate identity")
        (is (re-find #"issuance forbidden" (str err)))))))
