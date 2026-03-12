/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.summarization

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.feature.summarize.SummarizationSettings

/**
 * Wrapper for the summarization settings managed by the module. This is a convenience class to bridge
 * suspending and non-suspending contents, to be hosted by a lifecycle observer.
 */
class FenixSummarizationSettings(
    private val summarizationSettings: SummarizationSettings,
) : DefaultLifecycleObserver {
    private val _isFeatureEnabled = MutableStateFlow(true)
    val isFeatureEnabled: StateFlow<Boolean> = _isFeatureEnabled
    private val _isGestureEnabled = MutableStateFlow(true)
    val isGestureEnabled: StateFlow<Boolean> = _isGestureEnabled
    private val _isShakeGestureRejected = MutableStateFlow(false)
    val isShakeGestureRejected: StateFlow<Boolean> = _isShakeGestureRejected

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        owner.lifecycle.coroutineScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectFeatureStatuses()
                mapShakeGestureRejected()
            }
        }
    }

    private fun CoroutineScope.collectFeatureStatuses() = launch {
        combine(
            summarizationSettings.getFeatureEnabledUserStatus(),
            summarizationSettings.getGestureEnabledUserStatus(),
        ) { a, b ->
            a to b
        }
            .distinctUntilChanged()
            .collect { (isFeatureEnabled, isGestureEnabled) ->
                this@FenixSummarizationSettings._isFeatureEnabled.value = isFeatureEnabled
                this@FenixSummarizationSettings._isGestureEnabled.value = isGestureEnabled
            }
    }

    private fun CoroutineScope.mapShakeGestureRejected() = launch {
        val maxRejectionCount = 3
        summarizationSettings
            .getShakeConsentRejectedCount()
            .map { it > maxRejectionCount }
            .distinctUntilChanged()
            .collect { this@FenixSummarizationSettings._isShakeGestureRejected.value = it }
    }
}
