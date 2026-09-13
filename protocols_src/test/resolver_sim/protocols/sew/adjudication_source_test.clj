(ns resolver-sim.protocols.sew.adjudication-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.adjudication-source :as source]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- world [fence]
  {:block-time 1000
   :dispute-levels {7 0}
   :dispute-question-roots {7 (root :question)}
   :dispute-evidence-set-roots {7 (root :evidence)}
   :governed-resolution-basis-roots {7 (root :authority-basis)}
   :governed-authority-context-roots {7 (root :authority-context)}
   :configuration-roots {7 (root :configuration)}
   :state-fence fence})

(deftest publishes-rooted-authoritative-source-envelope
  (let [fence (root :state-0)
        result (source/publish-current-adjudication-source! (world fence) 7 fence)
        envelope (:source result)
        current (source/current-adjudication-source (:world result) 7)]
    (is (:ok result))
    (is (:valid? (source/validate-adjudication-source envelope)))
    (is (= fence (:source/state-fence-root envelope)))
    (is (= 0 (:adjudication/round-id envelope)))
    (is (nil? (:source/parent-root envelope)))
    (is (:valid? current))
    (is (= envelope (:source current)))))

(deftest envelope-is-deterministic-and-component-bound
  (let [fence (root :state-0)
        first (source/publish-current-adjudication-source! (world fence) 7 fence)
        changed (assoc-in (world fence) [:dispute-evidence-set-roots 7] (root :other-evidence))
        other (source/publish-current-adjudication-source! changed 7 fence)]
    (is (= (:sew/adjudication-source-root (:source first))
           (:sew/adjudication-source-root (:source
                                           (source/publish-current-adjudication-source!
                                            (world fence) 7 fence)))))
    (is (not= (:sew/adjudication-source-root (:source first))
              (:sew/adjudication-source-root (:source other))))))

(deftest authoritative-components-and-current-pointer-fail-closed
  (let [fence (root :state-0)
        missing (source/publish-current-adjudication-source!
                 (update (world fence) :configuration-roots dissoc 7) 7 fence)
        published (source/publish-current-adjudication-source! (world fence) 7 fence)
        dangling (assoc-in (:world published) [:adjudication-current-source-root 7] (root :missing))]
    (is (= :missing-authoritative-adjudication-component (:error missing)))
    (is (= [:configuration/root] (:missing-components missing)))
    (is (= [:missing-current-adjudication-source]
           (:errors (source/current-adjudication-source dangling 7))))))

(deftest successor-lineage-requires-a-new-authoritative-observation
  (let [fence-0 (root :state-0)
        first (source/publish-current-adjudication-source! (world fence-0) 7 fence-0)
        fence-1 (root :state-1)
        successor-world (-> (:world first)
                            (assoc :state-fence fence-1)
                            (assoc-in [:dispute-levels 7] 1))
        second (source/publish-current-adjudication-source! successor-world 7 fence-1)]
    (is (:ok second))
    (is (= 1 (:adjudication/round-id (:source second))))
    (is (= (:sew/adjudication-source-root (:source first))
           (:source/parent-root (:source second))))))
