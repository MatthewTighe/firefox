package org.mozilla.fenix.shaketosummarize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun parseMarkdown(content: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = content.split("\n")

        lines.forEachIndexed { index, line ->
            // 1. Handle Headings (e.g., # Heading)
            val headingMatch = Regex("^(#{1,3})\\s+(.*)$").find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2]

                pushStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (level) {
                            1 -> 24.sp
                            2 -> 20.sp
                            else -> 18.sp
                        }
                    )
                )
                append(text)
                pop()
            } else {
                // 2. Handle Bold and Italic within normal text
                parseInlineStyles(line)
            }

            // Add newline back if it's not the last line
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.parseInlineStyles(text: String) {
    // Regex for Bold (**text**) and Italic (*text*)
    val pattern = Regex("(\\*\\*|\\*)(.*?)\\1")
    var lastIndex = 0

    pattern.findAll(text).forEach { match ->
        // Append text before the match
        append(text.substring(lastIndex, match.range.first))

        val delimiter = match.groupValues[1]
        val content = match.groupValues[2]

        val style = if (delimiter == "**") {
            SpanStyle(fontWeight = FontWeight.Bold)
        } else {
            SpanStyle(fontStyle = FontStyle.Italic)
        }

        pushStyle(style)
        append(content)
        pop()

        lastIndex = match.range.last + 1
    }

    // Append remaining text
    append(text.substring(lastIndex))
}
