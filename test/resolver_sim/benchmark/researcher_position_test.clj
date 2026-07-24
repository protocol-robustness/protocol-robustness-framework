(ns resolver-sim.benchmark.researcher-position-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-position :as rp]))

(deftest build-position-model-component-dimensions
  (let [pos (rp/build-position
             {:benchmark/content-root "sha256:content"
              :researcher/id "researcher-a"
              :outcome-hash "sha256:outcome"
              :dimensions
              {:reproduction {:status :reproduced}
               :model-state {:status :adequate}
               :model-transitions {:status :adequate :rationale "covers partial-fill cases"}
               :model-authority {:status :contested
                                 :targets [{:kind :authority-policy
                                            :id :position/current-amount-precedence
                                            :component-hash "sha256:policy"}]}
               :model-adversary {:status :incomplete :qualifications ["strategic delay not modelled"]}
               :model-parameters {:status :adequate}
               :model-cases {:status :adequate}
               :incentives-participants {:status :adequate}
               :incentives-strategies {:status :incomplete}
               :incentives-coalitions {:status :omitted}
               :evidence {:status :sufficient}
               :claims {:status :supported}
               :publication {:status :publish-with-qualification}}})]
    (is (rp/position-valid? pos))
    (is (= "researcher-position.v1" (:schema-version pos)))
    (is (= :reproduced (rp/dimension-status pos :reproduction)))
    (is (= :contested (rp/dimension-status pos :model-authority)))
    (is (= :omitted (rp/dimension-status pos :incentives-coalitions)))
    (let [targets (rp/dimension-targets pos :model-authority)]
      (is (= 1 (count targets)))
      (is (= :position/current-amount-precedence (:id (first targets)))))))

(deftest absent-status-not-reviewed
  (let [pos (rp/build-position
             {:benchmark/content-root "sha256:content"
              :researcher/id "researcher-b"
              :outcome-hash "sha256:outcome"
              :dimensions
              {:reproduction {:status :not-reviewed}
               :publication {:status :publish}}})]
    (is (rp/position-valid? pos))
    (is (rp/absent? (rp/dimension-status pos :reproduction)))))

(deftest absent-status-insufficient-information
  (let [pos (rp/build-position
             {:benchmark/content-root "sha256:content"
              :researcher/id "researcher-b"
              :outcome-hash "sha256:outcome"
              :dimensions
              {:reproduction {:status :insufficient-information}
               :publication {:status :publish}}})]
    (is (rp/position-valid? pos))
    (is (rp/absent? (rp/dimension-status pos :reproduction)))))

(deftest absent-status-not-applicable
  (let [pos (rp/build-position
             {:benchmark/content-root "sha256:content"
              :researcher/id "researcher-b"
              :outcome-hash "sha256:outcome"
              :dimensions
              {:reproduction {:status :not-applicable}
               :publication {:status :publish}}})]
    (is (rp/position-valid? pos))))

(deftest build-position-rejects-unknown-dimension
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown position dimensions"
                        (rp/build-position
                         {:benchmark/content-root "sha256:content"
                          :researcher/id "researcher-c"
                          :outcome-hash "sha256:outcome"
                          :dimensions {:nonexistent {:status :ok}}}))))

(deftest invalid-dimension-status-falls-back
  (let [pos (rp/build-position
             {:benchmark/content-root "sha256:content"
              :researcher/id "researcher-c"
              :outcome-hash "sha256:outcome"
              :dimensions
              {:reproduction {:status :not-a-real-status}
               :publication {:status :publish}}})]
    (is (= :not-reviewed (rp/dimension-status pos :reproduction)))))

(deftest absent-semantics
  (is (rp/absent? :not-reviewed))
  (is (rp/absent? :insufficient-information))
  (is (rp/absent? :not-applicable))
  (is (not (rp/absent? :reproduced)))
  (is (not (rp/absent? :adequate))))

(deftest valid-status-catalog
  (is (rp/valid-dimension-status? :model-authority :contested))
  (is (rp/valid-dimension-status? :model-authority :adequate))
  (is (rp/valid-dimension-status? :model-authority :not-reviewed))
  (is (rp/valid-dimension-status? :model-authority :insufficient-information))
  (is (rp/valid-dimension-status? :model-authority :not-applicable))
  (is (rp/valid-dimension-status? :model-adversary :omitted))
  (is (rp/valid-dimension-status? :incentives-coalitions :omitted))
  (is (rp/valid-dimension-status? :publication :do-not-publish))
  (is (not (rp/valid-dimension-status? :model-authority :omitted)))
  (is (not (rp/valid-dimension-status? :reproduction :adequate))))
