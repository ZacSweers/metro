# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:25-jdk AS android-sdk-version

COPY gradle/libs.versions.toml /tmp/libs.versions.toml

RUN set -eux; \
    android_compile_sdk="$(sed -n 's/^[[:space:]]*android-compileSdk[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\)"[[:space:]]*$/\1/p' /tmp/libs.versions.toml)"; \
    test -n "${android_compile_sdk}"; \
    printf '%s\n' "${android_compile_sdk}" > /android-compile-sdk

FROM eclipse-temurin:25-jdk

WORKDIR /workspace

ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends libatomic1 unzip wget; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -O /tmp/android-commandlinetools.zip; \
    unzip -q /tmp/android-commandlinetools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/android-commandlinetools.zip

COPY --from=android-sdk-version /android-compile-sdk /tmp/android-compile-sdk

RUN set -eux; \
    android_compile_sdk="$(cat /tmp/android-compile-sdk)"; \
    rm /tmp/android-compile-sdk; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager "platform-tools" "platforms;android-${android_compile_sdk}" "build-tools;${ANDROID_BUILD_TOOLS}"
