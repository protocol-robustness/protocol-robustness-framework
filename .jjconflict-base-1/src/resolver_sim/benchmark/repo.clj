(ns resolver-sim.benchmark.repo
  "Repository metadata for benchmark provenance.

   All VCS access is delegated to resolver-sim.vcs, which resolves jj-then-git.
   This namespace performs no direct git/jj subprocess calls."
  (:require [resolver-sim.vcs :as vcs]))

(defn metadata []
  {:repo
   {:root      (vcs/root)
    :commit    (vcs/commit-sha)
    :branch    (vcs/branch)
    :tag       (vcs/tag)
    :dirty?    (vcs/dirty?)
    :remotes   (vcs/remotes)
    :lockfiles (vcs/lockfile-hashes)}})
