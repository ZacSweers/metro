// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * Marker annotation indicating that this binding container's provider factory classes are generated
 * entirely in IR (not in FIR/kotlin metadata). Consuming modules must synthesize stub classes to
 * link against when loading these factories from metadata.
 *
 * This is used by contribution providers (`generateContributionProviders`) and can be used by
 * external Metro extensions that generate binding containers with `@Provides` functions.
 */
@Target(AnnotationTarget.CLASS) public annotation class IROnlyFactories
