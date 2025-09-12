/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.lib.state.internal.ReducerChainBuilder

/**
 * A generic store holding an immutable [State].
 *
 * The [State] can only be modified by dispatching [Action]s which will create a new state and notify all registered
 * [Observer]s.
 *
 * @param initialState The initial state until a dispatched [Action] creates a new state.
 * @param reducer A function that gets the current [State] and [Action] passed in and will return a new [State].
 * @param middleware Optional list of [Middleware] sitting between the [Store] and the [Reducer].
 */
open class Store<S : State, A : Action>(
    initialState: S,
    reducer: Reducer<S, A>,
    middleware: List<Middleware<S, A>> = emptyList(),
) {
    private val reducerChainBuilder = ReducerChainBuilder(reducer, middleware)

    /**
     * The current [State].
     */
    private var _state = MutableStateFlow(initialState)
    val state: S
        get() = _state.value

    val stateFlow: StateFlow<S> = _state

    /**
     * Dispatch an [Action] to the store in order to trigger a [State] change.
     */
    fun dispatch(action: A) =
        reducerChainBuilder.get(this@Store).invoke(action)

    /**
     * Transitions from the current [State] to the passed in [state] and notifies all observers.
     */
    internal fun transitionTo(state: S) {
        if (state == _state.value) {
            // Nothing has changed.
            return
        }

        _state.value = state
    }
}
