// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.androidviewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.ViewModelKey
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.ViewModelScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/** A trivial Counter ViewModel. */
@ContributesIntoMap(ViewModelScope::class)
@ViewModelKey(AppViewModel::class)
@Inject
class AppViewModel(application: Application, viewModelCounter: AtomicInt) :
  ViewModel() {
  val instance = viewModelCounter.fetchAndIncrement()
}
