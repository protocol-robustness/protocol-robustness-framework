(ns resolver-sim.evidence.aggregate
  "Generic evidence aggregate builder.
   Provides a consistent envelope for structured protocol evidence."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.time.context :as time-ctx]))

(defn evidence-content-hash
  "Compute the content-addressable hash of the evidence map inputs."
  [content]
  (hc/hash-with-intent {:hash/intent :evidence-record} content))

(defn extract-decision-frame
  "Extract the decision frame (world state metadata) from the world.
   The frame/id is derived from the world content hash for deterministic
   content-addressing — same world always produces the same frame.
   
   Includes the full temporal context (:context/time) when available,
   providing event-seq, clock mode, tick-seconds, and ISO instant
   alongside block-ts and step for forensic ordering and reproducibility."
  [world]
  (let [tc (time-ctx/temporal-context world)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} world)
        step-val (or (:step tc) (:step world 0))
        event-seq (:event-seq tc 0)]
    {:frame/id (str "frame-" world-hash "-" step-val "-" event-seq)
     :step step-val
     :event-seq event-seq
     :block-ts (time-ctx/block-ts world)
     :instant (str (time-ctx/now world))
     :clock/mode (:clock/mode tc :discrete-step)
     :tick-seconds (:tick-seconds tc time-ctx/seconds-per-day)
     :world/hash world-hash}))

(defn build-evidence-aggregate
  "Construct a consistent evidence envelope.

   The aggregate owns the envelope schema and hashing,
   delegating the specific payload structure to domain constructors."
  [{:keys [evidence-type
           schema-version
           world
           frame
           subject
           inputs
           result
           context
           dependencies
           attribution]
    :or {dependencies []}}]
  (let [envelope
        {:evidence/type evidence-type
         :evidence/schema-version schema-version

         :evidence/frame frame
         :evidence/subject subject
         :evidence/context context
         :evidence/inputs inputs
         :evidence/result result
         :evidence/dependencies dependencies
         :evidence/attribution attribution}]
    (assoc envelope
           :evidence/hash-input
           {:frame frame
            :subject subject
            :inputs inputs
            :result result
            :context context})))
