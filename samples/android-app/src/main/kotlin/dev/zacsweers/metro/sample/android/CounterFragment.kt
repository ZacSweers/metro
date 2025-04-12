// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject

/** A fragment that displays a counter and a button to increment it. */
@ContributesIntoMap(AppScope::class)
@FragmentKey(CounterFragment::class)
@Inject
class CounterFragment(private val viewModelFactory: ViewModelProvider.Factory) :
  Fragment(R.layout.fragment_counter) {

  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = viewModelFactory

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val counterViewModel by viewModels<CounterViewModel>()
    val counterText = view.findViewById<TextView>(R.id.counter_text)
    val incrementButton = view.findViewById<Button>(R.id.increment_button)
    val decrementButton = view.findViewById<Button>(R.id.decrement_button)

    counterViewModel.count.observe(viewLifecycleOwner) { count ->
      @Suppress("SetTextI18n")
      counterText.text = "Count: $count"
    }

    incrementButton.setOnClickListener { counterViewModel.increment() }
    decrementButton.setOnClickListener { counterViewModel.decrement() }
  }
}
