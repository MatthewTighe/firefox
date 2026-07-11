/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.theme.AcornTheme
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.feature.summarize.R
import mozilla.components.feature.summarize.ui.gradient.summaryGradientColors

private const val SHIMMER_DURATION_MS = 1800
private const val SHIMMER_TRAVEL = 400f
private const val SHIMMER_WINDOW = 200f

/**
 * Header plus a chat-style bubble showing "Thinking…", displayed while waiting for the model to
 * start responding. The text is animated with the brand gradient sweeping across it.
 *
 * @param info Metadata about the LLM handling the request.
 * @param onSettingsClicked Invoked when the settings control is tapped.
 */
@Composable
internal fun ThinkingContent(
    info: LlmProvider.Info,
    modifier: Modifier = Modifier,
    onSettingsClicked: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .padding(horizontal = AcornTheme.layout.space.static200)
            .fillMaxWidth(),
    ) {
        SummarizationHeader(info, onSettingsClicked = onSettingsClicked)
        Spacer(Modifier.height(AcornTheme.layout.space.static300))
        ThinkingBubble()
    }
}

@Composable
private fun ThinkingBubble(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val progress by transition.animateFloat(
        initialValue = -SHIMMER_TRAVEL,
        targetValue = SHIMMER_TRAVEL,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingShimmer",
    )

    val brush = Brush.linearGradient(
        colors = summaryGradientColors,
        start = Offset(progress - SHIMMER_WINDOW, 0f),
        end = Offset(progress + SHIMMER_WINDOW, 0f),
    )

    Row(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(brush = brush)) {
                        append(stringResource(R.string.mozac_feature_summarize_thinking))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = AcornTheme.typography.body1,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Preview
@Composable
private fun ThinkingContentPreview() {
    AcornTheme {
        ThinkingContent(info = LlmProvider.Info(nameRes = R.string.mozac_feature_summarize_thinking))
    }
}
