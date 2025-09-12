/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.privatemode.notification

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.support.base.feature.LifecycleAwareFeature
import kotlin.reflect.KClass

/**
 * Starts up a [AbstractPrivateNotificationService] once a private tab is opened.
 *
 * @param store Browser store reference used to observe the number of private tabs.
 * @param notificationServiceClass The service sub-class that should be started by this feature.
 */
class PrivateNotificationFeature<T : AbstractPrivateNotificationService>(
    context: Context,
    private val store: BrowserStore,
    private val notificationServiceClass: KClass<T>,
) : LifecycleAwareFeature {

    private val applicationContext = context.applicationContext
    private var scope: CoroutineScope? = null

    override fun start() {
        scope = MainScope().also {
            it.launch {
                store.stateFlow.map { state -> state.privateTabs.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { hasPrivateTabs ->
                        if (hasPrivateTabs) {
                            applicationContext.startService(
                                Intent(
                                    applicationContext,
                                    notificationServiceClass.java
                                )
                            )
                        }
                    }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
    }
}
