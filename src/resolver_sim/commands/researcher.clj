(ns resolver-sim.commands.researcher
  "Researcher interaction commands for the PRF CLI.

  Commands:
    researcher disagree  — sign a dissenting (:dissent) decision
    researcher approve   — sign an approving (:approve) decision
    researcher check     — check force-authorisation usability

  All researcher identity is via integer review-member/key resolved through
  the committed review round — no parallel string-keyed representation."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.assurance.force-authorisation :as fa]))

(defn- stderr
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines] (println line))))

(defn- read-input
  "Read an EDN input document from a file path, or stdin when path is \"-\" or
   absent. Returns the parsed value, or nil on failure."
  [path]
  (let [source (if (or (nil? path) (= "-" path))
                 (io/reader *in*)
                 (try
                   (io/reader path)
                   (catch Exception _ nil)))]
    (when source
      (try
        (edn/read-string (slurp source))
        (catch Exception _
          nil)))))

(defn- print-json
  [value]
  (println (json/write-str value :escape-ascii false)))

(defn- resolve-researcher-by-key
  "Resolve an integer review-member/key to a researcher-id within a review round.
   Returns {:researcher/id <id> :review-member/key <key>} or nil.
   Validates that the round is keyed and the key is a valid member."
  [round member-key]
  (when (and round member-key (rr/round-uses-member-keys? round))
    (let [member (rr/member-by-key round member-key)]
      (when member
        {:researcher/id (:researcher/id member)
         :review-member/key member-key}))))

(defn- decision-reason
  "The decision implied by the command name."
  [cmd]
  (case cmd
    :disagree :dissent
    :approve :approve))

(declare parse-opts parse-opts-check)

(defn- sign-researcher-decision
  "Shared signing logic for researcher disagree/approve commands.
   Resolves the researcher identity from the integer member-key, then calls
   the authoritative rfa/build-signed-decision or rfa/build-signed-decision-v2."
  [{:keys [cmd]} {:keys [input member-key key dissent-reason outcome-root]}]
  (let [ctx (read-input input)]
    (cond
      (nil? ctx)
      (do (stderr (str "researcher " (name cmd) ": failed to read input"))
          {:exit-code 1 :message "failed to read input"})

      (nil? member-key)
      (do (stderr (str "researcher " (name cmd) ": --member-key is required"))
          {:exit-code 2 :message "--member-key is required"})

      (nil? key)
      (do (stderr (str "researcher " (name cmd) ": --key is required"))
          {:exit-code 2 :message "--key is required"})

      :else
      (let [round (:review-round ctx)
            resolved (resolve-researcher-by-key round member-key)]
        (if-not resolved
          (do (stderr (str "researcher " (name cmd)
                           ": member-key " member-key
                           " not found in keyed review round"))
              {:exit-code 2 :message
               (str "member-key " member-key " not found in keyed review round")})
          (let [researcher-id (:researcher/id resolved)
                decision (decision-reason cmd)
                auth-id (:authorization/id ctx)
                request-root (:authorisation/request-root ctx)
                round-hash (:review-round/hash ctx)]
            (if-not (and researcher-id auth-id request-root round-hash)
              (do (stderr (str "researcher " (name cmd)
                               ": input missing :authorization/id, "
                               ":authorisation/request-root, or :review-round/hash"))
                  {:exit-code 2 :message
                   "input missing :authorization/id, :authorisation/request-root, or :review-round/hash"})
              (try
                (let [signed (if (some? outcome-root)
                               (rfa/build-signed-decision-v2
                                researcher-id auth-id request-root round-hash
                                outcome-root decision key
                                :dissent-reason dissent-reason)
                               (rfa/build-signed-decision
                                researcher-id auth-id request-root round-hash
                                decision key
                                :dissent-reason dissent-reason))]
                  (print-json signed)
                  {:exit-code 0 :decision signed})
                (catch Exception e
                  (stderr (str "researcher " (name cmd) ": " (.getMessage e)))
                  {:exit-code 1 :message (.getMessage e)})))))))))

(defn disagree
  "researcher disagree --input PATH|- --member-key N --key PATH --dissent-reason STR
   Sign a dissenting researcher decision using an integer member-key."
  [opts]
  (let [{:keys [cmd/args]} opts
        parsed (parse-opts args (:input opts)
                           (some-> (:member-key opts) Long/parseLong)
                           (:key opts) (:dissent-reason opts)
                           (:outcome-root opts))]
    (if-let [err (:error parsed)]
      (do (stderr (str "researcher disagree: " err))
          {:exit-code 2 :message err})
      (sign-researcher-decision {:cmd :disagree} parsed))))

(defn approve
  "researcher approve --input PATH|- --member-key N --key PATH [--outcome-root SHA]
   Sign an approving researcher decision using an integer member-key."
  [opts]
  (let [{:keys [cmd/args]} opts
        parsed (parse-opts args (:input opts)
                           (some-> (:member-key opts) Long/parseLong)
                           (:key opts) (:dissent-reason opts)
                           (:outcome-root opts))]
    (if-let [err (:error parsed)]
      (do (stderr (str "researcher approve: " err))
          {:exit-code 2 :message err})
      (sign-researcher-decision {:cmd :approve} parsed))))

(defn- parse-opts
  "Parse CLI args for researcher commands.
   Accepts --input, --member-key, --key, --dissent-reason, --outcome-root, --json."
  [raw-args default-input default-member-key default-key
   default-dissent-reason default-outcome-root]
  (loop [args (vec raw-args)
         input default-input
         member-key default-member-key
         key default-key
         dissent-reason default-dissent-reason
         outcome-root default-outcome-root
         json false]
    (cond
      (empty? args)
      {:input input :member-key member-key :key key
       :dissent-reason dissent-reason :outcome-root outcome-root :json json}

      (str/starts-with? (first args) "--")
      (let [arg (first args)
            val (second args)]
        (cond
          (= arg "--input")
          (recur (subvec args 2) val member-key key dissent-reason outcome-root json)

          (= arg "--member-key")
          (recur (subvec args 2) input (Long/parseLong val) key dissent-reason outcome-root json)

          (= arg "--key")
          (recur (subvec args 2) input member-key val dissent-reason outcome-root json)

          (= arg "--dissent-reason")
          (recur (subvec args 2) input member-key key val outcome-root json)

          (= arg "--outcome-root")
          (recur (subvec args 2) input member-key key dissent-reason val json)

          (= arg "--json")
          (recur (subvec args 1) input member-key key dissent-reason outcome-root true)

          (= arg "--help")
          {:error "use: researcher <disagree|approve|check> --help"}
          :else
          {:error (str "unknown option: " arg)}))

      :else
      {:error (str "unexpected positional argument: " (first args))})))

(defn- classify-usable-result
  "Project the authoritative verify-authorisation-usable result into a
   researcher-interaction outcome classification.
   Does NOT re-implement validation — only projects the existing validator's
   output into interaction classification keywords."
  [auth-result record]
  (let [errors (:errors auth-result)]
    (cond
      (:valid? auth-result)
      :usable

      (some #(= :invalid-parameter-attribution (:code %)) errors)
      :invalid-parameter-attribution

      (some #(= :missing-scope-hash (:code %)) errors)
      :forbidden

      (and (some? record)
           (contains? #{:approved :approved-with-dissent}
                      (:authorisation/decision-status record)))
      :forbidden-authorized

      :else
      :forbidden)))

(defn check
  "researcher check --input PATH|-
   Check force-authorisation usability using the authoritative
   assurance.force-authorisation/verify-authorisation-usable validator.
   Projects the result into interaction classifications:
    :usable, :forbidden, :forbidden-authorized, :invalid-parameter-attribution."
  [opts]
  (let [{:keys [cmd/args]} opts
        parsed (parse-opts-check args (:input opts))]
    (if-let [err (:error parsed)]
      (do (stderr (str "researcher check: " err))
          {:exit-code 2 :message err})
      (let [ctx (read-input (:input parsed))]
        (if (nil? ctx)
          (do (stderr "researcher check: failed to read input")
              {:exit-code 1 :message "failed to read input"})
          (let [record (:authorization/record ctx)
                consumption-registry (:authorization/consumption-registry ctx)
                scope-map (:authorization/scope ctx)
                now-ts (:now-ts ctx
                                (long (System/currentTimeMillis)))
                auth-result (fa/verify-authorisation-usable
                             record consumption-registry scope-map now-ts)
                outcome (classify-usable-result auth-result record)]
            (print-json {:outcome outcome
                         :valid? (:valid? auth-result)
                         :blocking-reasons (map :detail (:errors auth-result))
                         :error-codes (map :code (:errors auth-result))})
            {:exit-code (if (= :usable outcome) 0 1)
             :message (name outcome)}))))))

(defn- parse-opts-check
  "Parse CLI args for the researcher check command."
  [raw-args default-input]
  (loop [args (vec raw-args)
         input default-input
         json false]
    (cond
      (empty? args)
      {:input input :json json}

      (str/starts-with? (first args) "--")
      (let [arg (first args)
            val (second args)]
        (cond
          (= arg "--input")
          (recur (subvec args 2) val json)

          (= arg "--json")
          (recur (subvec args 1) input true)

          (= arg "--help")
          {:error "use: researcher check --input PATH|-"}
          :else
          {:error (str "unknown option: " arg)}))

      :else
      {:error (str "unexpected positional argument: " (first args))})))
