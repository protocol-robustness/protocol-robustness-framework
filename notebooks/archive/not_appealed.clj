;; # Appeal Bond Analysis — Consolidated
;;
;; This notebook is retained as a compatibility landing page. Its former bond
;; analysis duplicated the broader, maintained appeal/challenge presentation.
;; See notebooks/appeal_analysis.clj for the canonical feature set.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.not-appealed
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "# Appeal Bond Analysis — Consolidated

   This notebook has been consolidated into
   [Appeal Analysis](appeal_analysis.clj), the canonical presentation for:

   - resolver slash appeals and slash-scoped bond custody;
   - open third-party resolution challenges and challenge-bond fees;
   - fee-generation boundaries and forfeiture distributions;
   - deadlines, governance authorization, scenario evidence, and invariants;
   - escalation economics and the clearly labelled external-arbitration model.

   Keeping one maintained source prevents the bond lifecycle, fee semantics,
   and scenario mappings from drifting between two notebooks.")

^{::clerk/visibility {:code :hide :result :show}}
(ui/notebook-navigation "Appeal Analysis")
