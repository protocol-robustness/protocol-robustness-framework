(ns resolver-sim.reference.bounded-transfer
  "A small protocol-neutral PRF reference adapter.

   This namespace intentionally depends only on Clojure/JDK facilities.  It
   demonstrates that PRF packages both an accepted run and a semantic rejection
   without relying on a protocol-specific runtime."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def policy-closure
  {:policy/id :bounded-transfer/v1
   :policy/description "A sender may transfer a positive amount no greater than its offered bound."
   :policy/terminal-states #{:accepted :semantic-failure}
   :policy/rules [{:rule/id :positive-amount
                   :rule/description "Accepted transfers must have a positive amount."}
                  {:rule/id :within-offered-bound
                   :rule/description "Accepted transfers must not exceed the offered bound."}
                  {:rule/id :sender-balance
                   :rule/description "Accepted transfers must not exceed the sender balance."}]})

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical-value v)]) value))
    (set? value) (into (sorted-set) (map canonical-value value))
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn canonical-edn [value]
  (str (pr-str (canonical-value value)) "\n"))

(defn- sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes text "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn evaluate [{:keys [scenario/id participants events] :as scenario}]
  (let [balances (or (:balances participants) {})
        offer (first (filter #(= :offer (:event/type %)) events))
        accept (first (filter #(= :accept (:event/type %)) events))
        sender (:from offer)
        receiver (:to offer)
        amount (:amount accept)
        bound (:max-amount offer)
        sender-balance (get balances sender)
        failures (cond-> []
                   (nil? offer) (conj {:rule :offer-present :message "No offer event was supplied."})
                   (nil? accept) (conj {:rule :accept-present :message "No accept event was supplied."})
                   (and offer accept (not= sender (:from accept))) (conj {:rule :same-sender :message "Acceptance sender differs from offer sender."})
                   (and offer accept (not= receiver (:to accept))) (conj {:rule :same-receiver :message "Acceptance receiver differs from offer receiver."})
                   (and accept (not (and (integer? amount) (pos? amount)))) (conj {:rule :positive-amount :message "Accepted amount must be a positive integer."})
                   (and offer accept (number? amount) (number? bound) (> amount bound)) (conj {:rule :within-offered-bound :message "Accepted amount exceeds the offered bound."})
                   (and offer accept (number? amount) (number? sender-balance) (> amount sender-balance)) (conj {:rule :sender-balance :message "Accepted amount exceeds the sender balance."}))]
    {:scenario/id id
     :adapter/id :bounded-transfer/v1
     :initial-state {:balances balances}
     :final-state (if (empty? failures)
                    {:balances (-> balances
                                   (update sender - amount)
                                   (update receiver (fnil + 0) amount))}
                    {:balances balances})
     :semantic {:status (if (empty? failures) :accepted :semantic-failure)
                :violations failures}}))

(defn package [scenario]
  (let [result (evaluate scenario)
        body {:package/schema :prf.reference-package/v1
              :adapter/id :bounded-transfer/v1
              :scenario scenario
              :policy/closure policy-closure
              :result result}
        canonical (canonical-edn body)]
    (assoc body :package/sha256 (sha256 canonical))))

(defn verify-package [package]
  (let [stored (:package/sha256 package)
        body (dissoc package :package/sha256)
        digest-ok? (= stored (sha256 (canonical-edn body)))
        recomputed (evaluate (:scenario package))
        semantics-ok? (= recomputed (:result package))
        policy-ok? (= policy-closure (:policy/closure package))]
    {:valid? (and digest-ok? semantics-ok? policy-ok?)
     :digest-ok? digest-ok?
     :semantics-ok? semantics-ok?
     :policy-ok? policy-ok?}))

(defn- read-edn [path] (edn/read-string (slurp path)))
(defn- write-package! [path package]
  (io/make-parents (io/file path))
  (spit path (canonical-edn package)))

(defn -main [& args]
  (let [[command input output] args]
    (case command
      "package" (let [package (package (read-edn input))]
                  (write-package! output package)
                  (println "PACKAGED" (:scenario/id (:scenario package))
                           (name (get-in package [:result :semantic :status])) output)
                  0)
      "verify" (let [result (verify-package (read-edn input))]
                 (println (if (:valid? result) "VERIFIED" "INVALID") input)
                 (if (:valid? result) 0 1))
      (do (binding [*out* *err*]
            (println "Usage: bounded-transfer package <scenario.edn> <package.edn> | verify <package.edn>"))
          2))))
