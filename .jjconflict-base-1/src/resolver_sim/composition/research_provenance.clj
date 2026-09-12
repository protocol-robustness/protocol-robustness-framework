(ns resolver-sim.composition.research-provenance
  "Composition-owned admission adapter for CC3 command lineage at the research
   boundary.

   This adapter does not create a research command or persist a new composition
   artifact. It verifies the existing command-built-with-includes, combination,
   and consecutive-concatenation contracts, then returns the canonical command
   identity and provenance roots that a research boundary may bind."
  (:require [resolver-sim.composition.command-lineage :as lineage]))

(defn- fail!
  [reason details]
  (throw (ex-info "Executable command provenance is not admissible"
                  (assoc details :reason reason))))

(defn- expected-concatenations
  [commands]
  (lineage/build-concatenation-chain commands))

(defn- verify-combination!
  [command combination]
  (when-not (lineage/combination-valid? combination)
    (fail! :invalid-combination
           {:combination/root (:combination/root combination)}))
  (let [computed-root (lineage/combination-root
                       (:combination/built-with-includes combination))
        command-root (lineage/combination-root
                      (:command/built-with-includes command))]
    (when-not (= computed-root (:combination/root combination))
      (fail! :combination-root-mismatch
             {:declared (:combination/root combination)
              :computed computed-root}))
    (when-not (= command-root (:combination/root combination))
      (fail! :include-composition-substitution
             {:command/root (:command/root command)
              :command/combination-root command-root
              :combination/root (:combination/root combination)}))
    command-root))

(defn verified-executable-command-provenance!
  "Verify existing CC3 records and return their research-boundary provenance.

   Input is {:commands [command ...]
             :concatenations [concatenation ...]
             :combination combination}.

   `:commands` is ordered and non-empty. `:concatenations` must be the exact
   ordered CC3 concatenation artifacts for every adjacent command pair. The
   supplied `:combination` must be the concrete command-built-with-includes
   record for the terminal command. No caller-provided root is trusted: every
   returned root is re-derived or verified from these existing contracts."
  [{:keys [commands concatenations combination]}]
  (let [commands (vec commands)
        concatenations (vec concatenations)]
    (when (empty? commands)
      (fail! :missing-commands {}))
    (let [lineage-check (lineage/verify-lineage commands)
          expected (expected-concatenations commands)
          resolved (into {} (map (juxt :command/root identity)) commands)
          supplied-chain-check (lineage/verify-concatenation-chain concatenations resolved)]
      (when-not (and (:valid? lineage-check) (= :ok (:status lineage-check)))
        (fail! :invalid-command-lineage
               {:status (:status lineage-check)
                :errors (:errors lineage-check)}))
      (let [expected-roots (mapv :concatenation/root expected)
            actual-roots (mapv :concatenation/root concatenations)]
        (when-not (= expected-roots actual-roots)
          (if (= (frequencies expected-roots) (frequencies actual-roots))
            (fail! :reordered-concatenations
                   {:expected expected-roots :actual actual-roots})
            (do
              (when-not (:valid? supplied-chain-check)
                (fail! :broken-concatenation-continuity
                       {:issues (:issues supplied-chain-check)}))
              (fail! :substituted-concatenations
                     {:expected expected-roots :actual actual-roots}))))
        (when-not (:valid? supplied-chain-check)
          (fail! :broken-concatenation-continuity
                 {:issues (:issues supplied-chain-check)})))
      (let [command (peek commands)
            combination-root (verify-combination! command combination)]
        {:command/root (:command/root command)
         :command/combination-root combination-root
         :command/input-state-root (:command/input-state-root command)
         :command/resulting-state-root (:command/resulting-state-root command)
         :command/concatenation-roots (mapv :concatenation/root concatenations)
         :command/concatenation-chain-root
         (when (seq concatenations)
           (lineage/concatenation-chain-root
            (mapv :concatenation/root concatenations)))}))))
