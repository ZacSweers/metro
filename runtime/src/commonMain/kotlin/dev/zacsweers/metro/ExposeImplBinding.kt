// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS

/**
 * When `generateContributionProviders` is enabled, classes annotated with `@Contributes*` and
 * `@Inject` normally ecnapsulate their implementation behind a generated `@Provides` binding. This
 * means the implementation type cannot be directly injected; only the bound supertype is available.
 *
 * In some cases, you may want to still expose the underlying impl binding on the graph too. To do
 * this, annotate a class with `@ExposeImplBinding` to opt out of this behavior and retain the
 * inject factory, allowing the implementation type to be injected directly alongside the
 * contributed binding.
 *
 * ```
 * @ExposeImplBinding
 * @ContributesBinding(AppScope::class)
 * @Inject
 * class PreferencesImpl(...) : Preferences
 *
 * // Both Preferences and PreferencesImpl are now injectable
 * ```
 *
 * This annotation has no effect when `generateContributionProviders` is not enabled.
 */
@ExperimentalMetroApi @Target(CLASS) public annotation class ExposeImplBinding
