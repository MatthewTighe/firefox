package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
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
            TabsTrayHost(singleStore(requireComponents.core.store.state, requireComponents.appStore.state).scopeToTabsTray())
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
}

data class TabsTraySlice(val threeDotMenuOpen: Boolean = false)

data class FenixState(
    // These states represent our data layer - everything that the rest of the app relies on
    val appState: AppState = AppState(),
    val browserState: BrowserState = BrowserState(),
    // Slices represent local state
    val tabsTraySlice: TabsTraySlice = TabsTraySlice()
) {
    // This is a derived state, so we can just compute it on access
    val tabsTrayState: TabsTrayState
        get() = TabsTrayState(
            tabs = browserState.tabs.map { it.toTab() },
            mode = appState.mode.toTabsTrayMode(),
            threeDotMenuOpen = tabsTraySlice.threeDotMenuOpen
        )
}

sealed class FenixAction {
    data class TabsTrayActions(val inner: TabsTrayAction) : FenixAction()
}

fun fenixReducer(state: FenixState, action: FenixAction): FenixState = when (action) {
    is FenixAction.TabsTrayActions -> {
        val updatedState = tabsTrayReducer(state.tabsTrayState, action.inner)
        state.copy(
            tabsTraySlice = state.tabsTraySlice.copy(threeDotMenuOpen = updatedState.threeDotMenuOpen),
            browserState = state.browserState.copy(tabs = updatedState.tabs.map { it.toTabSessionState() }),
            appState = state.appState.copy(mode = updatedState.mode.toBrowsingMode())
        )
    }
}

val middlewares = listOf<Middleware<FenixState, FenixAction>>()
fun singleStore(browserState: BrowserState, appState: AppState) = object : BasicStore<FenixState, FenixAction> {
    private var _state: FenixState = FenixState(browserState = browserState, appState = appState)
        set(value) {
            field = value
            _stateFlow.value = value
        }
    override val state: FenixState
        get() = _state
    private val _stateFlow: MutableStateFlow<FenixState> = MutableStateFlow(state)
    override val stateFlow: StateFlow<FenixState>
        get() = _stateFlow

    override fun dispatch(action: FenixAction) {
        middlewares.fold({ action: FenixAction -> _state = fenixReducer(state, action)} ) { next, middleware ->
            { middleware({ state }, { dispatch(it) }, action, next) }
        }.invoke(action)
    }
}

private fun BasicStore<FenixState, FenixAction>.scopeToTabsTray(): BasicStore<TabsTrayState, TabsTrayAction> =
    object : BasicStore<TabsTrayState, TabsTrayAction> {
        override val state: TabsTrayState
            get() = this@scopeToTabsTray.state.tabsTrayState
        override val stateFlow: Flow<TabsTrayState>
            get() = this@scopeToTabsTray.stateFlow.map { it.tabsTrayState }

        override fun dispatch(action: TabsTrayAction) {
            this@scopeToTabsTray.dispatch(FenixAction.TabsTrayActions(action))
        }
    }

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

private fun Tab.toTabSessionState() = createTab(url = url)
private fun TabsTrayMode.toBrowsingMode() = when (this) {
    TabsTrayMode.Private -> BrowsingMode.Private
    else -> BrowsingMode.Normal
}
