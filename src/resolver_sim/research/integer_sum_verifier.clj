(ns resolver-sim.research.integer-sum-verifier
  "Narrow deterministic method verifier used to qualify one integer-addition
   conclusion fixture. This is not a general research verifier."
  (:require [resolver-sim.benchmark.research-conclusion :as conclusion]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]))

(def evidence-schema "research-integer-sum-evidence.v1")

(def verifier-descriptor
  {:schema/version "research-method-verifier-descriptor.v1"
   :verifier/id :verifier/research-integer-sum-v1
   :verifier/method :checked-signed-64-bit-integer-addition
   :verifier/version 1})

(def verifier-root
  (reference/sha256-ref
   (canonical/domain-hash :research-method-verifier-descriptor-v1
                          verifier-descriptor)))

(defn evidence-root [evidence]
  (reference/sha256-ref
   (canonical/domain-hash :research-integer-sum-evidence-v1
                          (dissoc evidence :method/evidence-root))))

(defn build-evidence
  "Build a deterministic observation record. The observed result is an input,
   not assumed correct; verify-evidence recomputes it."
  [{:keys [left right observed-result]}]
  (let [body {:schema/version evidence-schema
              :method/verifier-root verifier-root
              :method/left left
              :method/right right
              :method/observed-result observed-result}]
    (assoc body :method/evidence-root (evidence-root body))))

(defn validate-evidence [evidence]
  (let [errors (cond-> []
                 (not= evidence-schema (:schema/version evidence))
                 (conj :unsupported-evidence-schema)
                 (not= #{:schema/version :method/verifier-root :method/left
                         :method/right :method/observed-result :method/evidence-root}
                       (set (keys evidence)))
                 (conj :invalid-evidence-fields)
                 (not= verifier-root (:method/verifier-root evidence))
                 (conj :unrecognized-verifier-root)
                 (not-every? #(and (integer? %) (<= Long/MIN_VALUE % Long/MAX_VALUE))
                             [(:method/left evidence) (:method/right evidence)
                              (:method/observed-result evidence)])
                 (conj :integer-input-out-of-range)
                 (not= (:method/evidence-root evidence) (evidence-root evidence))
                 (conj :evidence-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn verify-evidence [evidence]
  (let [validity (validate-evidence evidence)
        sum (when (:valid? validity)
              (try
                (Math/addExact (long (:method/left evidence))
                               (long (:method/right evidence)))
                (catch ArithmeticException _ ::overflow)))
        errors (cond-> (:errors validity)
                 (= ::overflow sum) (conj :addition-overflow)
                 (and (:valid? validity) (not= sum (:method/observed-result evidence)))
                 (conj :observed-result-mismatch))]
    {:valid? (empty? errors)
     :errors errors
     :computed-result (when-not (= ::overflow sum) sum)}))

(defn method-verifier
  "Return a conclusion qualifier callback over one exact evidence artifact."
  [evidence]
  (fn [{:keys [conclusion supporting-theorems]}]
    (let [verification (verify-evidence evidence)
          result (get-in conclusion [:conclusion/result :y])
          expected {:operation :integer-addition
                    :left (:method/left evidence)
                    :right (:method/right evidence)
                    :value (:computed-result verification)}
          scoped? (= (:method/evidence-root evidence)
                     (get-in conclusion [:conclusion/scope :method/evidence-root]))
          supported? (empty? supporting-theorems)]
      (if (and (:valid? verification)
               (= :conclusion/integer-sum (:conclusion/id conclusion))
               (= expected result)
               scoped?
               supported?)
        {:status :verified
         :conclusion/root (:conclusion/hash conclusion)
         :evidence/root (:method/evidence-root evidence)
         :verifier/root verifier-root}
        {:status :invalid
         :error (cond
                  (not (:valid? verification)) (str "invalid arithmetic evidence: " (:errors verification))
                  (not= expected result) "conclusion result differs from recomputed sum"
                  (not scoped?) "conclusion scope is not bound to the evidence root"
                  (not supported?) "fixture verifier does not interpret supporting theorem inputs"
                  :else "unsupported conclusion identity")}))))

(defn build-fixture-conclusion
  "Build the exact, bounded conclusion matched by this verifier."
  [evidence]
  (let [{:keys [valid? computed-result]} (verify-evidence evidence)]
    (when-not valid?
      (throw (ex-info "Cannot build conclusion from invalid arithmetic evidence"
                      {:reason :invalid-method-evidence})))
    (conclusion/build-conclusion
     {:conclusion/id :conclusion/integer-sum
      :conclusion/premise {:x "The committed signed 64-bit integers are the method inputs."}
      :conclusion/result
      {:y {:operation :integer-addition
           :left (:method/left evidence)
           :right (:method/right evidence)
           :value computed-result}}
      :conclusion/status :established
      :conclusion/scope {:method/evidence-root (:method/evidence-root evidence)}
      :conclusion/qualifications ["This result applies only to the committed integer inputs."]})))
