// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

abstract class MetroProjectExtension @Inject constructor(objects: ObjectFactory) {
  abstract val jvmTarget: Property<String>
  val languageVersion: Property<KotlinVersion> =
    objects.property(KotlinVersion::class.java).convention(KotlinVersion.DEFAULT)
  val apiVersion: Property<KotlinVersion> =
    objects.property(KotlinVersion::class.java).convention(KotlinVersion.DEFAULT)
  val progressiveMode: Property<Boolean> =
    objects
      .property(Boolean::class.java)
      .convention(languageVersion.map { it < KotlinVersion.DEFAULT })
}
