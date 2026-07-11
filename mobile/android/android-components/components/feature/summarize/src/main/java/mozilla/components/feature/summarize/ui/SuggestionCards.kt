/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.theme.AcornTheme
import mozilla.components.feature.summarize.R
import mozilla.components.feature.summarize.SummarizationSuggestion

/**
 * A "Suggested questions" header above a vertical stack of suggestion cards. Selecting one starts
 * a request about the page.
 *
 * @param onSuggestionSelected Invoked with the [SummarizationSuggestion] the user tapped.
 */
@Composable
internal fun SuggestionCards(
    onSuggestionSelected: (SummarizationSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AcornTheme.layout.space.static100),
    ) {
        Text(
            text = stringResource(R.string.mozac_feature_summarize_suggested_questions),
            style = AcornTheme.typography.body2,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SummarizationSuggestion.entries.forEach { suggestion ->
            SuggestionCard(
                label = stringResource(suggestion.labelRes),
                onClick = { onSuggestionSelected(suggestion) },
            )
        }
    }
}

@Composable
private fun SuggestionCard(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = AcornTheme.typography.body2,
        )
    }
}
