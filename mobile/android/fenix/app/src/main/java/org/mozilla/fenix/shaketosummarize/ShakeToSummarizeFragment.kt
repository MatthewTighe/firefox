package org.mozilla.fenix.shaketosummarize

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.delay
import org.mozilla.fenix.R
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

sealed class ShakeToSummarizeState {
    object Inert : ShakeToSummarizeState()
    object Loading : ShakeToSummarizeState()
    data class Loaded(val text: String) : ShakeToSummarizeState()
}

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
            var textContent by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                textContent = runCatching {
                    // fake delay to simulate us loading the content.
                    // all this dance would probably happen in a middleware somewhere
                    delay(3000)
                    getPageContent()
                }.getOrNull() ?: ""
            }
           ShakeToSummarizeScreen(
               getSummarizedText = { getPageContent() },
               onDismiss = {
                   dismiss()
               },
           )
        }
    }

    private suspend fun getPageContent(): String = suspendCoroutine { continuation ->
        val selectedTab =
            requireContext().components.core.store.state.selectedTab ?: error("No selected tab")
        selectedTab.engineState.engineSession?.getPageTextContent(
            url = selectedTab.content.url,
            onResult = {
                continuation.resume(it)
            },
            onException = {
                continuation.resumeWithException(it)
            },
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeToSummarizeScreen(
    getSummarizedText: suspend () -> String,
    onDismiss: () -> Unit,
) {
    FirefoxTheme {
       ShakeToSummarizeBottomSheet(getSummarizedText = getSummarizedText) {
           onDismiss()
       }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeToSummarizeBottomSheet(
    getSummarizedText: suspend () -> String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ShakeToSummarizeState>(ShakeToSummarizeState.Loading) }

    LaunchedEffect(Unit) {
        val summarizedText = getSummarizedText()
        delay(2000L)
        state = ShakeToSummarizeState.Loaded(text = summarizedText)
    }

    ModalBottomSheet(
        isLoading = state is ShakeToSummarizeState.Loading,
        onDismissRequest = {
            onDismiss()
        },
        sheetState = sheetState,
        scrimColor = MaterialTheme.colorScheme.scrim,
    ) {
        BackHandler {
            onDismiss()
        }

        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
            ) {
                if (state is ShakeToSummarizeState.Loading) {
                    LoadingView()
                }

                if (state is ShakeToSummarizeState.Loaded) {
                    SummarizedPageView((state as ShakeToSummarizeState.Loaded))
                }
            }
        }
    }
}

@Composable
fun LoadingView() {
    Row(
        modifier = Modifier.fillMaxWidth(), // Ensure the row takes up the full width
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        AiAnimatedText(text = "Summarizing...")
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun SummarizedPageView(state: ShakeToSummarizeState.Loaded) {
    AiGeneratedText(state.text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiGeneratedText(
    text: String,
    style: TextStyle = TextStyle.Default,
    speedMillis: Long = 10L
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.alpha(0f)
        )

        val words = remember(text) { text.split(" ") }
        FlowRow {
            words.forEachIndexed { index, word ->
                AnimatedWord(
                    word = "$word ",
                    index = index,
                    delayStep = speedMillis,
                    style = style
                )
            }
        }
    }
}

@Composable
private fun AnimatedWord(
    word: String,
    index: Int,
    delayStep: Long,
    style: TextStyle
) {
    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(key1 = word) {
        // Stagger the start of each word based on its position
        delay(index * delayStep)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 400,
                easing = LinearEasing
            )
        )
    }

    Text(
        text = word,
        style = style,
        modifier = Modifier
            .graphicsLayer {
                // Subtle upward slide
                translationY = (1f - animatedProgress.value) * 15f
            }
            .alpha(animatedProgress.value)
    )
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
