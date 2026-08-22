(ns resolver-sim.resubmission.genesis-authorization
  "Canonical resubmission-chain-genesis-authorization.v1: the governance
   authorization layer that binds an exact genesis root to an authenticated
   three-member researcher authority decision.

   This namespace implements Stage 3 of the staged genesis plan (design §15):
   governance authorization. It reuses the EXISTING three-member researcher
   authority evaluated through review-governance.v1 (root-addressed governance),
   exposed via verify-governed-authority in
   resolver-sim.assurance.governed-authority-consumer.

   Authority boundary (design §6): physical runtime instantiation remains an
   unrestricted local operation (new-chain, new-chain-from-genesis). Only
   'authoritative chain genesis' — one whose genesis root is bound to a
   genuine three-member authority decision — is governed.

   Hierarchy (design §14):
     declared (genesis) -> validated (genesis) -> authentically authorized
     (this artifact) -> authoritative realization (admit-chain-from-authorization)

   The authorization artifact is intentionally thin: it contains only the
   genesis-root reference, the force-authorisation hash, and the authority
   report root. All genesis content (family-id, chain-id, configuration, G0)
   is transitively committed by the genesis root.

   validated != authoritative. A validated genesis without a verified
   authorization is NOT authoritative. Authorization must be evidenced by a
   separate verifiable artifact (this namespace)."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.assurance.governed-authority-consumer :as gac]))

;; Schema constants

(def ^:const authorization-schema
  "Schema identifier for resubmission-chain-genesis-authorization.v1."
  "resubmission-chain-genesis-authorization.v1")

(def ^:const authorization-fields
  "Ordered identity fields of resubmission-chain-genesis-authorization.v1.
   Must match hc/resubmission-chain-genesis-authorization-fields."
  [:authorization/schema
   :authorization/genesis-root
   :authorization/force-authorisation-hash
   :authorization/authority-report-root])

;; Validation

(defn validate-genesis-authorization
  "Strict, closed-shape validator for resubmission-chain-genesis-authorization.v1.

   Returns {:valid? bool :errors [...]}"
  ([authz] (validate-genesis-authorization authz nil))
  ([authz genesis]
   (let [errors (atom [])
         report! (fn [& msgs] (swap! errors #(into % msgs)))]
     (when-not (map? authz)
       (report! "resubmission-chain-genesis-authorization must be a map"))
     (when (map? authz)
       (let [have    (set (keys authz))
             extra   (set/difference have (set authorization-fields))
             missing (set/difference (set authorization-fields) have)]
         (when (seq extra)
           (report! (str "unknown authorization keys: " (sort extra))))
         (when (seq missing)
           (report! (str "missing authorization keys: " (sort missing))))
         (when-not (= authorization-schema (:authorization/schema authz))
           (report! (str "authorization/schema must be " authorization-schema
                         ", got " (pr-str (:authorization/schema authz)))))
         (doseq [f [:authorization/genesis-root
                    :authorization/force-authorisation-hash
                    :authorization/authority-report-root]]
           (let [v (get authz f)]
             (cond
               (nil? v)
               (report! (str f " must not be nil"))
               (not (hash-ref/valid-sha256-ref? v))
               (report! (str f " must be a valid sha256 reference, got "
                             (pr-str v))))))))
       ;; genesis-root cross-check
     (when (and (map? authz) genesis)
       (let [gv (genesis/validate-resubmission-chain-genesis genesis)]
         (when-not (:valid? gv)
           (report! (str "referenced genesis is not canonically valid: "
                         (:errors gv))))
         (when (:valid? gv)
           (let [computed (genesis/resubmission-chain-genesis-root genesis)
                 declared (:authorization/genesis-root authz)]
             (when (and (hash-ref/valid-sha256-ref? declared)
                        (not= computed declared))
               (report! (str "authorization/genesis-root does not match "
                             "recomputed genesis root: declared "
                             (pr-str declared) " computed "
                             (pr-str computed))))))))
     {:valid? (empty? @errors) :errors (vec @errors)})))

(defn genesis-authorization-valid?
  "Quick boolean structural validity check (without genesis cross-check)."
  [authz]
  (:valid? (validate-genesis-authorization authz)))

;; Root computation

(defn genesis-authorization-root
  "Compute the canonical SHA-256 root of a
   resubmission-chain-genesis-authorization.v1.

   Validates strict closed-shape first (fail-closed)."
  [authz]
  (let [v (validate-genesis-authorization authz)]
    (when-not (:valid? v)
      (throw (ex-info "resubmission-chain-genesis-authorization.v1 is invalid"
                      {:type   :authorization/invalid
                       :schema authorization-schema
                       :errors (:errors v)}))))
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-resubmission-chain-genesis-authorization-v1
                   (hc/project-resubmission-chain-genesis-authorization
                    authz :prf-resubmission-chain-genesis-authorization-v1))))

;; Authoritative verification

(defn verify-genesis-authorization
  "Fail-closed verifier. See docstring online."
  [genesis authz package-resolver context]
  (let [errors (atom [])
        report! (fn [& msgs] (swap! errors #(into % msgs)))
        auth-result (atom nil)]

    ;; Gate 1: structural validation
    (let [sv (validate-genesis-authorization authz genesis)]
      (when-not (:valid? sv)
        (report! (:errors sv))))

    ;; Gate 2: Genesis canonical validity
    (when (empty? @errors)
      (let [gv (genesis/validate-resubmission-chain-genesis genesis)]
        (when-not (:valid? gv)
          (report! (:errors gv)))))

    ;; Gate 3-5: Resolve and validate the force-authorisation artifact
    (when (empty? @errors)
      (let [fa-hash (:authorization/force-authorisation-hash authz)
            fa      (when (fn? package-resolver)
                      (package-resolver fa-hash))]
        (when-not fa
          (report! (str "force-authorisation artifact not found by hash: "
                        fa-hash)))
        (when fa
          (let [fa-validation (rfa/validate-authorisation fa)]
            (when-not (:valid? fa-validation)
              (report! (:errors fa-validation)))
            ;; Gate 4: FA must be approved
            (when-not (rfa/authorisation-approved? fa)
              (report! (str "force-authorisation decision-status is "
                            (pr-str (:authorisation/decision-status fa))
                            ", expected :approved or :approved-with-dissent")))
            ;; Gate 5: FA target must bind the exact genesis root
            (let [genesis-root (:authorization/genesis-root authz)
                  target-root  (get-in fa [:authorisation/target
                                           :target/proposed-content-root])]
              (when (or (nil? target-root) (not= target-root genesis-root))
                (report! (str "force-authorisation target/proposed-content-root "
                              "does not match genesis-root: target "
                              (pr-str target-root) " genesis "
                              (pr-str genesis-root)))))))))

    ;; Gate 6-7: Evaluate the governed three-member authority
    (when (empty? @errors)
      (let [fa-hash    (:authorization/force-authorisation-hash authz)
            fa         (package-resolver fa-hash)
            round-hash (get-in fa [:authorisation/review-round
                                   :review-round/hash])]
        (when-not round-hash
          (report! (str "force-authorisation has no :authorisation/review-round "
                        ":review-round/hash")))
        (when round-hash
          (let [result (gac/verify-governed-authority context fa round-hash)]
            (reset! auth-result result)
            (when-not (:valid? result)
              (report! "governed authority verification failed"))
            ;; Gate 7: Authority report root must match
            (when (:valid? result)
              (let [expected-report-root (:authorization/authority-report-root authz)
                    actual-report-root   (:authority-report-root result)]
                (when (not= actual-report-root expected-report-root)
                  (report! (str "authority-report-root mismatch: declared "
                                (pr-str expected-report-root)
                                " computed "
                                (pr-str actual-report-root))))))))))

    ;; Final: recompute the authorization artifact root
    (when (empty? @errors)
      (try
        (genesis-authorization-root authz)
        (catch Exception e
          (report! (str "authorization root computation failed: "
                        (.getMessage e))))))

    ;; Return result
    (if (empty? @errors)
      {:valid?              true
       :genesis-root        (:authorization/genesis-root authz)
       :force-authorisation-hash (:authorization/force-authorisation-hash authz)
       :authority-report-root    (:authorization/authority-report-root authz)
       :governance-root     (:governance-root @auth-result)}
      {:valid? false :errors (vec @errors)})))
