// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule

/** Interface for a service that provides a message. */
interface MessageService {
  fun getMessage(): String
}

/** Interface for a service that provides a number. */
interface NumberService {
  fun getNumber(): Int
}

/** Interface for a service that provides a list of items. */
interface ItemService {
  fun getItems(): List<String>
}

/** Interface for a service that provides a map of items. */
interface MapService {
  fun getMap(): Map<String, String>
}
