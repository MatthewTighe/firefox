/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.state.store

import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.InitAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStoreTest {

    @Test
    fun `Initial state is empty by default`() {
        val store = BrowserStore()
        assertEquals(0, store.state.tabs.size)
        assertNull(store.state.selectedTabId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Initial state is validated and rejected if selected tab does not exist`() {
        val initialState = BrowserState(
            tabs = listOf(createTab("https://www.mozilla.org")),
            selectedTabId = "invalid",
        )
        BrowserStore(initialState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Initial state is validated and rejected if it contains duplicate tabs`() {
        val tabs = listOf(
            createTab(id = "1", url = "https://www.mozilla.org"),
            createTab(id = "2", url = "https://www.getpocket.com"),
            createTab(id = "1", url = "https://www.mozilla.org"),
        )
        val initialState = BrowserState(tabs)
        BrowserStore(initialState)
    }

    @Test
    fun `Adding a tab`() = runTest {
        val store = BrowserStore()

        assertEquals(0, store.state.tabs.size)
        assertNull(store.state.selectedTabId)

        val tab = createTab(url = "https://www.mozilla.org")

        store.dispatch(TabListAction.AddTabAction(tab))

        assertEquals(1, store.state.tabs.size)
        assertEquals(tab.id, store.state.selectedTabId)
    }

    @Test
    fun `Dispatches init action when created`() {
        var initActionObserved = false
        val testMiddleware: Middleware<BrowserState, BrowserAction> = { _, next, action ->
            if (action == InitAction) {
                initActionObserved = true
            }

            next(action)
        }

        BrowserStore(middleware = listOf(testMiddleware))
        assertTrue(initActionObserved)
    }

    @Test
    fun `RestoreCompleteAction updates state`() {
        val store = BrowserStore()
        assertFalse(store.state.restoreComplete)

        store.dispatch(RestoreCompleteAction)
        assertTrue(store.state.restoreComplete)

        store.dispatch(RestoreCompleteAction)
        assertTrue(store.state.restoreComplete)
    }
}
