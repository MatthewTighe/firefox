package org.mozilla.fenix.shaketosummarize

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.fenix.R
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONObject
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

sealed class ShakeToSummarizeState {
    object Inert : ShakeToSummarizeState()
    object Loading : ShakeToSummarizeState()
    data class Loaded(val text: AnnotatedString) : ShakeToSummarizeState()
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
        val client = requireComponents.core.client

        setContent {
           ShakeToSummarizeScreen(
               getSummarizedText = {
                   val pageContent = getPageContent()
                   val body = withContext(Dispatchers.Default) {
                       val response = client.fetch(generateRequest(pageContent))
                       response.body.string(Charsets.UTF_8)
                   }
                   JSONObject(body).getContent()
               },
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

fun JSONObject.getContent(): String {
    val choices = getJSONArray("choices")
    val firstChoice = choices.getJSONObject(0)
    val message = firstChoice.getJSONObject("message")
    return message.getString("content")
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
    var state by remember { mutableStateOf<ShakeToSummarizeState>(ShakeToSummarizeState.Loading) }

    LaunchedEffect(Unit) {
        val summarizedText = getSummarizedText()
        state = ShakeToSummarizeState.Loaded(text = AnnotatedString(summarizedText))
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
    text: AnnotatedString,
    style: TextStyle = TextStyle.Default,
    speedMillis: Long = 30L // Slowed down slightly for better visual effect
) {
    // 1. Split the AnnotatedString into a list of AnnotatedStrings
    val words = remember(text) {
        val list = mutableListOf<AnnotatedString>()
        var start = 0
        val pattern = Regex("\\s+")

        // Find all whitespace matches to determine word boundaries
        pattern.findAll(text.text).forEach { result ->
            // Add the word plus the trailing whitespace
            list.add(text.subSequence(start, result.range.last + 1))
            start = result.range.last + 1
        }

        // Add the final word if there is one
        if (start < text.length) {
            list.add(text.subSequence(start, text.length))
        }
        list
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Invisible text used to reserve the total space (prevents layout jumping)
        Text(
            text = text,
            style = style,
            modifier = Modifier.alpha(0f)
        )

        FlowRow {
            words.forEachIndexed { index, annotatedWord ->
                AnimatedWord(
                    annotatedWord = annotatedWord,
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
    annotatedWord: AnnotatedString,
    index: Int,
    delayStep: Long,
    style: TextStyle
) {
    val animatedProgress = remember { Animatable(0f) }

    // Use the content as the key so it re-animates if the text changes
    LaunchedEffect(annotatedWord) {
        delay(index * delayStep)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
        )
    }

    Text(
        text = annotatedWord,
        style = style,
        modifier = Modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * 10f
            }
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


private val summarizedText12213 = """
    # Vidit ripas ab flexisque

    ## Damna visceraque memorant

    Lorem markdownum non **est parvoque**: cinxisse secundo abstinuit et? Spoliata
    **aetherias cornua** ab et possederat ad longo annis ex o Naxon: cum membra
    revocare et.

    Omnia [inque](#damna-visceraque-memorant) sustinuit tumidum
    [iussis](#in-tenus-tamen): nullus vagus congelat genitoris. Urbs aut triformis,
    ut iussos inutilior enim *indueret*: orba an hic redde avito. Nec quo, diruerent
    clamore tamen ferroque et finxit in artus ocior infert greges. Glorior fortius
    prior signisque regionibus adhuc, tacuit, mutat **consultaque**, putatur quae
    fixurus corpora portenditur! Tumulo `errorCharacterBar` virides duos.

    Paventem renovat fulvas hoc, omnia me dabat Zancle videt. Per marmore cuspide
    sibi, non inque; somnus terras Lapithas pars caeli vipereas satis latus, nec
    ardescunt caecis. Faciemque oscula sagitta iustius, sua supero prius sororis
    admonitu convicia. Sic Aurora causa patiens tandem trementes, aetasque hominem
    *vulnera*, Actorides indicium tendentem, ducit racemiferis deprendit potest
    [iacentes](#vidit-ripas-ab-flexisque).

    ## In tenus tamen

    Nam est edere fine est carinae Ismenides seu sic fuit ego. Miseros *a eurus
    lacus* verba per vulnera. Possim iter flectentem fugerat cecidere carmen dea
    laetabile inanes, est deducit `character` domos, caelo! Prodibant Latinum hic
    cruor arvaque *pallent et latere* collocat multorum legi. Veniensque pudore
    leonem serius; [avus](#damna-visceraque-memorant) nec [frangit nuper
    loquentis](#vidit-ripas-ab-flexisque).

    > Felicia omni; nec maestae flumina nostri. Terebrata digitosque ferrum
    > furialibus notior interritus arma possedit amplexaque; et ait sui
    > `development` percussit nimium se inania mater. Crimina garrula minimo quoque,
    > imas pro humili etiam vincla, mihi exiguo agant est, corpusque fecundus
    > dumque?

    Silentia venite, Placatus relictum circum Argolico, messes septem circumvolat et
    possem in hominum nulla. Est dici tempore dextrae fecit nec: quique ne obvertit
    iuvenem. Inpleratque procul leves, non istis Neptunus dixit, mihi retemptantem
    haec Libys de pudici magni et adacta quereris hominis. Est vulnera mandabat
    illo, vos est corpora cladis nec sunto a.

    ## De uvis servato litora

    Territus pater sequentia, nefando, canis in exitus at quoque alto minimae, enim
    lugenti fuit, mea! Silvas paelice occiderat huc aede mulcendas vultus oscula
    victoris pectora quantumque venerisque rapit. Cum mihi auras, femina potiuntur a
    plausis [tectaque resupinus Titania](#damna-visceraque-memorant) fugiant pudoris
    temptabat sucosque, sit trahit formae.

    - Cum clamor ramos longa sine videtur
    - Est nec
    - Radiataque et opus si abdita Phinea regis
    - Habet gentisque non
    - Hortis dira ramis sinu operatus verbaque cruore
    - Nimis Cyane proles
""".trimIndent()


fun generateRequest(content: String): Request {
    return Request(
        url = "https://mlpa-nonprod-stage-mozilla.global.ssl.fastly.net/v1/chat/completions",
        method = Request.Method.POST,
        headers = MutableHeaders(
            "authorization" to authorizationToken,
            "content-type" to "application/json",
            "service-type" to "ai",
        ),
        body = requestBody(content)
    )
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

fun requestBody(content: String): Request.Body {
    val prompt = "Summarize the following article in a single, dense paragraph. " +
            "Remove any links from Markdown. The summary should be presented " +
            "as a single block of text. Article: $content"

    val requestObj = ChatRequest(
        model = "mistral-small-2503",
        messages = listOf(ChatMessage(role = "user", content = prompt))
    )

    // This will correctly escape all quotes and newlines within the content
    val jsonString = Json.encodeToString(ChatRequest.serializer(), requestObj)

    return Request.Body.fromString(jsonString)
}


val authorizationToken = "<token>"
