# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:25-jdk

WORKDIR /workspace

ARG KOTLIN_REPO_URL
ARG KOTLIN_VERSION
ARG KOTLIN_API_VERSION
ARG KOTLIN_LANGUAGE_VERSION
ARG KOTLIN_ADDITIONAL_CLI_OPTIONS

COPY . .

RUN set -eux; \
    chmod +x ./gradlew ./scripts/run-ci-gradle.sh; \
    set -- \
      :compiler-tests:generateTests \
      :compiler:test \
      :compiler-tests:test \
      :gradle-plugin:functionalTest \
      --quiet \
      --no-configuration-cache \
      -Pmetro.excludeJsBoxTests \
      -Pmetro.functionalTestKmpTarget=jvm; \
    if [ -n "${KOTLIN_REPO_URL:-}" ]; then set -- "$@" "-Pkotlin_repo_url=${KOTLIN_REPO_URL}"; fi; \
    if [ -n "${KOTLIN_VERSION:-}" ]; then set -- "$@" "-Pmetro.testCompilerVersion=${KOTLIN_VERSION}"; fi; \
    if [ -n "${KOTLIN_API_VERSION:-}" ]; then set -- "$@" "-Pkotlin_api_version=${KOTLIN_API_VERSION}"; fi; \
    if [ -n "${KOTLIN_LANGUAGE_VERSION:-}" ]; then set -- "$@" "-Pkotlin_language_version=${KOTLIN_LANGUAGE_VERSION}"; fi; \
    if [ -n "${KOTLIN_ADDITIONAL_CLI_OPTIONS:-}" ]; then set -- "$@" "-Pkotlin_additional_cli_options=${KOTLIN_ADDITIONAL_CLI_OPTIONS}"; fi; \
    ./scripts/run-ci-gradle.sh "$@"
