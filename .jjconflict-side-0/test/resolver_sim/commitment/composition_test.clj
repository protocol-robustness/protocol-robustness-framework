(ns resolver-sim.commitment.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commitment.composition :as sut]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- digest-ref [digit]
  (hash-ref/sha256-ref (apply str (repeat 64 digit))))

(def members
  [{:member/type :source-artifact
    :member/name :generator-source
    :member/commitment (digest-ref "a")}
   {:member/type :projection-definition
    :member/name :projection-rules
    :member/commitment (digest-ref "b")}
   {:member/type :input-set
    :member/name :holdout-inputs
    :member/commitment (digest-ref "c")}])

(defn composition [members]
  (sut/build-composition {:composition/domain :prf/test-composition.v1
                          :composition/members members}))

(deftest deterministic-canonical-composition
  (let [first-composition (composition members)
        second-composition (composition members)]
    (is (= first-composition second-composition))
    (is (= "prf/commitment-composition.v1" (:artifact/schema first-composition)))
    (is (hash-ref/valid-sha256-ref? (:composition/root first-composition)))
    (is (sut/valid-composition? first-composition))))

(deftest member-and-role-substitution-change-the-root
  (let [baseline (composition members)
        one-nibble-mutation (hash-ref/sha256-ref
                             (str (apply str (repeat 63 "a")) "b"))
        substituted-member (composition (assoc-in members [0 :member/commitment] one-nibble-mutation))
        substituted-role (composition (assoc-in members [0 :member/name] :projection-rules-copy))
        substituted-type (composition (assoc-in members [0 :member/type] :input-set))]
    (is (not= (:composition/root baseline) (:composition/root substituted-member)))
    (is (not= (:composition/root baseline) (:composition/root substituted-role)))
    (is (not= (:composition/root baseline) (:composition/root substituted-type)))))

(deftest builder-canonicalizes-order-and-validator-rejects-noncanonical-artifacts
  (let [baseline (composition members)
        reordered (composition (vec (reverse members)))
        noncanonical (assoc baseline :composition/members
                            (vec (reverse (:composition/members baseline))))]
    (is (= baseline reordered))
    (is (not (sut/valid-composition? noncanonical)))
    (is (some #{:commitment-composition/members-not-canonically-ordered}
              (:errors (sut/validate-composition noncanonical))))))

(deftest duplicate-and-missing-members-fail-closed
  (testing "semantic member names are unique even when types differ"
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition (assoc-in members [1 :member/name] :generator-source)))))
  (testing "a member missing its canonical commitment field is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition [(dissoc (first members) :member/commitment)]))))
  (testing "an empty member vector cannot create a meaningless root"
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition [])))))

(deftest domain-schema-and-root-substitution-reject-or-change-identity
  (let [baseline (composition members)
        other-domain (sut/build-composition {:composition/domain :prf/other-composition.v1
                                             :composition/members members})
        wrong-schema (assoc baseline :artifact/schema "prf/commitment-composition.v2")
        mutated-root (assoc baseline :composition/root (digest-ref "f"))]
    (is (not= (:composition/root baseline) (:composition/root other-domain)))
    (is (not (sut/valid-composition? wrong-schema)))
    (is (some #{:commitment-composition/unsupported-schema-version}
              (:errors (sut/validate-composition wrong-schema))))
    (is (some #{:commitment-composition/root-mismatch}
              (:errors (sut/validate-composition mutated-root))))))

(deftest canonical-framing-rejects-concatenation-interpretation
  (is (not= (vec (hc/canonical-bytes ["A" "BC"]))
            (vec (hc/canonical-bytes ["AB" "C"]))))
  (is (not= (vec (hc/canonical-bytes {:components ["A" "BC"]}))
            (vec (hc/canonical-bytes {:components ["AB" "C"]})))))

(deftest commitment-references-have-one-canonical-text-encoding
  (let [canonical-ref (digest-ref "a")
        bare-hex (subs canonical-ref (count "sha256:"))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition [(assoc (first members) :member/commitment bare-hex)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition [(assoc (first members) :member/commitment
                                      (byte-array 32))])))
    (is (not= (vec (hc/canonical-bytes canonical-ref))
              (vec (hc/canonical-bytes bare-hex))))))

(deftest canonical-round-trip-preserves-the-full-composition
  (let [baseline (composition members)
        bytes (hc/canonical-bytes baseline)
        decoded (:value (fv/decode-one bytes 0))]
    (is (= baseline decoded))
    (is (= (vec bytes) (vec (hc/canonical-bytes decoded))))
    (is (sut/valid-composition? decoded))))

(deftest cleanroom-holdout-composition-vector
  (let [cleanroom-members
        [{:member/type :source-artifact
          :member/name :generator-source
          :member/commitment (hash-ref/sha256-ref "712717322f20a09ad8931da6a474276c9bc1f7811c8daa41333dd0fe375ee77f")}
         {:member/type :projection-definition
          :member/name :projection-rules
          :member/commitment (hash-ref/sha256-ref "a521a3f0038e7a362e241d2be5807b0973286f4d5def00a760bbcbda68753776")}
         {:member/type :input-set
          :member/name :holdout-inputs
          :member/commitment (hash-ref/sha256-ref "eb70b39efae01eb3378ea29d210e1ae2dc49b5f4054cfebc813dd4a4d5699699")}
         {:member/type :expected-output-set
          :member/name :holdout-expected-outputs
          :member/commitment (hash-ref/sha256-ref "8a8f4475bf0a49ab52eddb67912fd6a2cd8d5f3ce73f7b3241593ba762eaf1f9")}]
        composition (sut/build-composition
                     {:composition/domain :prf/cleanroom-realized-partial-fill-v1
                      :composition/members cleanroom-members})]
    (is (sut/valid-composition? composition))
    (is (= 4 (count (:composition/members composition))))
    (is (= "sha256:7e1622532bed353969915888953ebd5f978f6ecd282be0ab3c81aeb91dc7883d"
           (:composition/root composition)))))
