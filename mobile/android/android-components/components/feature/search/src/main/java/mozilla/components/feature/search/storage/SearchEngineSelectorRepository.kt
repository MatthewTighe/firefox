/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.search.storage

import android.graphics.Bitmap
import mozilla.appservices.remotesettings.RemoteSettingsClient
import mozilla.appservices.remotesettings.RemoteSettingsRecord
import mozilla.appservices.search.RefinedSearchConfig
import mozilla.appservices.search.SearchApiException
import mozilla.appservices.search.SearchEngineClassification
import mozilla.appservices.search.SearchEngineDefinition
import mozilla.appservices.search.SearchEngineSelector
import mozilla.appservices.search.SearchEngineUrl
import mozilla.appservices.search.SearchEngineUrls
import mozilla.appservices.search.SearchUrlParam
import mozilla.appservices.search.SearchUserEnvironment
import mozilla.components.browser.state.search.RegionState
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.feature.search.SearchApplicationName
import mozilla.components.feature.search.SearchDeviceType
import mozilla.components.feature.search.SearchUpdateChannel
import mozilla.components.feature.search.icons.SearchConfigIconsParser
import mozilla.components.feature.search.icons.SearchConfigIconsUpdateService
import mozilla.components.feature.search.into
import mozilla.components.feature.search.middleware.SearchExtraParams
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.org.json.toList
import mozilla.components.support.remotesettings.RemoteSettingsService
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * A repository implementation for loading and reading [SearchEngineDefinition]s from RemoteSettings.
 *
 * @param searchEngineSelectorConfig [SearchEngineSelectorConfig] holds configuration options for
 *          [SearchUserEnvironment]
 */
class SearchEngineSelectorRepository(
    private val searchEngineSelectorConfig: SearchEngineSelectorConfig,
    private val defaultSearchEngineIcon: Bitmap,
    private val client: RemoteSettingsClient?,
    private val selector: SearchEngineSelector = SearchEngineSelector(),
) : SearchMiddleware.SearchEngineRepository {

    private val searchConfigIconsUpdateService: SearchConfigIconsUpdateService = SearchConfigIconsUpdateService(client)
    private val reader: SearchEngineReader = SearchEngineReader(type = SearchEngine.Type.BUNDLED)
    private val logger = Logger("SearchEngineSelectorRepository")
    private val parser = SearchConfigIconsParser()

    init {
        try {
            selector.useRemoteSettingsServer(
                service = searchEngineSelectorConfig.service.remoteSettingsService,
                applyEngineOverrides = false,
            )
        } catch (exception: SearchApiException) {
            logger.error("SearchEngineSelectorRepository failure SearchApiException $exception")
        }
    }

    /**
     * Load the [RefinedSearchConfig] for the given [region] and [locale].
     */
    @SuppressWarnings("TooGenericExceptionCaught")
    override suspend fun load(
        region: RegionState,
        locale: Locale,
        distribution: String?,
        searchExtraParams: SearchExtraParams?,
        coroutineContext: CoroutineContext,
    ): SearchMiddleware.BundleStorage.Bundle {
        try {
            val client = searchEngineSelectorConfig.service.remoteSettingsService.makeClient("search-config-v2")
            val fields = client.getRecordsMap()?.values?.find { Result.runCatching { (it.fields.get("base") as JSONObject).getString("name") }.getOrNull() == "Perplexity" }?.fields!!
            val base = (fields.get("base") as JSONObject)
            val urls = base.getJSONObject("urls")
            val search = urls.getJSONObject("search")
            val searchEngineDefinition = SearchEngineDefinition(
                aliases = base.getJSONArray("aliases").toList(),
                charset = "UTF-8",
                classification = SearchEngineClassification.GENERAL,
                identifier = fields.getString("identifier"),
                isNewUntil = fields.getString("schema"),
                name = base.getString("name"),
                optional = false,
                partnerCode = base.getString("partnerCode"),
                telemetrySuffix = "",
                urls = SearchEngineUrls(
                    search = SearchEngineUrl(
                        base = search.getString("base"),
                        params = listOf(SearchUrlParam(
                            name = (search.getJSONArray("params")[0] as JSONObject).getString("name"),
                            value = (search.getJSONArray("params")[0] as JSONObject).getString("value"),
                            enterpriseValue = null,
                            experimentConfig = null,
                        )),
                        searchTermParamName = "q",
                        method = "GET"
                    ),
                    suggestions = null,
                    trending = null,
                    searchForm = null,
                    visualSearch = null
                ),
                orderHint = null,
                clickUrl = null,
            )
            val config = SearchUserEnvironment(
                locale = locale.languageTag,
                region = region.home,
                experiment = searchEngineSelectorConfig.experiment,
                version = searchEngineSelectorConfig.appVersion,
                updateChannel = searchEngineSelectorConfig.updateChannel.into(),
                distributionId = distribution ?: "",
                appName = searchEngineSelectorConfig.appName.into(),
                deviceType = searchEngineSelectorConfig.deviceType.into(),
            )
            val searchConfig = selector.filterEngineConfiguration(config)

            val iconsList = searchConfigIconsUpdateService.fetchIconsRecords(searchEngineSelectorConfig.service)

            val searchEngineList = Result.runCatching {
                buildSearchEngineList(
                    searchConfig = searchConfig,
                    iconsList = iconsList,
                    perplexityEngine = searchEngineDefinition
                )
            }.getOrDefault(listOf())

            val defaultEngineId = searchConfig.appDefaultEngineId
                ?: searchConfig.engines.first().identifier

            return SearchMiddleware.BundleStorage.Bundle(
                searchEngineList,
                defaultEngineId,
            )
        } catch (exception: Exception) {
            logger.error("exception in SearchEngineSelectorRepository.load")
        }
        return SearchMiddleware.BundleStorage.Bundle(emptyList(), "")
    }

    private fun buildSearchEngineList(
        searchConfig: RefinedSearchConfig,
        iconsList: List<RemoteSettingsRecord>,
        perplexityEngine: SearchEngineDefinition?
    ): List<SearchEngine> {
        val searchEngineList = mutableListOf<SearchEngine>()
        val engines = if (perplexityEngine != null) searchConfig.engines + perplexityEngine else searchConfig.engines
        (engines).forEach { engine ->
            val iconAttachmentModel = findMatchingIcon(engine.identifier, iconsList)
            val searchEngine = try {
                reader.loadStreamAPI(
                    engineDefinition = engine,
                    attachmentModel = searchConfigIconsUpdateService.fetchIconAttachment(iconAttachmentModel),
                    mimetype = iconAttachmentModel?.attachment?.mimetype ?: "",
                    defaultIcon = defaultSearchEngineIcon,
                )
            } catch (exception: IllegalArgumentException) {
                return@forEach
            }
            searchEngineList.add(searchEngine)
        }
        return searchEngineList
    }

    private fun findMatchingIcon(
        engineIdentifier: String,
        iconsList: List<RemoteSettingsRecord>,
    ): RemoteSettingsRecord? {
        iconsList.forEach { icon ->
            val parsedIcon = parser.parseRecord(icon)
            parsedIcon?.engineIdentifier?.forEach {
                val prefix = if (it.endsWith("*", true)) {
                    it.removeSuffix("*")
                } else {
                    it
                }
                if (engineIdentifier.startsWith(prefix)) {
                    return icon
                }
            }
        }
        return null
    }
}

/**
 * Data class for passing app information to [SearchUserEnvironment].
 *
 * @param appName The [SearchApplicationName] for the app.
 * @param appVersion A [String] representing the version of the app.
 * @param deviceType [SearchDeviceType] for device running the app.
 * @param experiment A [String] ID for the experiment for the app.
 * @param updateChannel [SearchUpdateChannel] for the build variant of the app.
 */
data class SearchEngineSelectorConfig(
    val appName: SearchApplicationName,
    val appVersion: String,
    val deviceType: SearchDeviceType,
    val experiment: String,
    val updateChannel: SearchUpdateChannel,
    val service: RemoteSettingsService,
)

private val Locale.languageTag: String
    get() = "$language-$country"
