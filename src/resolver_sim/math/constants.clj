(ns resolver-sim.math.constants
  "Shared exact arithmetic constants for PRF calculations.

   Domain-specific semantics remain with their owning modules; this namespace
   only gives common numeric units one dependency-safe definition.")

(def ^:const basis-point-denominator
  "The number of basis points in one whole (100%)."
  10000)
