(ns resolver-sim.protocols.sew.evidence.slashing
  "Compatibility facade for the Sew pro-rata slash evidence API."
  (:require [resolver-sim.protocols.sew.pro-rata.evidence]))

(doseq [[sym source-var]
        (ns-publics 'resolver-sim.protocols.sew.pro-rata.evidence)]
  (when-not (ns-resolve *ns* sym)
    (intern *ns*
            (with-meta sym (dissoc (meta source-var) :ns :name))
            @source-var)))
