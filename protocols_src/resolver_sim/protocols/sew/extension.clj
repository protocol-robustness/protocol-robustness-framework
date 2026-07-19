(ns resolver-sim.protocols.sew.extension
  "Explicit Sew extension bootstrap. Load this namespace only on a :with-sew
   classpath to make the Sew adapter available through the core registry."
  (:require [resolver-sim.protocols.registry :as registry]))

(def protocol-id
  (registry/register-extension! "sew-v1" 'resolver-sim.protocols.sew/protocol))
