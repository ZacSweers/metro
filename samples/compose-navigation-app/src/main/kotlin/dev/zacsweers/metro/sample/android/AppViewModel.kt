// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.sample.android.viewmodel.ViewModelKey
import dev.zacsweers.metro.sample.android.viewmodel.ViewModelScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/** A trivial Counter ViewModel. */
@ContributesIntoMap(ViewModelScope::class)
@ViewModelKey(AppViewModel::class)
@Inject
class AppViewModel(application: Application, viewModelCounter: AtomicInt) :
  AndroidViewModel(application) {
  val instance = viewModelCounter.fetchAndIncrement()
}
