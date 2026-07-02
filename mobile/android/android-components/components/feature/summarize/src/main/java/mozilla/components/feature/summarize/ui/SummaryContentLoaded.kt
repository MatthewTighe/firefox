/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.compose.base.button.IconButton
import mozilla.components.compose.base.theme.AcornTheme
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.feature.summarize.R
import mozilla.components.ui.richtext.RichText
import mozilla.components.ui.richtext.ir.RichDocument
import mozilla.components.ui.icons.R as iconsR

/**
 * Content being shown after the page summary has been generated
 */
@Composable
internal fun SummaryContentLoaded(
    document: RichDocument,
    info: LlmProvider.Info,
    onSettingsClicked: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .padding(horizontal = AcornTheme.layout.space.static200)
            .fillMaxWidth(),
    ) {
        SummarizationHeader(info, onSettingsClicked = onSettingsClicked)
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
        SummarizedContent(
            document = document,
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
        DisclaimerMessage()
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
    }
}

/**
 * Content shown after a summary, allowing the user to ask follow-up questions about the page.
 *
 * @param document The page summary.
 * @param info Metadata about the LLM.
 * @param followUpQuestion The user's current follow-up question, if any.
 * @param followUpResponse The follow-up response so far, if any.
 * @param isResponding Whether a follow-up response is currently streaming.
 * @param onSettingsClicked Invoked when the settings control is tapped.
 * @param onFollowUpSubmitted Invoked with the user's follow-up question.
 */
@Composable
internal fun FollowUpContent(
    document: RichDocument,
    info: LlmProvider.Info,
    followUpQuestion: String? = null,
    followUpResponse: RichDocument? = null,
    isResponding: Boolean = false,
    onSettingsClicked: () -> Unit = {},
    onFollowUpSubmitted: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .padding(horizontal = AcornTheme.layout.space.static200)
            .fillMaxWidth(),
    ) {
        SummarizationHeader(info, onSettingsClicked = onSettingsClicked)
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SummarizedContent(document = document, modifier = Modifier.fillMaxWidth())
            followUpQuestion?.let {
                Spacer(Modifier.height(AcornTheme.layout.space.static200))
                Text(text = it, fontWeight = FontWeight.Bold)
            }
            followUpResponse?.let {
                Spacer(Modifier.height(AcornTheme.layout.space.static100))
                SelectionContainer { RichText(document = it) }
            }
        }
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
        if (!isResponding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(stringResource(R.string.mozac_feature_summarize_follow_up_placeholder))
                    },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onFollowUpSubmitted(input.trim())
                            input = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.mozac_feature_summarize_follow_up_send))
                }
            }
        }
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
        DisclaimerMessage()
        Spacer(Modifier.height(AcornTheme.layout.space.static200))
    }
}

@Composable
internal fun SummarizationHeader(
    info: LlmProvider.Info,
    modifier: Modifier = Modifier,
    onSettingsClicked: () -> Unit,
) {
    Row(
        modifier = modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelInformation(info)

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onSettingsClicked,
            contentDescription = stringResource(
                id = R.string.mozac_summarize_settings_button_content_description,
            ),
        ) {
            Icon(
                painter = painterResource(id = iconsR.drawable.mozac_ic_settings_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelInformation(
    info: LlmProvider.Info,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))

        info.iconRes?.let {
            Image(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .offset(y = (-1).dp),
            )

            Spacer(Modifier.width(8.dp))
        }

        Text(
            text = stringResource(
                id = R.string.mozac_feature_summarize_summary_model,
                stringResource(info.nameRes),
            ),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun SummarizedContent(document: RichDocument, modifier: Modifier = Modifier) {
    SelectionContainer(modifier = modifier) {
        RichText(document = document)
    }
}

@Composable
private fun DisclaimerMessage() {
    Text(
        text = stringResource(R.string.mozac_feature_summarize_disclaimer_message),
        fontSize = 14.sp,
        modifier = Modifier
            .height(24.dp)
            .width(AcornTheme.layout.size.containerMaxWidth),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
