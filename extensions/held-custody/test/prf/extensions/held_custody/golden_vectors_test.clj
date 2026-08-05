(ns prf.extensions.held-custody.golden-vectors-test
  "Golden (pinned) vectors for the newly published force-auth-held-custody
   mutation .v1 contracts.

   Rule: no .v1 schema is considered published until its canonical preimage and
   at least one golden hash are checked into tests. These vectors freeze the
   deterministic content hashes and preimages; any change to the member body,
   the scope projection, the envelope, or the summary derivation changes these
   values and must be an intentional, reviewed contract change."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.extensions.manifest :as em]
            [prf.extensions.held-custody.mutation :as mut]
            [prf.extensions.held-custody.aggregate :as agg]
            [prf.extensions.held-custody.manifest :as manifest]))

(defn- scope [id dir amt]
  {:authorization/id id
   :authorization/type :force-authorisation
   :held/direction dir
   :token "USDC"
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- auth [id dir amt]
  (let [s (scope id dir amt)]
    {:authorization/id id
     :authorization/status :active
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash
                                (fa/normalize-force-authorisation-scope s))
     :authorization/scope (fa/normalize-force-authorisation-scope s)
     :starts-at 0
     :expires-at 1000}))

(defn- mk [mutation-id action direction amount auth-id]
  (mut/build-force-auth-held-mutation
   (auth auth-id direction amount)
   {:mutation/id mutation-id
    :held/action action
    :held/direction direction
    :held/amount amount
    :held/token "USDC"
    :held/account :escrow-principal
    :owner/address "0xrecipient"
    :held/reason :force-authorised-release
    :held/workflow-id 0}
   {}))

(deftest golden-member-hashes
  (is (= "sha256:bf5cb8b2368444183b475f6e14a4fdb4cd018f160bb6f83cea752b33b67eacf4"
         (:artifact/hash (mk "m-add" :add-held :in 100 "fa-0")))
      "inward add-held mutation hash is frozen")
  (is (= "sha256:13856f5f0c1439aca813f72780c638710aa40d3686168186d543b96faddf30e0"
         (:artifact/hash (mk "m-sub" :sub-held :out 40 "fa-1")))
      "outward :sub-held mutation hash is frozen")
  (is (= "sha256:7240b3c0c0d05b8aff9e543966d71a40635c329dc320bd2884b94fec7820610e"
         (:artifact/hash (mk "m-fin" :finalize-released :out 25 "fa-2")))
      ":finalize-released mutation hash is frozen"))

(deftest golden-member-preimage
  (is (= "{:authorization-scope/projection-hash \"6be08128ab70db8e4730a442e2165cb51cdab05d2121b6450ee2ea50252688dd\", :held/position-id nil, :artifact/kind :force-auth-held-custody-mutation, :schema-version \"force-auth-held-custody-mutation.v1\", :held/direction :in, :mutation/id \"m-add\", :held/consumed-by nil, :held/workflow-id 0, :held/reason :force-authorised-release, :held/amount 100, :owner/address \"0xrecipient\", :authorization/type :force-authorisation, :held/consumed-at nil, :held/account :escrow-principal, :authorization/id \"fa-0\", :held/token :USDC, :artifact/verifier \"force-auth-held-custody-mutation.verifier.v1\", :held/action :add-held, :authorization-scope/projection {:amount 100, :operation :held-custody-mutation, :held/direction :in, :held/workflow-id 0, :held/reason :force-authorised-release, :token :USDC, :owner/address \"0xrecipient\", :authorization/type :force-authorisation, :held/account :escrow-principal, :authorization/id \"fa-0\"}}"
         (:artifact/preimage (mk "m-add" :add-held :in 100 "fa-0")))
      "the canonical inward-mutation preimage is frozen"))

(deftest golden-summary-hashes
  (is (= "sha256:60ae2b21c7aba6f1e6ccb280147fadf9e47407aa4da13bf1d08fc6e30bdac024"
         (:artifact/hash
          (agg/build-held-mutation-summary
           [(mk "m-add" :add-held :in 100 "fa-0")
            (mk "m-fin" :finalize-released :out 40 "fa-1")] {})))
      "mixed-direction summary hash is frozen")
  (is (= "sha256:3e7f98e325221a2f414ea3a65f8a5bc5f0930773f5ebfa591a205328b46e9a0f"
         (:artifact/hash
          (agg/build-held-mutation-summary
           [(mk "m-in" :add-held :in 30 "fa-0")
            (mk "m-out" :sub-held :out 50 "fa-1")] {})))
      "negative net-change summary hash is frozen")
  (is (= -20 (:net-change
              (agg/build-held-mutation-summary
               [(mk "m-in" :add-held :in 30 "fa-0")
                (mk "m-out" :sub-held :out 50 "fa-1")] {})))
      "the negative-net fixture actually has negative :net-change"))

(deftest golden-manifest-roots
  (is (= "f2823c53426f0040a72acb7d27bf917b33285132a0157c41cf5bcd457f1fba17"
         (em/capability-descriptor-root manifest/capability))
      "capability descriptor root is pinned; manifest contract changes are reviewed")
  (is (= "5ae2303f72b7b507c9a33f31d0c65fb60de3ddcee00c98969d6b28680cf2aa4c"
         (em/package-root manifest/package))
      "package root is pinned; manifest contract changes are reviewed"))
