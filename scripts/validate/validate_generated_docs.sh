#!/bin/bash
# Validate that generated documentation is up-to-date.

# 1. Regenerate docs
clojure -M:generate-scenario-docs

# 2. Compare against committed docs
# Note: This assumes generated docs are in docs/generated/
if git diff --exit-code docs/generated/; then
  echo "Documentation is up-to-date."
  exit 0
else
  echo "Documentation drift detected! Please run clojure -M:generate-scenario-docs and commit the changes."
  exit 1
fi
