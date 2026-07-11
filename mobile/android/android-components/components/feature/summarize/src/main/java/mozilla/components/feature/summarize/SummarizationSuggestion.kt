/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import androidx.annotation.StringRes

/**
 * A predefined action the user can pick from a suggestion card to start a request about the page.
 *
 * @property labelRes The user-facing label shown on the suggestion card.
 */
enum class SummarizationSuggestion(@StringRes val labelRes: Int) {
    /** Summarize the current page. */
    Summarize(R.string.mozac_feature_summarize_suggestion_summarize),

    /** Extract the key points from the current page. */
    KeyPoints(R.string.mozac_feature_summarize_suggestion_key_points),

    /** Explain the current page in simple terms. */
    SimpleTerms(R.string.mozac_feature_summarize_suggestion_simple_terms),
}
