# syntax=docker.io/docker/dockerfile:1

# Build stage
FROM clojure:temurin-17-bookworm AS build-stage

WORKDIR /opt/cartesi/dapp
# Build context should be the Protocol Robustness Framework repository root.
# Example:
#   docker build -f cartesi/Dockerfile .
COPY . /opt/cartesi/dapp/protocol-robustness-framework

WORKDIR /opt/cartesi/dapp/protocol-robustness-framework
RUN clojure -X:build-uberjar :jar prf.jar

# Runtime stage
FROM --platform=linux/riscv64 cartesi/node:20.16.0-jammy-slim

ARG MACHINE_EMULATOR_TOOLS_VERSION=0.14.1
ADD https://github.com/cartesi/machine-emulator-tools/releases/download/v${MACHINE_EMULATOR_TOOLS_VERSION}/machine-emulator-tools-v${MACHINE_EMULATOR_TOOLS_VERSION}.deb /
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    dpkg -i /machine-emulator-tools-v${MACHINE_EMULATOR_TOOLS_VERSION}.deb && \
    rm /machine-emulator-tools-v${MACHINE_EMULATOR_TOOLS_VERSION}.deb && \
    rm -rf /var/lib/apt/lists/*

LABEL io.cartesi.rollups.sdk_version=0.9.0
LABEL io.cartesi.rollups.ram_size=256Mi

ARG DEBIAN_FRONTEND=noninteractive
RUN <<EOF
set -e
apt-get update
apt-get install -y --no-install-recommends \
  busybox-static \
  openjdk-17-jre-headless
rm -rf /var/lib/apt/lists/* /var/log/* /var/cache/*
useradd --create-home --user-group dapp
EOF

ENV PATH="/opt/cartesi/bin:${PATH}"

WORKDIR /opt/cartesi/dapp
COPY --from=build-stage /opt/cartesi/dapp/protocol-robustness-framework/prf.jar .

ENV ROLLUP_HTTP_SERVER_URL="http://127.0.0.1:5004"

ENTRYPOINT ["rollup-init"]
CMD ["java", "-jar", "prf.jar"]
