package it.parrocchia.coro.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pulsante protetto contro tocchi accidentali:
 * si attiva solo mantenendo la pressione per [holdDurationMs] millisecondi.
 * Compatibile con lo scorrimento di LazyColumn (se l'utente trascina per scorrere,
 * la pressione viene annullata automaticamente senza attivare il comando).
 */
@Composable
fun HoldToConfirmSurface(
    holdDurationMs: Int,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color,
    progressFillColor: Color,
    border: BorderStroke? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    contentDescriptionText: String? = null,
    content: @Composable BoxScope.(holdProgress: Float, isHolding: Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }

    val latestOnConfirmed by rememberUpdatedState(onConfirmed)
    val latestHoldDuration by rememberUpdatedState(holdDurationMs.coerceIn(200, 1500))
    val latestEnabled by rememberUpdatedState(enabled)

    Box(
        modifier = modifier
            .clip(shape)
            .alpha(if (enabled) 1f else 0.42f)
            .background(containerColor)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .semantics {
                role = Role.Button
                if (contentDescriptionText != null) {
                    contentDescription = contentDescriptionText
                }
            }
            .pointerInput(latestEnabled) {
                if (!latestEnabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        var triggered = false
                        val animJob = scope.launch {
                            try {
                                progress.snapTo(0f)
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = latestHoldDuration,
                                        easing = LinearEasing
                                    )
                                )
                                triggered = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnConfirmed()
                            } catch (_: CancellationException) {
                                // Annullato prima del completamento
                            }
                        }

                        try {
                            tryAwaitRelease()
                        } finally {
                            isHolding = false
                            if (!triggered) {
                                animJob.cancel()
                            }
                            scope.launch {
                                progress.snapTo(0f)
                            }
                        }
                    }
                )
            }
    ) {
        if (progress.value > 0f) {
            // Barra di riempimento progressiva da sinistra a destra
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = progressFillColor.copy(alpha = 0.50f),
                    size = size.copy(width = size.width * progress.value)
                )
            }
        }

        content(progress.value, isHolding)
    }
}
