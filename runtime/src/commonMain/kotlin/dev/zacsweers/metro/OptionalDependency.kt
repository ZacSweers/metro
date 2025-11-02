// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

@Deprecated("Use @OptionalBinding instead", ReplaceWith("OptionalBinding"))
@MustBeDocumented
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OptionalDependency
