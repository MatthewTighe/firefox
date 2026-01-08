package org.mozilla.fenix.shaketosummarize

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Outline
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Stable
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

//
//import androidx.compose.animation.core.Animatable
//import androidx.compose.animation.core.AnimationVector1D
//import androidx.compose.animation.core.FiniteAnimationSpec
//import androidx.compose.animation.core.animateFloatAsState
//import androidx.compose.animation.core.spring
//import androidx.compose.foundation.Canvas
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.gestures.Orientation
//import androidx.compose.foundation.gestures.detectTapGestures
//import androidx.compose.foundation.gestures.draggable
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.BoxScope
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.ColumnScope
//import androidx.compose.foundation.layout.WindowInsets
//import androidx.compose.foundation.layout.consumeWindowInsets
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.imePadding
//import androidx.compose.foundation.layout.widthIn
//import androidx.compose.foundation.layout.windowInsetsPadding
//import androidx.compose.material3.BottomSheetDefaults
//import androidx.compose.material3.ExperimentalMaterial3Api
//import androidx.compose.material3.Surface
//import androidx.compose.material3.contentColorFor
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.Immutable
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.SideEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.rememberCoroutineScope
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.GraphicsLayerScope
//import androidx.compose.ui.graphics.Shape
//import androidx.compose.ui.graphics.TransformOrigin
//import androidx.compose.ui.graphics.graphicsLayer
//import androidx.compose.ui.graphics.isSpecified
//import androidx.compose.ui.input.nestedscroll.nestedScroll
//import androidx.compose.ui.input.pointer.pointerInput
//import androidx.compose.ui.semantics.collapse
//import androidx.compose.ui.semantics.contentDescription
//import androidx.compose.ui.semantics.dismiss
//import androidx.compose.ui.semantics.expand
//import androidx.compose.ui.semantics.isTraversalGroup
//import androidx.compose.ui.semantics.onClick
//import androidx.compose.ui.semantics.paneTitle
//import androidx.compose.ui.semantics.semantics
//import androidx.compose.ui.semantics.traversalIndex
//import androidx.compose.ui.unit.Dp
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.util.lerp
//import com.google.android.material.animation.MotionSpec
//import kotlin.math.max
//import kotlin.math.min
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.launch
//
///**
// * [Material Design modal bottom sheet](https://m3.material.io/components/bottom-sheets/overview)
// *
// * Modal bottom sheets are used as an alternative to inline menus or simple dialogs on mobile,
// * especially when offering a long list of action items, or when items require longer descriptions
// * and icons. Like dialogs, modal bottom sheets appear in front of app content, disabling all other
// * app functionality when they appear, and remaining on screen until confirmed, dismissed, or a
// * required action has been taken.
// *
// * ![Bottom sheet
// * image](https://developer.android.com/images/reference/androidx/compose/material3/bottom_sheet.png)
// *
// * A simple example of a modal bottom sheet looks like this:
// *
// * @sample androidx.compose.material3.samples.ModalBottomSheetSample
// * @param onDismissRequest Executes when the user clicks outside of the bottom sheet, after sheet
// *   animates to [Hidden].
// * @param modifier Optional [Modifier] for the bottom sheet.
// * @param sheetState The state of the bottom sheet.
// * @param sheetMaxWidth [Dp] that defines what the maximum width the sheet will take. Pass in
// *   [Dp.Unspecified] for a sheet that spans the entire screen width.
// * @param sheetGesturesEnabled Whether the bottom sheet can be interacted with by gestures.
// * @param shape The shape of the bottom sheet.
// * @param containerColor The color used for the background of this bottom sheet
// * @param contentColor The preferred color for content inside this bottom sheet. Defaults to either
// *   the matching content color for [containerColor], or to the current [LocalContentColor] if
// *   [containerColor] is not a color from the theme.
// * @param tonalElevation when [containerColor] is [ColorScheme.surface], a translucent primary color
// *   overlay is applied on top of the container. A higher tonal elevation value will result in a
// *   darker color in light theme and lighter color in dark theme. See also: [Surface].
// * @param scrimColor Color of the scrim that obscures content when the bottom sheet is open.
// * @param dragHandle Optional visual marker to swipe the bottom sheet.
// * @param contentWindowInsets callback which provides window insets to be passed to the bottom sheet
// *   content via [Modifier.windowInsetsPadding]. [ModalBottomSheet] will pre-emptively consume top
// *   insets based on it's current offset. This keeps content outside of the expected window insets
// *   at any position.
// * @param properties [ModalBottomSheetProperties] for further customization of this modal bottom
// *   sheet's window behavior.
// * @param content The content to be displayed inside the bottom sheet.
// */
//@Composable
//@ExperimentalMaterial3Api
//fun ModalBottomSheet(
//    onDismissRequest: () -> Unit,
//    modifier: Modifier = Modifier,
//    sheetState: SheetState = rememberModalBottomSheetState(),
//    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
//    sheetGesturesEnabled: Boolean = true,
//    shape: Shape = BottomSheetDefaults.ExpandedShape,
//    containerColor: Color = BottomSheetDefaults.ContainerColor,
//    contentColor: Color = contentColorFor(containerColor),
//    tonalElevation: Dp = 0.dp,
//    scrimColor: Color = BottomSheetDefaults.ScrimColor,
//    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
//    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
//    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
//    content: @Composable ColumnScope.() -> Unit,
//) {
//    // TODO Load the motionScheme tokens from the component tokens file
//    val anchoredDraggableMotion = spring<Float>(
//        dampingRatio = 0.8f,
//        stiffness = 800.0f,
//    )
//    val showMotion = anchoredDraggableMotion
//    val hideMotion =  spring<Float>(
//        dampingRatio = 1.0f,
//        stiffness = 3800.0f,
//    )
//
//    SideEffect {
//        sheetState.showMotionSpec = showMotion
//        sheetState.hideMotionSpec = hideMotion
//        sheetState.anchoredDraggableMotionSpec = anchoredDraggableMotion
//    }
//    val scope = rememberCoroutineScope()
//    val animateToDismiss: () -> Unit = {
//        if (sheetState.confirmValueChange(SheetValue.Hidden)) {
//            scope
//                .launch { sheetState.hide() }
//                .invokeOnCompletion {
//                    if (!sheetState.isVisible) {
//                        onDismissRequest()
//                    }
//                }
//        }
//    }
//    val settleToDismiss: (velocity: Float) -> Unit = {
//        scope
//            .launch { sheetState.settle(it) }
//            .invokeOnCompletion { if (!sheetState.isVisible) onDismissRequest() }
//    }
//
//    val predictiveBackProgress = remember { Animatable(initialValue = 0f) }
//
//    ModalBottomSheetDialog(
//        properties = properties,
//        contentColor = contentColor,
//        onDismissRequest = {
//            if (sheetState.currentValue == Expanded && sheetState.hasPartiallyExpandedState) {
//                // Smoothly animate away predictive back transformations since we are not fully
//                // dismissing. We don't need to do this in the else below because we want to
//                // preserve the predictive back transformations (scale) during the hide animation.
//                scope.launch { predictiveBackProgress.animateTo(0f) }
//                scope.launch { sheetState.partialExpand() }
//            } else { // Is expanded without collapsed state or is collapsed.
//                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
//            }
//        },
//        predictiveBackProgress = predictiveBackProgress,
//    ) {
//        Box(modifier = Modifier.fillMaxSize().imePadding().semantics { isTraversalGroup = true }) {
//            Scrim(
//                color = scrimColor,
//                onDismissRequest = animateToDismiss,
//                visible = sheetState.targetValue != Hidden,
//                dismissEnabled = properties.shouldDismissOnClickOutside,
//            )
//            ModalBottomSheetContent(
//                predictiveBackProgress,
//                scope,
//                animateToDismiss,
//                settleToDismiss,
//                modifier,
//                sheetState,
//                sheetMaxWidth,
//                sheetGesturesEnabled,
//                shape,
//                containerColor,
//                contentColor,
//                tonalElevation,
//                dragHandle,
//                contentWindowInsets,
//                content,
//            )
//        }
//    }
//    if (sheetState.hasExpandedState) {
//        LaunchedEffect(sheetState) { sheetState.show() }
//    }
//}
//
//@Deprecated(
//    level = DeprecationLevel.HIDDEN,
//    message = "Maintained for Binary compatibility. Use overload with sheetGesturesEnabled param.",
//)
//@Composable
//@ExperimentalMaterial3Api
//fun ModalBottomSheet(
//    onDismissRequest: () -> Unit,
//    modifier: Modifier = Modifier,
//    sheetState: SheetState = rememberModalBottomSheetState(),
//    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
//    shape: Shape = BottomSheetDefaults.ExpandedShape,
//    containerColor: Color = BottomSheetDefaults.ContainerColor,
//    contentColor: Color = contentColorFor(containerColor),
//    tonalElevation: Dp = 0.dp,
//    scrimColor: Color = BottomSheetDefaults.ScrimColor,
//    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
//    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
//    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
//    content: @Composable ColumnScope.() -> Unit,
//) =
//    ModalBottomSheet(
//        onDismissRequest = onDismissRequest,
//        modifier = modifier,
//        sheetState = sheetState,
//        sheetMaxWidth = sheetMaxWidth,
//        sheetGesturesEnabled = true,
//        shape = shape,
//        containerColor = containerColor,
//        contentColor = contentColor,
//        tonalElevation = tonalElevation,
//        scrimColor = scrimColor,
//        dragHandle = dragHandle,
//        contentWindowInsets = contentWindowInsets,
//        properties = properties,
//        content = content,
//    )
//
//@Composable
//@ExperimentalMaterial3Api
//internal fun BoxScope.ModalBottomSheetContent(
//    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
//    scope: CoroutineScope,
//    animateToDismiss: () -> Unit,
//    settleToDismiss: (velocity: Float) -> Unit,
//    modifier: Modifier = Modifier,
//    sheetState: SheetState = rememberModalBottomSheetState(),
//    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
//    sheetGesturesEnabled: Boolean = true,
//    shape: Shape = BottomSheetDefaults.ExpandedShape,
//    containerColor: Color = BottomSheetDefaults.ContainerColor,
//    contentColor: Color = contentColorFor(containerColor),
//    tonalElevation: Dp = BottomSheetDefaults.Elevation,
//    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
//    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
//    content: @Composable ColumnScope.() -> Unit,
//) {
//    // val bottomSheetPaneTitle = getString(string = Strings.BottomSheetPaneTitle)
//
//    Surface(
//        modifier =
//            modifier
//                .align(Alignment.TopCenter)
//                .widthIn(max = sheetMaxWidth)
//                .fillMaxWidth()
//                .then(
//                    if (sheetGesturesEnabled)
//                        Modifier.nestedScroll(
//                            remember(sheetState) {
//                                ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection(
//                                    sheetState = sheetState,
//                                    orientation = Orientation.Vertical,
//                                    onFling = settleToDismiss,
//                                )
//                            }
//                        )
//                    else Modifier
//                )
//                .draggableAnchors(sheetState.anchoredDraggableState, Orientation.Vertical) {
//                    sheetSize,
//                    constraints ->
//                    val fullHeight = constraints.maxHeight.toFloat()
//                    val newAnchors = DraggableAnchors {
//                        Hidden at fullHeight
//                        if (
//                            sheetSize.height > (fullHeight / 2) && !sheetState.skipPartiallyExpanded
//                        ) {
//                            PartiallyExpanded at fullHeight / 2f
//                        }
//                        if (sheetSize.height != 0) {
//                            Expanded at max(0f, fullHeight - sheetSize.height)
//                        }
//                    }
//                    val newTarget =
//                        when (sheetState.anchoredDraggableState.targetValue) {
//                            Hidden -> Hidden
//                            PartiallyExpanded -> {
//                                val hasPartiallyExpandedState =
//                                    newAnchors.hasAnchorFor(PartiallyExpanded)
//                                val newTarget =
//                                    if (hasPartiallyExpandedState) PartiallyExpanded
//                                    else if (newAnchors.hasAnchorFor(Expanded)) Expanded else Hidden
//                                newTarget
//                            }
//                            Expanded -> {
//                                if (newAnchors.hasAnchorFor(Expanded)) Expanded else Hidden
//                            }
//                        }
//                    return@draggableAnchors newAnchors to newTarget
//                }
//                .draggable(
//                    state = sheetState.anchoredDraggableState.draggableState,
//                    orientation = Orientation.Vertical,
//                    enabled = sheetGesturesEnabled && sheetState.isVisible,
//                    startDragImmediately = sheetState.anchoredDraggableState.isAnimationRunning,
//                    onDragStopped = { settleToDismiss(it) },
//                )
//                .semantics {
//                    paneTitle = bottomSheetPaneTitle
//                    traversalIndex = 0f
//                }
//                .consumeWindowInsets(WindowInsets(top = sheetState.offset.toInt().coerceAtLeast(0)))
//                .graphicsLayer {
//                    val sheetOffset = sheetState.anchoredDraggableState.offset
//                    val sheetHeight = size.height
//                    if (!sheetOffset.isNaN() && !sheetHeight.isNaN() && sheetHeight != 0f) {
//                        val progress = predictiveBackProgress.value
//                        scaleX = calculatePredictiveBackScaleX(progress)
//                        scaleY = calculatePredictiveBackScaleY(progress)
//                        transformOrigin =
//                            TransformOrigin(0.5f, (sheetOffset + sheetHeight) / sheetHeight)
//                    }
//                }
//                // Scale up the Surface vertically in case the sheet's offset overflows below the
//                // min anchor. This is done to avoid showing a gap when the sheet opens and bounces
//                // when it's applied with a bouncy motion. Note that the content inside the Surface
//                // is scaled back down to maintain its aspect ratio (see below).
//                .verticalScaleUp(sheetState),
//        shape = shape,
//        color = containerColor,
//        contentColor = contentColor,
//        tonalElevation = tonalElevation,
//    ) {
//        Column(
//            Modifier.fillMaxWidth()
//                .windowInsetsPadding(contentWindowInsets())
//                .graphicsLayer {
//                    val progress = predictiveBackProgress.value
//                    val predictiveBackScaleX = calculatePredictiveBackScaleX(progress)
//                    val predictiveBackScaleY = calculatePredictiveBackScaleY(progress)
//
//                    // Preserve the original aspect ratio and alignment of the child content.
//                    scaleY =
//                        if (predictiveBackScaleY != 0f) predictiveBackScaleX / predictiveBackScaleY
//                        else 1f
//                    transformOrigin = PredictiveBackChildTransformOrigin
//                }
//                // Scale the content down in case the sheet offset overflows below the min anchor.
//                // The wrapping Surface is scaled up, so this is done to maintain the content's
//                // aspect ratio.
//                .verticalScaleDown(sheetState)
//        ) {
//            if (dragHandle != null) {
//                val collapseActionLabel = getString(Strings.BottomSheetPartialExpandDescription)
//                val dismissActionLabel = getString(Strings.BottomSheetDismissDescription)
//                val expandActionLabel = getString(Strings.BottomSheetExpandDescription)
//                DragHandleWithTooltip {
//                    Box(
//                        modifier =
//                            Modifier.clickable {
//                                    when (sheetState.currentValue) {
//                                        Expanded -> animateToDismiss()
//                                        PartiallyExpanded -> scope.launch { sheetState.expand() }
//                                        else -> scope.launch { sheetState.show() }
//                                    }
//                                }
//                                .semantics(mergeDescendants = true) {
//                                    // Provides semantics to interact with the bottomsheet based on
//                                    // its current value.
//                                    if (sheetGesturesEnabled) {
//                                        with(sheetState) {
//                                            dismiss(dismissActionLabel) {
//                                                animateToDismiss()
//                                                true
//                                            }
//                                            if (currentValue == PartiallyExpanded) {
//                                                expand(expandActionLabel) {
//                                                    if (
//                                                        anchoredDraggableState.confirmValueChange(
//                                                            Expanded
//                                                        )
//                                                    ) {
//                                                        scope.launch { sheetState.expand() }
//                                                    }
//                                                    true
//                                                }
//                                            } else if (hasPartiallyExpandedState) {
//                                                collapse(collapseActionLabel) {
//                                                    if (
//                                                        anchoredDraggableState.confirmValueChange(
//                                                            PartiallyExpanded
//                                                        )
//                                                    ) {
//                                                        scope.launch { partialExpand() }
//                                                    }
//                                                    true
//                                                }
//                                            }
//                                        }
//                                    }
//                                }
//                    ) {
//                        dragHandle()
//                    }
//                }
//            }
//            content()
//        }
//    }
//}
//
//private fun GraphicsLayerScope.calculatePredictiveBackScaleX(progress: Float): Float {
//    val width = size.width
//    return if (width.isNaN() || width == 0f) {
//        1f
//    } else {
//        1f - lerp(0f, min(PredictiveBackMaxScaleXDistance.toPx(), width), progress) / width
//    }
//}
//
//private fun GraphicsLayerScope.calculatePredictiveBackScaleY(progress: Float): Float {
//    val height = size.height
//    return if (height.isNaN() || height == 0f) {
//        1f
//    } else {
//        1f - lerp(0f, min(PredictiveBackMaxScaleYDistance.toPx(), height), progress) / height
//    }
//}
//
///**
// * Properties used to customize the behavior of a [ModalBottomSheet].
// *
// * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing the
// *   back button. If true, pressing the back button will call onDismissRequest.
// * @param shouldDismissOnClickOutside Whether the modal bottom sheet can be dismissed by clicking on
// *   the scrim.
// */
//@Immutable
//@ExperimentalMaterial3Api
//expect class ModalBottomSheetProperties(
//    shouldDismissOnBackPress: Boolean = true,
//    shouldDismissOnClickOutside: Boolean = true,
//) {
//    val shouldDismissOnBackPress: Boolean
//    val shouldDismissOnClickOutside: Boolean
//
//    @Deprecated(
//        level = DeprecationLevel.HIDDEN,
//        message = "Replaced with additional shouldDismissOnClickOutside param constructor.",
//    )
//    constructor(shouldDismissOnBackPress: Boolean)
//}
//
///** Default values for [ModalBottomSheet] */
//@Immutable
//@ExperimentalMaterial3Api
//expect object ModalBottomSheetDefaults {
//
//    /** Properties used to customize the behavior of a [ModalBottomSheet]. */
//    val properties: ModalBottomSheetProperties
//}
//
///**
// * Create and [remember] a [SheetState] for [ModalBottomSheet].
// *
// * @param skipPartiallyExpanded Whether the partially expanded state, if the sheet is tall enough,
// *   should be skipped. If true, the sheet will always expand to the [Expanded] state and move to
// *   the [Hidden] state when hiding the sheet, either programmatically or by user interaction.
// * @param confirmValueChange Optional callback invoked to confirm or veto a pending state change.
// */
//@Composable
//@ExperimentalMaterial3Api
//fun rememberModalBottomSheetState(
//    skipPartiallyExpanded: Boolean = false,
//    confirmValueChange: (SheetValue) -> Boolean = { true },
//) =
//    rememberSheetState(
//        skipPartiallyExpanded = skipPartiallyExpanded,
//        confirmValueChange = confirmValueChange,
//        initialValue = Hidden,
//    )
//
//@Composable
//private fun Scrim(
//    color: Color,
//    onDismissRequest: () -> Unit,
//    visible: Boolean,
//    dismissEnabled: Boolean,
//) {
//    // TODO Load the motionScheme tokens from the component tokens file
//    if (color.isSpecified) {
//        val alpha by
//            animateFloatAsState(
//                targetValue = if (visible) 1f else 0f,
//                animationSpec = MotionSchemeKeyTokens.DefaultEffects.value(),
//            )
//        val closeSheet = getString(Strings.CloseSheet)
//        val dismissSheet =
//            if (dismissEnabled) {
//                Modifier.pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
//                    .semantics(mergeDescendants = true) {
//                        traversalIndex = 1f
//                        contentDescription = closeSheet
//                        onClick {
//                            onDismissRequest()
//                            true
//                        }
//                    }
//            } else {
//                Modifier
//            }
//        Canvas(Modifier.fillMaxSize().then(dismissSheet)) {
//            drawRect(color = color, alpha = alpha.coerceIn(0f, 1f))
//        }
//    }
//}
//
//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//internal expect fun ModalBottomSheetDialog(
//    onDismissRequest: () -> Unit,
//    contentColor: Color,
//    properties: ModalBottomSheetProperties,
//    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
//    content: @Composable () -> Unit,
//)
//
//private val PredictiveBackMaxScaleXDistance = 48.dp
//private val PredictiveBackMaxScaleYDistance = 24.dp
//private val PredictiveBackChildTransformOrigin = TransformOrigin(0.5f, 0f)

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PaintingStyle.Companion.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.concept.menu.candidate.TextStyle

/**
 * [Material Design modal bottom sheet](https://m3.material.io/components/bottom-sheets/overview)
 *
 * Modal bottom sheets are used as an alternative to inline menus or simple dialogs on mobile,
 * especially when offering a long list of action items, or when items require longer descriptions
 * and icons. Like dialogs, modal bottom sheets appear in front of app content, disabling all other
 * app functionality when they appear, and remaining on screen until confirmed, dismissed, or a
 * required action has been taken.
 *
 * ![Bottom sheet
 * image](https://developer.android.com/images/reference/androidx/compose/material3/bottom_sheet.png)
 *
 * A simple example of a modal bottom sheet looks like this:
 *
 * @sample androidx.compose.material3.samples.ModalBottomSheetSample
 * @param onDismissRequest Executes when the user clicks outside of the bottom sheet, after sheet
 *   animates to [Hidden].
 * @param modifier Optional [Modifier] for the bottom sheet.
 * @param sheetState The state of the bottom sheet.
 * @param sheetMaxWidth [Dp] that defines what the maximum width the sheet will take. Pass in
 *   [Dp.Unspecified] for a sheet that spans the entire screen width.
 * @param sheetGesturesEnabled Whether the bottom sheet can be interacted with by gestures.
 * @param shape The shape of the bottom sheet.
 * @param containerColor The color used for the background of this bottom sheet
 * @param contentColor The preferred color for content inside this bottom sheet. Defaults to either
 *   the matching content color for [containerColor], or to the current [LocalContentColor] if
 *   [containerColor] is not a color from the theme.
 * @param tonalElevation when [containerColor] is [ColorScheme.surface], a translucent primary color
 *   overlay is applied on top of the container. A higher tonal elevation value will result in a
 *   darker color in light theme and lighter color in dark theme. See also: [Surface].
 * @param scrimColor Color of the scrim that obscures content when the bottom sheet is open.
 * @param dragHandle Optional visual marker to swipe the bottom sheet.
 * @param contentWindowInsets callback which provides window insets to be passed to the bottom sheet
 *   content via [Modifier.windowInsetsPadding]. [ModalBottomSheet] will pre-emptively consume top
 *   insets based on it's current offset. This keeps content outside of the expected window insets
 *   at any position.
 * @param properties [ModalBottomSheetProperties] for further customization of this modal bottom
 *   sheet's window behavior.
 * @param content The content to be displayed inside the bottom sheet.
 */
@Composable
@ExperimentalMaterial3Api
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // TODO Load the motionScheme tokens from the component tokens file
    val anchoredDraggableMotion = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 800.0f,
    )
    val showMotion = anchoredDraggableMotion
    val hideMotion =  spring<Float>(
        dampingRatio = 1.0f,
        stiffness = 3800.0f,
    )

    SideEffect {
        sheetState.showMotionSpec = showMotion
        sheetState.hideMotionSpec = hideMotion
        sheetState.anchoredDraggableMotionSpec = anchoredDraggableMotion
    }
    val scope = rememberCoroutineScope()
    val animateToDismiss: () -> Unit = {
        if (sheetState.confirmValueChange(SheetValue.Hidden)) {
            scope
                .launch { sheetState.hide() }
                .invokeOnCompletion {
                    if (!sheetState.isVisible) {
                        onDismissRequest()
                    }
                }
        }
    }

    val predictiveBackProgress = remember { Animatable(initialValue = 0f) }

    ModalBottomSheetDialog(
        properties = properties,
        contentColor = contentColor,
        onDismissRequest = {
            if (sheetState.currentValue == SheetValue.Expanded && sheetState.hasPartiallyExpandedState) {
                // Smoothly animate away predictive back transformations since we are not fully
                // dismissing. We don't need to do this in the else below because we want to
                // preserve the predictive back transformations (scale) during the hide animation.
                scope.launch { predictiveBackProgress.animateTo(0f) }
                scope.launch { sheetState.partialExpand() }
            } else { // Is expanded without collapsed state or is collapsed.
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
            }
        },
        predictiveBackProgress = predictiveBackProgress,
    ) {
        Box(modifier = Modifier.fillMaxSize().imePadding().semantics { isTraversalGroup = true }) {
            Scrim(
                color = scrimColor,
                onDismissRequest = animateToDismiss,
                visible = sheetState.targetValue != SheetValue.Hidden,
                dismissEnabled = properties.shouldDismissOnClickOutside,
            )
            ModalBottomSheetContent(
                predictiveBackProgress,
                scope,
                onDismissRequest,
                animateToDismiss,
                modifier,
                sheetState,
                sheetMaxWidth,
                sheetGesturesEnabled,
                shape,
                containerColor,
                contentColor,
                tonalElevation,
                dragHandle,
                contentWindowInsets,
                content,
            )
        }
    }
    if (sheetState.hasExpandedState) {
        LaunchedEffect(sheetState) { sheetState.show() }
    }
}

@Composable
@ExperimentalMaterial3Api
internal fun BoxScope.ModalBottomSheetContent(
    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    onDismissRequest: () -> Unit,
    animateToDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
//    val bottomSheetPaneTitle = getString(string = Strings.BottomSheetPaneTitle)
    val anchoredDraggableFlingBehavior =
        AnchoredDraggableDefaults.flingBehavior(
            state = sheetState.anchoredDraggableState,
            positionalThreshold = { _ -> sheetState.positionalThreshold.invoke() },
            animationSpec = BottomSheetAnimationSpec,
        )
    val modalBottomSheetFlingBehavior =
        remember(anchoredDraggableFlingBehavior) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    var remainingVelocity = 0f
                    try {
                        remainingVelocity =
                            with(anchoredDraggableFlingBehavior) { performFling(initialVelocity) }
                    } finally {
                        if (!sheetState.isVisible) onDismissRequest()
                    }
                    return remainingVelocity
                }
            }
        }
    /*------ custom code ----- */



    /* ------- end ------ */
    Surface(
        modifier =
            modifier
                .align(Alignment.TopCenter)
                .widthIn(max = sheetMaxWidth)
                .fillMaxWidth()
                .then(
                    if (sheetGesturesEnabled)
                        Modifier.nestedScroll(
                            remember(sheetState) {
                                ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection(
                                    sheetState = sheetState,
                                    orientation = Orientation.Vertical,
                                    flingBehavior = modalBottomSheetFlingBehavior,
                                )
                            }
                        )
                    else Modifier
                )
                .draggableAnchors(sheetState.anchoredDraggableState, Orientation.Vertical) {
                        sheetSize,
                        constraints ->
                    val fullHeight = constraints.maxHeight.toFloat()
                    val newAnchors = DraggableAnchors {
                        SheetValue.Hidden at fullHeight
                        if (
                            sheetSize.height > (fullHeight / 2) && !sheetState.skipPartiallyExpanded
                        ) {
                            SheetValue.PartiallyExpanded at fullHeight / 2f
                        }
                        if (sheetSize.height != 0) {
                            SheetValue.Expanded at max(0f, fullHeight - sheetSize.height)
                        }
                    }
                    val newTarget =
                        when (sheetState.anchoredDraggableState.targetValue) {
                            SheetValue.Hidden -> SheetValue.Hidden
                            SheetValue.PartiallyExpanded -> {
                                val hasPartiallyExpandedState =
                                    newAnchors.hasPositionFor(SheetValue.PartiallyExpanded)
                                val newTarget =
                                    if (hasPartiallyExpandedState) SheetValue.PartiallyExpanded
                                    else if (newAnchors.hasPositionFor(SheetValue.Expanded)) SheetValue.Expanded
                                    else SheetValue.Hidden
                                newTarget
                            }

                            SheetValue.Expanded -> {
                                if (newAnchors.hasPositionFor(SheetValue.Expanded)) SheetValue.Expanded else SheetValue.Hidden
                            }
                        }
                    return@draggableAnchors newAnchors to newTarget
                }
                .anchoredDraggable(
                    state = sheetState.anchoredDraggableState,
                    orientation = Orientation.Vertical,
                    enabled = sheetGesturesEnabled && sheetState.currentValue != SheetValue.Hidden,
                    flingBehavior = modalBottomSheetFlingBehavior,
                )
                .semantics {
                    paneTitle = "BottomSheetTitle" //TODO bottomSheetPaneTitle
                    traversalIndex = 0f
                }
                .consumeWindowInsets(WindowInsets(top = sheetState.offset.toInt().coerceAtLeast(0)))
                .graphicsLayer {
                    val sheetOffset = sheetState.anchoredDraggableState.offset
                    val sheetHeight = size.height
                    if (!sheetOffset.isNaN() && !sheetHeight.isNaN() && sheetHeight != 0f) {
                        val progress = predictiveBackProgress.value
                        scaleX = calculatePredictiveBackScaleX(progress)
                        scaleY = calculatePredictiveBackScaleY(progress)
                        transformOrigin =
                            TransformOrigin(0.5f, (sheetOffset + sheetHeight) / sheetHeight)
                    }
                }
                // Scale up the Surface vertically in case the sheet's offset overflows below the
                // min anchor. This is done to avoid showing a gap when the sheet opens and bounces
                // when it's applied with a bouncy motion. Note that the content inside the Surface
                // is scaled back down to maintain its aspect ratio (see below).
                .verticalScaleUp(sheetState)
                .aiGlowBorder(shape = shape)
                .aiBackground(shape = shape),
        shape = shape,
        color = Color.Transparent, //containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(contentWindowInsets())
                .graphicsLayer {
                    val progress = predictiveBackProgress.value
                    val predictiveBackScaleX = calculatePredictiveBackScaleX(progress)
                    val predictiveBackScaleY = calculatePredictiveBackScaleY(progress)

                    // Preserve the original aspect ratio and alignment of the child content.
                    scaleY =
                        if (predictiveBackScaleY != 0f) predictiveBackScaleX / predictiveBackScaleY
                        else 1f
                    transformOrigin = PredictiveBackChildTransformOrigin
                }
                // Scale the content down in case the sheet offset overflows below the min anchor.
                // The wrapping Surface is scaled up, so this is done to maintain the content's
                // aspect ratio.
                .verticalScaleDown(sheetState)
        ) {
            if (dragHandle != null) {
//                val collapseActionLabel = getString(Strings.BottomSheetPartialExpandDescription)
//                val dismissActionLabel = getString(Strings.BottomSheetDismissDescription)
//                val expandActionLabel = getString(Strings.BottomSheetExpandDescription)
                DragHandleWithTooltip(
                    modifier =
                        Modifier.clickable {
                            when (sheetState.currentValue) {
                                SheetValue.Expanded -> animateToDismiss()
                                SheetValue.PartiallyExpanded -> scope.launch { sheetState.expand() }
                                else -> scope.launch { sheetState.show() }
                            }
                        }
                            .semantics(mergeDescendants = true) {
                                // Provides semantics to interact with the bottomsheet based on
                                // its current value.
                                if (sheetGesturesEnabled) {
                                    with(sheetState) {
                                        dismiss("dismissActionLabel") {
                                            animateToDismiss()
                                            true
                                        }
                                        if (currentValue == SheetValue.PartiallyExpanded) {
                                            expand("expandActionLabel") {
                                                if (confirmValueChange(SheetValue.Expanded)) {
                                                    scope.launch { sheetState.expand() }
                                                }
                                                true
                                            }
                                        } else if (hasPartiallyExpandedState) {
                                            collapse("collapseActionLabel") {
                                                if (confirmValueChange(SheetValue.PartiallyExpanded)) {
                                                    scope.launch { partialExpand() }
                                                }
                                                true
                                            }
                                        }
                                    }
                                }
                            },
                    content = dragHandle,
                )
            }
            content()
        }
    }
}

private fun GraphicsLayerScope.calculatePredictiveBackScaleX(progress: Float): Float {
    val width = size.width
    return if (width.isNaN() || width == 0f) {
        1f
    } else {
        1f - lerp(0f, min(PredictiveBackMaxScaleXDistance.toPx(), width), progress) / width
    }
}

private fun GraphicsLayerScope.calculatePredictiveBackScaleY(progress: Float): Float {
    val height = size.height
    return if (height.isNaN() || height == 0f) {
        1f
    } else {
        1f - lerp(0f, min(PredictiveBackMaxScaleYDistance.toPx(), height), progress) / height
    }
}

/**
 * Properties used to customize the behavior of a [ModalBottomSheet].
 *
 * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing the
 *   back button. If true, pressing the back button will call onDismissRequest.
 * @param shouldDismissOnClickOutside Whether the modal bottom sheet can be dismissed by clicking on
 *   the scrim.
 */
//@Immutable
//@ExperimentalMaterial3Api
//data class ModalBottomSheetProperties(
//    val shouldDismissOnBackPress: Boolean = true,
//    val shouldDismissOnClickOutside: Boolean = true,
//)
//
///** Default values for [ModalBottomSheet] */
//@Immutable
//@ExperimentalMaterial3Api
//data object ModalBottomSheetDefaults {
//
//    /** Properties used to customize the behavior of a [ModalBottomSheet]. */
//    val properties: ModalBottomSheetProperties
//}

/**
 * Create and [remember] a [SheetState] for [ModalBottomSheet].
 *
 * @param skipPartiallyExpanded Whether the partially expanded state, if the sheet is tall enough,
 *   should be skipped. If true, the sheet will always expand to the [Expanded] state and move to
 *   the [Hidden] state when hiding the sheet, either programmatically or by user interaction.
 * @param confirmValueChange Optional callback invoked to confirm or veto a pending state change.
 */
@Composable
@ExperimentalMaterial3Api
fun rememberModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (SheetValue) -> Boolean = { true },
) =
    rememberSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        confirmValueChange = confirmValueChange,
        initialValue = SheetValue.Hidden,
    )

@Composable
private fun Scrim(
    color: Color,
    onDismissRequest: () -> Unit,
    visible: Boolean,
    dismissEnabled: Boolean,
) {
    // TODO Load the motionScheme tokens from the component tokens file
    if (color.isSpecified) {
        val alpha by
        animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = 1600.0f,
            )
        )
//        val closeSheet = getString(Strings.CloseSheet)
        val dismissSheet =
            if (dismissEnabled) {
                Modifier.pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                    .semantics(mergeDescendants = true) {
                        traversalIndex = 1f
                        contentDescription = "Close" // TODO: FIXME
                        onClick {
                            onDismissRequest()
                            true
                        }
                    }
            } else {
                Modifier
            }
        Canvas(Modifier.fillMaxSize().then(dismissSheet)) {
            drawRect(color = color, alpha = alpha.coerceIn(0f, 1f))
        }
    }
}

//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//internal expect fun ModalBottomSheetDialog(
//    onDismissRequest: () -> Unit,
//    contentColor: Color,
//    properties: ModalBottomSheetProperties,
//    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
//    content: @Composable () -> Unit,
//)

private val PredictiveBackMaxScaleXDistance = 48.dp
private val PredictiveBackMaxScaleYDistance = 24.dp
private val PredictiveBackChildTransformOrigin = TransformOrigin(0.5f, 0f)




// Logic forked from androidx.compose.ui.window.DialogProperties. Removed dismissOnClickOutside
// and usePlatformDefaultWidth as they are not relevant for fullscreen experience.
/**
 * Properties used to customize the behavior of a [ModalBottomSheet].
 *
 * @param securePolicy Policy for setting [WindowManager.LayoutParams.FLAG_SECURE] on the bottom
 *   sheet's window.
 * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing the
 *   back button. If true, pressing the back button will call onDismissRequest.
 */
@Immutable
@ExperimentalMaterial3Api
class ModalBottomSheetProperties {
    val securePolicy: SecureFlagPolicy
    val shouldDismissOnBackPress: Boolean
    @get:JvmName("shouldDismissOnClickOutside") val shouldDismissOnClickOutside: Boolean
    internal val isAppearanceLightStatusBars: Boolean?
    internal val isAppearanceLightNavigationBars: Boolean?

    /**
     * Properties used to customize the behavior of a [ModalBottomSheet].
     *
     * This constructor provides default behavior for [ModalBottomSheet]. See other constructors for
     * customization options.
     */
    constructor() {
        this.securePolicy = SecureFlagPolicy.Inherit
        this.shouldDismissOnBackPress = true
        this.shouldDismissOnClickOutside = true
        this.isAppearanceLightStatusBars = null
        this.isAppearanceLightNavigationBars = null
    }

    constructor(shouldDismissOnBackPress: Boolean, shouldDismissOnClickOutside: Boolean) {
        this.securePolicy = SecureFlagPolicy.Inherit
        this.shouldDismissOnBackPress = shouldDismissOnBackPress
        this.shouldDismissOnClickOutside = shouldDismissOnClickOutside
        this.isAppearanceLightNavigationBars = null
        this.isAppearanceLightStatusBars = null
    }

    /**
     * Properties used to customize the behavior of a [ModalBottomSheet].
     *
     * @param securePolicy Policy for setting [WindowManager.LayoutParams.FLAG_SECURE] on the bottom
     *   sheet's window.
     * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing
     *   the back button. If true, pressing the back button will call onDismissRequest.
     * @param shouldDismissOnClickOutside Whether the modal bottom sheet can be dismissed by
     *   clicking on the scrim.
     */
    constructor(
        securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
        shouldDismissOnBackPress: Boolean = true,
        shouldDismissOnClickOutside: Boolean = true,
    ) {
        this.securePolicy = securePolicy
        this.shouldDismissOnBackPress = shouldDismissOnBackPress
        this.shouldDismissOnClickOutside = shouldDismissOnClickOutside
        this.isAppearanceLightNavigationBars = null
        this.isAppearanceLightStatusBars = null
    }

    /**
     * Properties used to customize the behavior of a [ModalBottomSheet].
     *
     * Use this constructor to customize the behavior of status and navigation bars on the
     * [ModalBottomSheet] window.
     *
     * @param isAppearanceLightStatusBars If true, changes the foreground color of the status bars
     *   to light so that the items on the bar can be read clearly. If false, reverts to the default
     *   appearance.
     * @param isAppearanceLightNavigationBars If true, changes the foreground color of the
     *   navigation bars to light so that the items on the bar can be read clearly. If false,
     *   reverts to the default appearance.
     * @param securePolicy Policy for setting [WindowManager.LayoutParams.FLAG_SECURE] on the bottom
     *   sheet's window.
     * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing
     *   the back button. If true, pressing the back button will call onDismissRequest.
     * @param shouldDismissOnClickOutside Whether the modal bottom sheet can be dismissed by
     *   clicking on the scrim.
     */
    constructor(
        isAppearanceLightStatusBars: Boolean,
        isAppearanceLightNavigationBars: Boolean,
        securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
        shouldDismissOnBackPress: Boolean = true,
        shouldDismissOnClickOutside: Boolean = true,
    ) {
        this.shouldDismissOnBackPress = shouldDismissOnBackPress
        this.shouldDismissOnClickOutside = shouldDismissOnClickOutside
        this.securePolicy = securePolicy
        this.isAppearanceLightStatusBars = isAppearanceLightStatusBars
        this.isAppearanceLightNavigationBars = isAppearanceLightNavigationBars
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModalBottomSheetProperties) return false
        if (securePolicy != other.securePolicy) return false
        if (isAppearanceLightStatusBars != other.isAppearanceLightStatusBars) return false
        if (isAppearanceLightNavigationBars != other.isAppearanceLightNavigationBars) return false
        if (shouldDismissOnClickOutside != other.shouldDismissOnClickOutside) return false
        if (shouldDismissOnBackPress != other.shouldDismissOnBackPress) return false
        return true
    }

    override fun hashCode(): Int {
        var result = securePolicy.hashCode()
        result = 31 * result + shouldDismissOnBackPress.hashCode()
        result = 31 * result + (isAppearanceLightStatusBars?.hashCode() ?: 0)
        result = 31 * result + (isAppearanceLightNavigationBars?.hashCode() ?: 0)
        result = 31 * result + shouldDismissOnClickOutside.hashCode()
        return result
    }
}

/** Default values for [ModalBottomSheet] */
@Immutable
@ExperimentalMaterial3Api
object ModalBottomSheetDefaults {

    /** Properties used to customize the behavior of a [ModalBottomSheet]. */
    val properties = ModalBottomSheetProperties()

    /**
     * Properties used to customize the behavior of a [ModalBottomSheet].
     *
     * @param securePolicy Policy for setting [WindowManager.LayoutParams.FLAG_SECURE] on the bottom
     *   sheet's window.
     * @param isFocusable Whether the modal bottom sheet is focusable. When true, the modal bottom
     *   sheet will receive IME events and key presses, such as when the back button is pressed.
     * @param shouldDismissOnBackPress Whether the modal bottom sheet can be dismissed by pressing
     *   the back button. If true, pressing the back button will call onDismissRequest. Note that
     *   [isFocusable] must be set to true in order to receive key events such as the back button -
     *   if the modal bottom sheet is not focusable then this property does nothing.
     */
    @Deprecated(
        level = DeprecationLevel.WARNING,
        message = "'isFocusable' param is no longer used. Use value without this parameter.",
        replaceWith = ReplaceWith("properties"),
    )
    @Suppress("UNUSED_PARAMETER")
    fun properties(
        securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
        isFocusable: Boolean = true,
        shouldDismissOnBackPress: Boolean = true,
    ) =
        ModalBottomSheetProperties(
            securePolicy = securePolicy,
            shouldDismissOnBackPress = shouldDismissOnBackPress,
        )
}

// Fork of androidx.compose.ui.window.AndroidDialog_androidKt.Dialog
// Added predictiveBackProgress param to pass into BottomSheetDialogWrapper.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalBottomSheetDialog(
    onDismissRequest: () -> Unit,
    contentColor: Color,
    properties: ModalBottomSheetProperties,
    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val composition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val dialogId = rememberSaveable { UUID.randomUUID() }
    val scope = rememberCoroutineScope()
    val dialog =
        remember(view, density) {
            ModalBottomSheetDialogWrapper(
                onDismissRequest,
                properties,
                contentColor,
                view,
                layoutDirection,
                density,
                dialogId,
                predictiveBackProgress,
                scope,
            )
                .apply {
                    setContent(composition) {
                        Box(Modifier.semantics { dialog() }) { currentContent() }
                    }
                }
        }

    DisposableEffect(dialog) {
        dialog.show()

        onDispose {
            dialog.dismiss()
            dialog.disposeComposition()
        }
    }

    SideEffect {
        dialog.updateParameters(
            onDismissRequest = onDismissRequest,
            properties = properties,
            contentColor = contentColor,
            layoutDirection = layoutDirection,
        )
    }
}

// Fork of androidx.compose.ui.window.DialogLayout
// Additional parameters required for current predictive back implementation.
@Suppress("ViewConstructor")
private class ModalBottomSheetDialogLayout(context: Context, override val window: Window) :
    AbstractComposeView(context), DialogWindowProvider {

    private var content: @Composable () -> Unit by mutableStateOf({})

    override var shouldCreateCompositionOnAttachedToWindow: Boolean = false
        private set

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        setParentCompositionContext(parent)
        this.content = content
        shouldCreateCompositionOnAttachedToWindow = true
        createComposition()
    }

    // Display width and height logic removed, size will always span fillMaxSize().

    @Composable
    override fun Content() {
        content()
    }
}

// Fork of androidx.compose.ui.window.DialogWrapper.
// predictiveBackProgress and scope params added for predictive back implementation.
// EdgeToEdgeFloatingDialogWindowTheme provided to allow theme to extend into status bar.
@ExperimentalMaterial3Api
private class ModalBottomSheetDialogWrapper(
    private var onDismissRequest: () -> Unit,
    private var properties: ModalBottomSheetProperties,
    private var contentColor: Color,
    private val composeView: View,
    layoutDirection: LayoutDirection,
    density: Density,
    dialogId: UUID,
    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
) :
    ComponentDialog(
        ContextThemeWrapper(
            composeView.context,
            androidx.compose.material3.R.style.EdgeToEdgeFloatingDialogWindowTheme,
        )
    ),
    ViewRootForInspector {

    private val dialogLayout: ModalBottomSheetDialogLayout

    // On systems older than Android S, there is a bug in the surface insets matrix math used by
    // elevation, so high values of maxSupportedElevation break accessibility services: b/232788477.
    private val maxSupportedElevation = 8.dp

    override val subCompositionView: AbstractComposeView
        get() = dialogLayout

    init {
        val window = window ?: error("Dialog has no window")
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        dialogLayout =
            ModalBottomSheetDialogLayout(context, window).apply {
                // Set unique id for AbstractComposeView. This allows state restoration for the
                // state defined inside the Dialog via rememberSaveable()
                setTag(R.id.compose_view_saveable_id_tag, "Dialog:$dialogId")
                // Enable children to draw their shadow by not clipping them
                clipChildren = false
                // Allocate space for elevation
                with(density) { elevation = maxSupportedElevation.toPx() }
                // Simple outline to force window manager to allocate space for shadow.
                // Note that the outline affects clickable area for the dismiss listener. In
                // case of shapes like circle the area for dismiss might be to small
                // (rectangular outline consuming clicks outside of the circle).
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, result: Outline) {
                            result.setRect(0, 0, view.width, view.height)
                            // We set alpha to 0 to hide the view's shadow and let the
                            // composable to draw its own shadow. This still enables us to get
                            // the extra space needed in the surface.
                            result.alpha = 0f
                        }
                    }
            }
        // Clipping logic removed because we are spanning edge to edge.

        setContentView(dialogLayout)
        dialogLayout.setViewTreeLifecycleOwner(composeView.findViewTreeLifecycleOwner())
        dialogLayout.setViewTreeViewModelStoreOwner(composeView.findViewTreeViewModelStoreOwner())
        dialogLayout.setViewTreeSavedStateRegistryOwner(
            composeView.findViewTreeSavedStateRegistryOwner()
        )

        // Initial setup
        updateParameters(onDismissRequest, properties, contentColor, layoutDirection)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Theme system bars based on content color. Light system bars provide dark icons
            // and vice-versa. This maintains visible system bars for the bottom sheet window.
            isAppearanceLightStatusBars =
                properties.isAppearanceLightStatusBars ?: contentColor.isDark()
            isAppearanceLightNavigationBars =
                properties.isAppearanceLightNavigationBars ?: contentColor.isDark()
        }
        // Due to how the onDismissRequest callback works
        // (it enforces a just-in-time decision on whether to update the state to hide the dialog)
        // we need to provide a custom onBackPressedCallback to provide predictive back animations
        // for this component while handling onDismissRequest.
        onBackPressedDispatcher.addCallback(
            owner = this,
            onBackPressedCallback =
                PredictiveBackOnBackPressedCallback(
                    isEnabled = properties.shouldDismissOnBackPress,
                    scope = scope,
                    predictiveBackProgress = predictiveBackProgress,
                    onDismissRequest = {
                        this.onDismissRequest()
                    }, // Ensure lambda captures current onDismissRequest
                ),
        )
    }

    private fun setLayoutDirection(layoutDirection: LayoutDirection) {
        dialogLayout.layoutDirection =
            when (layoutDirection) {
                LayoutDirection.Ltr -> android.util.LayoutDirection.LTR
                LayoutDirection.Rtl -> android.util.LayoutDirection.RTL
            }
    }

    fun setContent(parentComposition: CompositionContext, children: @Composable () -> Unit) {
        dialogLayout.setContent(parentComposition, children)
    }

    private fun setSecurePolicy(securePolicy: SecureFlagPolicy) {
        val secureFlagEnabled =
            securePolicy.shouldApplySecureFlag(composeView.isFlagSecureEnabled())
        window!!.setFlags(
            if (secureFlagEnabled) {
                WindowManager.LayoutParams.FLAG_SECURE
            } else {
                WindowManager.LayoutParams.FLAG_SECURE.inv()
            },
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    fun updateParameters(
        onDismissRequest: () -> Unit,
        properties: ModalBottomSheetProperties,
        contentColor: Color,
        layoutDirection: LayoutDirection,
    ) {
        this.onDismissRequest = onDismissRequest
        this.properties = properties
        this.contentColor = contentColor
        setSecurePolicy(properties.securePolicy)
        setLayoutDirection(layoutDirection)

        // Window flags to span parent window.
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window?.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        )
    }

    fun disposeComposition() {
        dialogLayout.disposeComposition()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val result = super.onTouchEvent(event)
        if (result) {
            onDismissRequest()
        }

        return result
    }

    override fun cancel() {
        // Prevents the dialog from dismissing itself
        return
    }

    private class PredictiveBackOnBackPressedCallback(
        isEnabled: Boolean,
        val scope: CoroutineScope,
        val predictiveBackProgress: Animatable<Float, AnimationVector1D>,
        var onDismissRequest: () -> Unit,
    ) : OnBackPressedCallback(isEnabled) {

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            scope.launch {
                predictiveBackProgress.snapTo(PredictiveBack.transform(backEvent.progress))
            }
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            scope.launch {
                // Use snapTo for immediate feedback during the gesture
                predictiveBackProgress.snapTo(PredictiveBack.transform(backEvent.progress))
            }
        }

        override fun handleOnBackPressed() {
            // Back gesture completed successfully, invoke dismiss
            onDismissRequest()
        }

        override fun handleOnBackCancelled() {
            // Back gesture cancelled, animate back to 0
            scope.launch { predictiveBackProgress.animateTo(0f) }
        }
    }
}

internal fun View.isFlagSecureEnabled(): Boolean {
    val windowParams = rootView.layoutParams as? WindowManager.LayoutParams
    if (windowParams != null) {
        return (windowParams.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }
    return false
}

/** Determines if a color should be considered light or dark. */
internal fun Color.isDark(): Boolean {
    return this != Color.Transparent && luminance() <= 0.5
}

internal fun SecureFlagPolicy.shouldApplySecureFlag(isSecureFlagSetOnParent: Boolean): Boolean {
    return when (this) {
        SecureFlagPolicy.SecureOff -> false
        SecureFlagPolicy.SecureOn -> true
        SecureFlagPolicy.Inherit -> isSecureFlagSetOnParent
    }
}

private val PredictiveBackEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

internal object PredictiveBack {
    internal fun transform(progress: Float) = PredictiveBackEasing.transform(progress)
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.verticalScaleUp(state: SheetState) = graphicsLayer {
    val offset = state.anchoredDraggableState.offset
    val anchor = state.anchoredDraggableState.anchors.minPosition()
    val overflow = if (offset < anchor) anchor - offset else 0f
    scaleY = if (overflow > 0f) (size.height + overflow) / size.height else 1f
    transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.verticalScaleDown(state: SheetState) = graphicsLayer {
    val offset = state.anchoredDraggableState.offset
    val anchor = state.anchoredDraggableState.anchors.minPosition()
    val overflow = if (offset < anchor) anchor - offset else 0f
    scaleY = if (overflow > 0f) 1 / ((size.height + overflow) / size.height) else 1f
    transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
}

/**
 * This Modifier allows configuring an [AnchoredDraggableState]'s anchors based on this layout
 * node's size and offsetting it. It considers lookahead and reports the appropriate size and
 * measurement for the appropriate phase.
 *
 * @param state The state the anchors should be attached to
 * @param orientation The orientation the component should be offset in
 * @param anchors Lambda to calculate the anchors based on this layout's size and the incoming
 *   constraints. These can be useful to avoid subcomposition.
 */
@Stable
internal fun <T> Modifier.draggableAnchors(
    state: AnchoredDraggableState<T>,
    orientation: Orientation,
    anchors: (size: IntSize, constraints: Constraints) -> Pair<DraggableAnchors<T>, T>,
) = this then DraggableAnchorsElement(state, anchors, orientation)

private class DraggableAnchorsElement<T>(
    private val state: AnchoredDraggableState<T>,
    private val anchors: (size: IntSize, constraints: Constraints) -> Pair<DraggableAnchors<T>, T>,
    private val orientation: Orientation,
) : ModifierNodeElement<DraggableAnchorsNode<T>>() {

    override fun create() = DraggableAnchorsNode(state, anchors, orientation)

    override fun update(node: DraggableAnchorsNode<T>) {
        node.state = state
        node.anchors = anchors
        node.orientation = orientation
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        if (other !is DraggableAnchorsElement<*>) return false

        if (state != other.state) return false
        if (anchors !== other.anchors) return false
        if (orientation != other.orientation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + anchors.hashCode()
        result = 31 * result + orientation.hashCode()
        return result
    }

    override fun InspectorInfo.inspectableProperties() {
        debugInspectorInfo {
            properties["state"] = state
            properties["anchors"] = anchors
            properties["orientation"] = orientation
        }
    }
}

private class DraggableAnchorsNode<T>(
    var state: AnchoredDraggableState<T>,
    var anchors: (size: IntSize, constraints: Constraints) -> Pair<DraggableAnchors<T>, T>,
    var orientation: Orientation,
) : Modifier.Node(), LayoutModifierNode {
    private var didLookahead: Boolean = false

    override fun onDetach() {
        didLookahead = false
    }

    private val isReverseDirection: Boolean
        get() =
            requireLayoutDirection() == LayoutDirection.Rtl && orientation == Orientation.Horizontal

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        // If we are in a lookahead pass, we only want to update the anchors here and not in
        // post-lookahead. If there is no lookahead happening (!isLookingAhead && !didLookahead),
        // update the anchors in the main pass.
        if (!isLookingAhead || !didLookahead) {
            val size = IntSize(placeable.width, placeable.height)
            val newAnchorResult = anchors(size, constraints)
            state.updateAnchors(newAnchorResult.first, newAnchorResult.second)
        }
        didLookahead = isLookingAhead || didLookahead
        return layout(placeable.width, placeable.height) {
            // In a lookahead pass, we use the position of the current target as this is where any
            // ongoing animations would move. If the component is in a settled state, lookahead
            // and post-lookahead will converge.
            val offset =
                if (isLookingAhead) {
                    state.anchors.positionOf(state.targetValue)
                } else state.requireOffset()
            val rtlModifier = if (isReverseDirection) -1f else 1f
            val xOffset = if (orientation == Orientation.Horizontal) offset * rtlModifier else 0f
            val yOffset = if (orientation == Orientation.Vertical) offset else 0f
            // Tagging as motion frame of reference placement, meaning the placement
            // contains scrolling. This allows the consumer of this placement offset to
            // differentiate this offset vs. offsets from structural changes. Generally
            // speaking, this signals a preference to directly apply changes rather than
            // animating, to avoid a chasing effect to scrolling.
            withMotionFrameOfReferencePlacement {
                placeable.place(xOffset.roundToInt(), yOffset.roundToInt())
            }
        }
    }
}

fun Modifier.aiBorder(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    strokeWidth: Dp = 6.dp
): Modifier = composed {
    if (!enabled) return@composed this

    val infiniteTransition = rememberInfiniteTransition(label = "AI Border")

    // Animates the progress of the gradient flow
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GradientOffset"
    )

    this.drawWithCache {
        // Define colors for the "AI" look
        val colors = listOf(
            Color(0x00698AF9),
            Color(0xFF7570D1),
            Color(0xFF698AF9),
            Color(0xFF7570D1),
            Color(0x007570D1),
        )

        // Create the outline based on the provided shape
        val outline = shape.createOutline(size, layoutDirection, this)
        val strokePx = strokeWidth.toPx()

        onDrawWithContent {
            drawContent()

            // We use a LinearGradient that moves based on the animated offset
            val gradientBrush = Brush.linearGradient(
                colors = colors,
                start = Offset(size.width * (offset - 1f), 0f),
                end = Offset(size.width * (offset + 1f), size.height),
                tileMode = TileMode.Clamp
            )

            // Draw the border using the calculated outline
            drawOutline(
                outline = outline,
                brush = gradientBrush,
                style = Stroke(width = strokePx)
            )
        }
    }
}

fun Modifier.aiGlowBorder(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    strokeWidth: Dp = 2.dp,
    glowRadius: Dp = 4.dp
): Modifier = composed {
    if (!enabled) return@composed this

    val infiniteTransition = rememberInfiniteTransition(label = "AI Glow")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GradientOffset"
    )

    this
        // 1. Add padding (inset) so content doesn't overlap the 6pt border
        .padding(strokeWidth / 2)
        .drawWithCache {
            val colors = listOf(
                Color(0x00698AF9),
                Color(0xFF7570D1),
                Color(0xFF698AF9),
                Color(0xFF7570D1),
                Color(0x007570D1),
            )

            val strokePx = strokeWidth.toPx()
            val glowPx = glowRadius.toPx()
            val newSize = Size(size.width, size.height + strokeWidth.toPx() + glowPx)
            val outline = shape.createOutline(newSize, layoutDirection, this)

            onDrawWithContent {
                // Animated Brush
                val gradientBrush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(size.width * (offset - 1f), 0f),
                    end = Offset(size.width * (offset + 1f), size.height),
                    tileMode = TileMode.Clamp
                )

                // 2. Draw the Outer Glow
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        style = PaintingStyle.Stroke
                        this.strokeWidth = strokePx
                    }

                    val frameworkPaint = paint.asFrameworkPaint()

                    // Apply blur to the framework paint
                    frameworkPaint.maskFilter = BlurMaskFilter(glowPx, BlurMaskFilter.Blur.NORMAL)
                    frameworkPaint.color = Color(0xFF9B72CB).toArgb() // Glow base color

                    canvas.drawOutline(outline, paint)
                }

                // 3. Draw the Content
                drawContent()

                // 4. Draw the actual 6pt Border on top
                drawOutline(
                    outline = outline,
                    brush = gradientBrush,
                    style = Stroke(width = strokePx)
                )
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.aiBackground(
    enabled: Boolean = true,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier = composed {
    if (!enabled) return@composed this

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

    val stripeWidthPx = with(LocalDensity.current) { 260.dp.toPx() }

    // To loop perfectly, the shift must equal the width of one full color cycle
    val shiftPx = phase * stripeWidthPx

    // Simplify the list: The last color must match the first for TileMode.Repeated
    // to look seamless, OR you ensure the gradient covers the width.
    val colors = listOf(
        Color(0x0d7570D1).compositeOver(containerColor),
        Color(0x1a698AF9).compositeOver(containerColor),
        Color(0x0d7570D1).compositeOver(containerColor),
    )

    val brush = Brush.linearGradient(
        colors = colors,
        // Start and End are anchored to the stripe width
        start = Offset(x = shiftPx, y = 0f),
        end = Offset(x = shiftPx + stripeWidthPx, y = 0f),
        tileMode = TileMode.Repeated
    )

    this.background(brush = brush, shape = shape)
}

@Composable
fun AiAnimatedText(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black // Used for the composite blend
) {

    val infinite = rememberInfiniteTransition(label = "text_wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val stripeWidthPx = with(LocalDensity.current) { 280.dp.toPx() }
    val shiftPx = phase * stripeWidthPx

    // Same color logic as your background
    val colors = listOf(
        Color(0xFF4285F4), // Bright Blue
        Color(0xFF9B72CB), // Purple
        Color(0xFFD96570), // Pink
        Color(0xFF4285F4), // Loop back
    )

    val brush = Brush.linearGradient(
        colors = colors,
        start = Offset(x = shiftPx, y = 0f),
        end = Offset(x = shiftPx + stripeWidthPx, y = 0f),
        tileMode = TileMode.Repeated
    )

    Text(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.copy(brush = brush)
    )
}
