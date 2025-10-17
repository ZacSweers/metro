// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

@Deprecated(
  "Use @GraphExtension instead",
  ReplaceWith(
    expression = "@GraphExtension(scope, additionalScopes, excludes, bindingContainers)",
    imports = ["dev.zacsweers.metro.GraphExtension"],
  ),
)
@Target(CLASS)
public annotation class ContributesGraphExtension(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
  val excludes: Array<KClass<*>> = [],
  val bindingContainers: Array<KClass<*>> = [],
) {
  @Deprecated(
    "Use @GraphExtension.Factory instead",
    ReplaceWith(
      expression = """
        @ContributesTo(scope) @GraphExtension.Factory
      """,
      imports = ["dev.zacsweers.metro.GraphExtension.Factory", "dev.zacsweers.metro.ContributesTo"],
    ),
    level = DeprecationLevel.ERROR,
  )
  public annotation class Factory(val scope: KClass<*>)
}
