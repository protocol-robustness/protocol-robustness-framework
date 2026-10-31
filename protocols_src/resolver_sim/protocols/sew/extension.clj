(ns resolver-sim.protocols.sew.extension
  "Explicit Sew extension bootstrap. Load this namespace only on a :with-sew
   classpath to register Sew and install the optional pro-rata slash-evidence
   adapter. The protocol registry loads this bootstrap when resolving Sew."
  (:require [resolver-sim.protocols.registry :as registry]
            [resolver-sim.protocols.sew.pro-rata.evidence]))

(def protocol-id
  (registry/register-extension! "sew-v1" 'resolver-sim.protocols.sew/protocol))
