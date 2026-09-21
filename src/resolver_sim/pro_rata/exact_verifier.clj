(ns resolver-sim.pro-rata.exact-verifier
  "SP-B: *independent* exact pro-rata verification.

   The allocator (resolver-sim.economics.payoffs and
   resolver-sim.pro-rata.allocation) computes shares; this namespace MUST NOT
   call either. It reconstructs the mathematically expected allocation from a
   canonical request using its own decomposition.

   Core reconstruction and verification functions are delegated to
   resolver-sim.economics.verification — the shared generic layer.  This
   namespace retains the pro-rata-specific frozen corpus, spec version,
   and corpus identity.

   Reuse is limited to a low-level allowlist:
     canonical arithmetic     allowed   (resolver-sim.hash.canonical)
     canonical identity       allowed
     primitive rounding math  allowed   (integer floor/remainder)
     primitive ordering       allowed   (canonical tie-break)
   Forbidden, whether aliased or fully-qualified:
     allocator                (resolver-sim.pro-rata.allocation)
     partial-fill producer    (resolver-sim.yield.partial-fill)
     payoffs allocation       (resolver-sim.economics.payoffs)
     result-derived expected
   No function of a forbidden namespace may be invoked, so a passing verdict does
   not reduce to replaying the producer.

   Exact coverage is only claimed over `supported-policies`; any request outside
   that domain yields {:status :unsupported}, never a silently narrower verdict.

   The frozen corpus (`frozen-corpus`) and its locked identity are hand-derived
   mathematical targets, committed independently of the allocator."
  (:require [resolver-sim.economics.verification :as verification])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Delegated verification (generic layer in resolver-sim.economics.verification)
;; ---------------------------------------------------------------------------

(def supported-policies verification/supported-policies)
(def reconstruct verification/reconstruct)
(def verify-weighted-proportionality verification/verify-weighted-proportionality)

;; ---------------------------------------------------------------------------
;; Frozen spec/corpus identity (hand-derived, independent of the allocator)
;; ---------------------------------------------------------------------------

(def frozen-spec-version "pro-rata-exact-verification.v1")

(defn- sha256-hex
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map (fn [b] (format "%02x" (bit-and (int b) 0xFF))) bytes))))

(declare frozen-corpus)

(defn corpus-identity
  "Locked identity over the frozen corpus + spec version. Any change to the
   corpus, its expected targets, or the spec version changes this value."
  []
  (sha256-hex (pr-str [frozen-spec-version frozen-corpus])))

(def frozen-corpus
  "Frozen, HAND-DERIVED canonical cases. Expected allocations are mathematical
   targets — they are not produced by the allocator.
   :expected is {id -> allocated}; :chain (optional) is the sequence of
   newly-cap-committed id groups across redistribution rounds."
  [{:id :corpus/no-cap-large-remainder
    :request {:amount 7
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order
              :cap-treatment :unallocated
              :items [{:id :a :weight 4}
                      {:id :b :weight 4}
                      {:id :c :weight 2}]}
    :expected {:a 3 :b 3 :c 1}}

   {:id :corpus/canonical-id-tie-break
    :request {:amount 7
              :rounding :floor-with-largest-remainder
              :ordering-policy :canonical-id
              :cap-treatment :unallocated
              :items [{:id :z :weight 4}
                      {:id :a :weight 4}]}
    :expected {:a 4 :z 3}}

   {:id :corpus/cap-unallocated
    :request {:amount 10
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order
              :cap-treatment :unallocated
              :items [{:id :a :weight 5 :cap 2}
                      {:id :b :weight 5}]}
    :expected {:a 2 :b 5}}

   {:id :corpus/redistribute-single
    :request {:amount 10
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order
              :cap-treatment :redistribute
              :items [{:id :a :weight 5 :cap 2}
                      {:id :b :weight 5}]}
    :expected {:a 2 :b 8}
    :chain [[:a]]}

   {:id :corpus/redistribute-all-capped
    :request {:amount 10
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order
              :cap-treatment :redistribute
              :items [{:id :a :weight 5 :cap 4}
                      {:id :b :weight 5 :cap 4}]}
    :expected {:a 4 :b 4}
    :chain [[:a :b]]}

   {:id :corpus/floor-rounding-residual
    :request {:amount 7
              :rounding :floor
              :ordering-policy :input-order
              :cap-treatment :unallocated
              :items [{:id :a :weight 4}
                      {:id :b :weight 4}
                      {:id :c :weight 2}]}
    :expected {:a 2 :b 2 :c 1}}

   {:id :corpus/no-active-weight
    :request {:amount 10
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order
              :cap-treatment :redistribute
              :items [{:id :a :weight 0}
                      {:id :b :weight 0}]}
    :expected {:a 0 :b 0}}])
