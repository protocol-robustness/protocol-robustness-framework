# syntax=docker/dockerfile:1

# Multi-stage image for AWS ECS (Fargate-compatible).
#
# Build stage pins the toolchain (Temurin 21 + Clojure CLI 1.12.5.1664,
# Ubuntu Noble) to match CI (.github/workflows: temurin 21). Per repo policy
# in config/docker-compose.yaml, images must be pinned and `latest` is never
# used. Before publishing to ECR, replace these tags with @sha256 digests
# (obtain via `docker buildx imagetools inspect <ref>`) exactly like the
# pinned ghcr.io/xtdb/xtdb digest in config/docker-compose.yaml.
FROM clojure:temurin-21-tools-deps-1.12.5.1664-noble AS build

WORKDIR /src

# Dependency prefetch. Cached independently of source edits; includes the
# tools.build git dependency selected by the :build alias.
COPY deps.edn ./
RUN clojure -P -M:build

# Inputs consumed by scripts/build.clj for the service variant: src,
# protocols_src, resources, scenarios, plus data/ and config/ which it copies
# into the JAR as classpath roots. scripts/build.clj provides the packaging.
COPY scripts/ scripts/
COPY src/ src/
COPY protocols_src/ protocols_src/
COPY resources/ resources/
COPY scenarios/ scenarios/
COPY data/ data/
COPY config/ config/

# Service distribution (:service): Sew sources + gRPC transport deps, with
# resolver-sim.core AOT-compiled as the JAR Main-Class. This is the only
# variant whose dependency set can actually run resolver-sim.server.grpc.
RUN clojure -T:build uberjar :variant service

# Runtime stage: JRE only, no build tooling, no package manager use.
FROM eclipse-temurin:21-jre-noble

# Fixed non-root identity for ECS/Fargate (read-only rootfs friendly).
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /src/target/prf-runner-service-0.1.0-uber.jar /app/app.jar

USER app

# Heap sizing derives from the Fargate task memory limit via container
# awareness; ExitOnOutOfMemoryError turns heap exhaustion into a fast,
# replaceable task instead of a wedged service.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

# Evidence-node emission (resolver-sim.evidence.config/artifact-dir) writes
# under the configured artifact dir, which defaults to the repo-relative
# results/test-artifacts. Redirect it to container-local ephemeral storage:
# writable by the non-root user and safe to discard on task replacement.
ENV PRF_ARTIFACT_DIR=/tmp/prf-artifacts

# Deliberately NO Dockerfile HEALTHCHECK: ECS/Fargate ignores it. Configure
# the container health check in the task definition instead (TCP probe on the
# gRPC port, or grpc_health_probe once the server registers the standard
# health service).
EXPOSE 7070

# Exec form so java is PID 1: ECS stopTimeout delivers SIGTERM directly to the
# JVM, whose shutdown hook (registered by resolver-sim.core serve mode) drains
# the gRPC server. The AOT Main-Class boots straight into -main. Default
# command starts the Phase 2 live server; override CMD in the task definition
# for one-shot runs (e.g. "--invariants --suite ...").
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--serve", "--port", "7070"]
