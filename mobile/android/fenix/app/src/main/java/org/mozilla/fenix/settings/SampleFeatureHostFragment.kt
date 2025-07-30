package org.mozilla.fenix.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.appstate.AppState
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.tabstray.BasicStore
import org.mozilla.tabstray.Middleware
import org.mozilla.tabstray.Tab
import org.mozilla.tabstray.TabsTrayAction
import org.mozilla.tabstray.TabsTrayHost
import org.mozilla.tabstray.TabsTrayMode
import org.mozilla.tabstray.TabsTrayState
import org.mozilla.tabstray.TestMiddleware
import org.mozilla.tabstray.buildStore
import org.mozilla.tabstray.tabs
import org.mozilla.tabstray.tabsTrayReducer

class SampleFeatureHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_sample_feature_host, container, false)
        val store = DerivedTabsTrayStore(requireComponents.appStore, requireComponents.core.store)
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.resumeCollection()
            }
        }
        view.findViewById<ComposeView>(R.id.compose_view).setContent {
            TabsTrayHost(store)
        }
        return view
    }
}

class DerivedTabsTrayStore(
    private val appStore: AppStore,
    private val browserStore: BrowserStore,
    private val middlewares: List<Middleware<TabsTrayState, TabsTrayAction>> = listOf(TestMiddleware())
) : BasicStore<TabsTrayState, TabsTrayAction> {
    private var _state: TabsTrayState = TabsTrayState()
        set(value) {
            field = value
            _stateFlow.value = value
        }
    override val state: TabsTrayState
        get() = _state
    private val _stateFlow: MutableStateFlow<TabsTrayState> = MutableStateFlow(state)
    override val stateFlow: StateFlow<TabsTrayState>
        get() = _stateFlow

    override fun dispatch(action: TabsTrayAction) {
        // This creates a base case of invoking the reducer, and establishes a chain of invoking middleware
        // using recursive lambdas.
        middlewares.fold({ action: TabsTrayAction -> _state = tabsTrayReducer(state, action)} ) { next, middleware ->
            { middleware({ state }, { dispatch(it) }, action, next) }
        }.invoke(action)
    }

    suspend fun resumeCollection() {
        appStore.flow()
            .distinctUntilChangedBy { it.mode }
            .combine(
                browserStore.flow()
                    .distinctUntilChangedBy { it.tabs }
            ) { appState, browserState ->
                buildState(appState, browserState)
            }
            .collect {
                _state = it
            }
    }

    private fun buildState(appState: AppState, browserState: BrowserState) = state.copy(
        tabs = browserState.tabs.map { it.toTab() } + browserState.tabs.map { it.toSyncedTab() },
        mode = appState.mode.toTabsTrayMode(),
    )

    private fun TabSessionState.toTab() = Tab(
        id = id,
        url = content.url,
        title = content.title,
        bitmap = content.icon,
        mode = if (content.private) TabsTrayMode.Private else TabsTrayMode.Normal
    )

    private fun TabSessionState.toSyncedTab() = Tab(
        id = id,
        url = content.url,
        title = "Synced: ${content.title}",
        bitmap = content.icon,
        mode = TabsTrayMode.Synced
    )

    private fun BrowsingMode.toTabsTrayMode() = when (this) {
        BrowsingMode.Normal -> TabsTrayMode.Normal
        BrowsingMode.Private -> TabsTrayMode.Private
    }
}
