/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.R
import org.mozilla.fenix.shopping.ui.ext.headingResource
import org.mozilla.fenix.theme.FirefoxTheme

private val cardShape = RoundedCornerShape(8.dp)
private val defaultCardElevation = 5.dp
private val defaultCardContentPadding = 16.dp

/**
 * A card container for informative or error messages that can be expanded and collapsed.
 *
 * @param title The title of the card.
 * @param isExpanded Whether or not the card is expanded.
 * @param modifier Modifier to be applied to the card.
 * @param onExpandToggleClick Invoked when the card is expanded or collapsed.
 * @param content The content of the card.
 */
@Composable
fun ExpandableInfoCardContainer(
    title: String,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onExpandToggleClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    InfoCardContainer(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        val titleContentDescription = headingResource(title)

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .clickable(
                    onClickLabel = if (isExpanded) {
                        stringResource(R.string.a11y_action_label_collapse)
                    } else {
                        stringResource(R.string.a11y_action_label_expand)
                    },
                    onClick = onExpandToggleClick,
                )
                .padding(defaultCardContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = FirefoxTheme.colors.textPrimary,
                style = FirefoxTheme.typography.headline8,
                modifier = Modifier.semantics {
                    contentDescription = titleContentDescription
                },
            )

            val chevronDrawable = if (isExpanded) {
                R.drawable.mozac_ic_chevron_up_20
            } else {
                R.drawable.mozac_ic_chevron_down_20
            }

            Icon(
                painter = painterResource(id = chevronDrawable),
                contentDescription = if (isExpanded) {
                    stringResource(R.string.a11y_state_label_expanded)
                } else {
                    stringResource(R.string.a11y_state_label_collapsed)
                },
                tint = FirefoxTheme.colors.iconPrimary,
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Box(
                modifier = Modifier.padding(
                    start = defaultCardContentPadding,
                    end = defaultCardContentPadding,
                    bottom = defaultCardContentPadding,
                ),
            ) {
                content()
            }
        }
    }
}

/**
 * A card container for informative or error messages.
 *
 * @param modifier Modifier to be applied to the card.
 * @param backgroundColor The background color of the card.
 * @param elevation The elevation of the card.
 * @param contentPadding Padding used within the card container.
 * @param content The content of the card.
 */
@Composable
fun InfoCardContainer(
    modifier: Modifier,
    backgroundColor: Color = FirefoxTheme.colors.layer2,
    elevation: Dp = defaultCardElevation,
    contentPadding: PaddingValues = PaddingValues(defaultCardContentPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
        ) {
            content()
        }
    }
}

@PreviewLightDark
@Composable
private fun InfoCardContainerPreview() {
    FirefoxTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            var isExpanded by remember { mutableStateOf(true) }

            InfoCardContainer(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Info Check Card Content",
                    color = FirefoxTheme.colors.textPrimary,
                    style = FirefoxTheme.typography.headline8,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ExpandableInfoCardContainer(
                title = "Info Expandable Card",
                modifier = Modifier.fillMaxWidth(),
                isExpanded = isExpanded,
                onExpandToggleClick = { isExpanded = !isExpanded },
            ) {
                Text(
                    text = "content",
                    color = FirefoxTheme.colors.textPrimary,
                    style = FirefoxTheme.typography.body2,
                )
            }
        }
    }
}
