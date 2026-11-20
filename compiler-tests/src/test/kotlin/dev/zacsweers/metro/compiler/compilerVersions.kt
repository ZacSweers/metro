// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import dev.zacsweers.metro.compiler.test.TEST_COMPILER_VERSION

/** Version requested for the test run, including its prerelease classifier. */
internal val TEST_COMPILER_TOOLING_VERSION = KotlinToolingVersion(TEST_COMPILER_VERSION)

/**
 * True if the Kotlin compiler we're testing against still emits diagnostic / IR dump goldens with
 * the legacy `.fir.` infix (e.g. `<name>.fir.diag.txt`). KT-85292 dropped that infix in Kotlin
 * 2.4.20 as part of the K1 removal effort.
 *
 * Drives the diagnostic-handler shim wired in by [AbstractDiagnosticTest]. Once Metro's floor moves
 * to 2.4.20+, this constant, the two `Metro*DiagnosticsHandler` classes, and the inline configure
 * path can all be deleted.
 */
internal val NEEDS_LEGACY_GOLDEN_NAMING: Boolean = COMPILER_VERSION < KotlinVersion(2, 4, 20)
