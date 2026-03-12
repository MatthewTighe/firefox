/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Defines the contract for persisting and retrieving user settings related to
 * the summarization feature.
 *
 * Implementations are responsible for the underlying storage mechanism, such as
 * [SharedPreferences] or a database.
 *
 * @see [SummarizationSettings.Companion.inMemory] for a simple in-memory implementation
 * suitable for testing or previews.
 */
interface SummarizationSettings {

    /**
     * @return A [Flow] emitting the user's current preference for whether the summarization
     * feature is enabled.
     */
    suspend fun getFeatureEnabledUserStatus(): Flow<Boolean>

    /**
     * Persists the user's preference for whether the summarization feature is enabled.
     *
     * @param newValue `true` to enable the feature, `false` to disable it.
     */
    suspend fun setFeatureEnabledUserStatus(newValue: Boolean)

    /**
     * @return A [Flow] emitting the user's current preference for whether the shake gesture
     * is enabled.
     */
    suspend fun getGestureEnabledUserStatus(): Flow<Boolean>

    /**
     * Persists the user's preference for whether the shake gesture is enabled.
     *
     * @param newValue `true` to enable the gesture, `false` to disable it.
     */
    suspend fun setGestureEnabledUserStatus(newValue: Boolean)

    /**
     * @return A [Flow] emitting whether the user has consented to the shake gesture interaction.
     */
    suspend fun getHasConsentedToShake(): Flow<Boolean>

    /**
     * Persists the user's consent status for the shake gesture interaction.
     *
     * @param newValue `true` to indicate the user has consented, `false` to revoke consent.
     */
    suspend fun setHasConsentedToShake(newValue: Boolean)

    /**
     * @return A [Flow] emitting the number of times the user has rejected the shake consent prompt.
     */
    suspend fun getShakeConsentRejectedCount(): Flow<Int>

    /**
     * Increments the count of times the user has rejected the shake consent prompt.
     */
    suspend fun incrementShakeConsentRejectedCount()

    companion object {
        /**
         * Creates a simple in-memory implementation of [SummarizationSettings].
         *
         * This implementation does not persist data across sessions and is intended
         * for use in tests, Compose previews, or other scenarios where a lightweight,
         * non-persistent implementation is needed.
         *
         * @param hasConsentedToShakeInitial The initial value for the shake consent setting.
         * Defaults to `false`.
         * @return An in-memory [SummarizationSettings] instance.
         */
        fun inMemory(
            isFeatureEnabled: Boolean = false,
            isGestureEnabled: Boolean = false,
            hasConsentedToShake: Boolean = false,
            shakeConsentRejectedCount: Int = 0,
        ) = object : SummarizationSettings {
            var isFeatureEnabledFlow =
                MutableStateFlow(isFeatureEnabled)
            var isGestureEnabledFlow =
                MutableStateFlow(isGestureEnabled)
            var hasConsentedToShakeFlow =
                MutableStateFlow(hasConsentedToShake)
            var shakeConsentRejectedCountFlow =
                MutableStateFlow(shakeConsentRejectedCount)

            override suspend fun getFeatureEnabledUserStatus(): Flow<Boolean> = isFeatureEnabledFlow

            override suspend fun setFeatureEnabledUserStatus(newValue: Boolean) {
                isFeatureEnabledFlow.emit(newValue)
            }

            override suspend fun getGestureEnabledUserStatus(): Flow<Boolean> = isGestureEnabledFlow

            override suspend fun setGestureEnabledUserStatus(newValue: Boolean) {
                isGestureEnabledFlow.emit(newValue)
            }

            override suspend fun getHasConsentedToShake(): Flow<Boolean> = hasConsentedToShakeFlow

            override suspend fun setHasConsentedToShake(newValue: Boolean) {
                hasConsentedToShakeFlow.emit(newValue)
            }

            override suspend fun getShakeConsentRejectedCount(): Flow<Int> = shakeConsentRejectedCountFlow

            override suspend fun incrementShakeConsentRejectedCount() {
                shakeConsentRejectedCountFlow.emit(shakeConsentRejectedCountFlow.value++)
            }
        }

        /**
         * Creates a [DataStore]-backed implementation of [SummarizationSettings].
         *
         * Data is persisted across sessions using [androidx.datastore.preferences.core.Preferences].
         *
         * @param context The application [Context] used to access the data store.
         * @return A persistent [SummarizationSettings] instance backed by [DataStore].
         */
        fun dataStore(context: Context) = object : SummarizationSettings {
            val dataStore = context.dataStore

            private val FEATURE_ENABLED_USER_STATUS_KEY = booleanPreferencesKey("feature_enabled_user_status_key")
            private val GESTURE_ENABLE_USER_STATUS_KEY = booleanPreferencesKey("gesture_enabled_user_status_key")
            private val HAS_CONSENTED_TO_SHAKE_KEY = booleanPreferencesKey("has_consented_to_shake_key")
            private val SHAKE_CONSENT_REJECTED_COUNT_KEY = intPreferencesKey("shake_consent_rejected_count_key")

            override suspend fun getFeatureEnabledUserStatus(): Flow<Boolean> = dataStore.data.map { preferences ->
                preferences[FEATURE_ENABLED_USER_STATUS_KEY] ?: true
            }
            override suspend fun setFeatureEnabledUserStatus(newValue: Boolean) {
                dataStore.updateData {
                    it.toMutablePreferences().also { preferences ->
                        preferences[FEATURE_ENABLED_USER_STATUS_KEY] = newValue
                    }
                }
            }

            override suspend fun getGestureEnabledUserStatus(): Flow<Boolean> = dataStore.data.map { preferences ->
                preferences[GESTURE_ENABLE_USER_STATUS_KEY] ?: true
            }

            override suspend fun setGestureEnabledUserStatus(newValue: Boolean) {
                dataStore.updateData {
                    it.toMutablePreferences().also { preferences ->
                        preferences[GESTURE_ENABLE_USER_STATUS_KEY] = newValue
                    }
                }
            }

            override suspend fun getHasConsentedToShake(): Flow<Boolean> = dataStore.data.map { preferences ->
                preferences[HAS_CONSENTED_TO_SHAKE_KEY] ?: false
            }

            override suspend fun setHasConsentedToShake(newValue: Boolean) {
                dataStore.updateData {
                    it.toMutablePreferences().also { preferences ->
                        preferences[HAS_CONSENTED_TO_SHAKE_KEY] = newValue
                    }
                }
            }

            override suspend fun getShakeConsentRejectedCount(): Flow<Int> = dataStore.data.map { preferences ->
                preferences[SHAKE_CONSENT_REJECTED_COUNT_KEY] ?: 0
            }

            override suspend fun incrementShakeConsentRejectedCount() {
                dataStore.updateData {
                    it.toMutablePreferences().also { preferences ->
                        preferences[SHAKE_CONSENT_REJECTED_COUNT_KEY] =
                            (preferences[SHAKE_CONSENT_REJECTED_COUNT_KEY] ?: 0) + 1
                    }
                }
            }
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "summarization_feature_settings")
