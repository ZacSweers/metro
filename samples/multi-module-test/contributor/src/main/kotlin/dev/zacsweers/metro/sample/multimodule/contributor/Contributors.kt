// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.contributor

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.sample.multimodule.AppScope
import dev.zacsweers.metro.sample.multimodule.FeatureScope
import dev.zacsweers.metro.sample.multimodule.ItemService
import dev.zacsweers.metro.sample.multimodule.MapService
import dev.zacsweers.metro.sample.multimodule.MessageService

/**
 * Contributes providers to the AppScope. This interface will be merged into any graph that uses
 * AppScope.
 */
@ContributesTo(AppScope::class)
interface AppContributor {
  @Provides
  @Named("contributed")
  fun provideContributedMessage(): String = "Message from contributor"
}

/**
 * Contributes providers to the FeatureScope. This interface will be merged into any graph that uses
 * FeatureScope.
 */
@ContributesTo(FeatureScope::class)
interface FeatureContributor {
  @Provides
  @Named("feature")
  fun provideFeatureMessage(): String = "Feature message from contributor"
}

/**
 * Implementation of a qualified MessageService that is contributed to AppScope. This will be
 * available as a MessageService binding in any graph that uses AppScope.
 */
@ContributesBinding(
  scope = AppScope::class,
  binding = binding<@Named("contributed") MessageService>(),
)
@Inject
class ContributedMessageService : MessageService {
  override fun getMessage(): String = "Message from contributed service"
}

/**
 * Implementation of ItemService that is contributed to a Set multibinding in AppScope. This will be
 * available as part of a Set<ItemService> in any graph that uses AppScope.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class ContributedItemService : ItemService {
  override fun getItems(): List<String> = listOf("Contributed Item 1", "Contributed Item 2")
}

/**
 * Implementation of MapService that is contributed to a Map multibinding in AppScope. This will be
 * available as part of a Map<String, MapService> in any graph that uses AppScope.
 */
@ContributesIntoMap(
  scope = AppScope::class,
  binding = binding<@StringKey("contributor") MapService>(),
)
@Inject
class ContributedMapService : MapService {
  override fun getMap(): Map<String, String> = mapOf("key1" to "value1", "key2" to "value2")
}
