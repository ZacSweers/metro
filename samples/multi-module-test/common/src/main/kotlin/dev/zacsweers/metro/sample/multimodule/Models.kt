// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule

/**
 * A simple data class representing a user.
 */
data class User(val id: String, val name: String)

/**
 * A simple data class representing a message.
 */
data class Message(val id: String, val text: String)

/**
 * A simple data class representing an item.
 */
data class Item(val id: String, val name: String, val description: String)