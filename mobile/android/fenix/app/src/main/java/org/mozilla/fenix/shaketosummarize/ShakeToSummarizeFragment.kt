package org.mozilla.fenix.shaketosummarize

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import org.mozilla.fenix.R
import kotlinx.coroutines.launch
import org.mozilla.fenix.theme.FirefoxTheme

class ShakeToSummarizeFragment: DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.DialogStyleBase)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
           ShakeToSummarizeScreen(
               onDismiss = {
                   dismiss()
               },
           )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeToSummarizeScreen(
    onDismiss: () -> Unit,
) {
    FirefoxTheme {
       ShakeToSummarizeBottomSheet {
           onDismiss()
       }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeToSummarizeBottomSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            onDismiss()
        },
        sheetState = sheetState,
        scrimColor = MaterialTheme.colorScheme.scrim,
    ) {
        BackHandler {
            onDismiss()
        }

        Row(
            modifier = Modifier.fillMaxWidth(), // Ensure the row takes up the full width
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            AiAnimatedText(text = "Summarizing...")
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveGradientModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Animate a normalized offset [0..1) forever
    val infinite = rememberInfiniteTransition(label = "wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // A "stripe length" in px: bigger = slower/broader waves
    val stripeWidthPx = with(LocalDensity.current) { 260.dp.toPx() }

    // Move the gradient left→right by shifting the brush coordinates.
    // We create a repeating pattern by spanning multiple stripe widths.
    val shiftPx = phase * stripeWidthPx

    val brush = Brush.linearGradient(
        // Multi-stop palette gives a wavy look as it slides
        colors = listOf(
            Color(0xFF00BCD4), // cyan
            Color(0xFF3F51B5), // indigo
            Color(0xFFE91E63), // pink
            Color(0xFFFFC107), // amber
            Color(0xFF00BCD4)  // back to start for seamless wrap-ish feel
        ),
        start = Offset(x = -stripeWidthPx + shiftPx, y = 0f),
        end = Offset(x = stripeWidthPx * 2f + shiftPx, y = 0f)
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Important: make the sheet container transparent so our gradient shows
        containerColor = Color.Transparent,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier =  Modifier
            .background(brush = brush, shape = MaterialTheme.shapes.extraLarge)
    ) {
        content()
    }
}

@Composable
private fun WaveGradientSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Animate a normalized offset [0..1) forever
    val infinite = rememberInfiniteTransition(label = "wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // A "stripe length" in px: bigger = slower/broader waves
    val stripeWidthPx = with(LocalDensity.current) { 260.dp.toPx() }

    // Move the gradient left→right by shifting the brush coordinates.
    // We create a repeating pattern by spanning multiple stripe widths.
    val shiftPx = phase * stripeWidthPx

    val brush = Brush.linearGradient(
        // Multi-stop palette gives a wavy look as it slides
        colors = listOf(
            Color(0xFF00BCD4), // cyan
            Color(0xFF3F51B5), // indigo
            Color(0xFFE91E63), // pink
            Color(0xFFFFC107), // amber
            Color(0xFF00BCD4)  // back to start for seamless wrap-ish feel
        ),
        start = Offset(x = -stripeWidthPx + shiftPx, y = 0f),
        end = Offset(x = stripeWidthPx * 2f + shiftPx, y = 0f)
    )

    // Use a Surface if you want tonal elevation/shape while keeping gradient.
    // Just set color to Transparent and draw gradient behind it.
    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
