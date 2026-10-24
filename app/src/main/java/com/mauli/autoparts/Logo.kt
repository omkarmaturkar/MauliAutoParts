package com.mauli.autoparts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Corner points of the bold "M" (centre of the logo = 0,0). */
private val LetterM = listOf(
    -11f to 10f, -11f to -10f, -5.5f to -10f, 0f to -2.5f, 5.5f to -10f, 11f to -10f,
    11f to 10f, 5.5f to 10f, 5.5f to -0.5f, 0f to 7f, -5.5f to -0.5f, -5.5f to 10f
)

/** Original Mauli Auto Parts logo: a bold "M" inside an orange gear. Drawn in code, sharp at any size. */
@Composable
fun MauliLogo(modifier: Modifier = Modifier, spin: Float = 0f, letterAlpha: Float = 1f) {
    Canvas(modifier) {
        val u = size.minDimension / 72f
        val c = center
        fun pt(x: Float, y: Float) = Offset(c.x + x * u, c.y + y * u)
        rotate(spin, c) {
            for (k in 0 until 8) {
                rotate(k * 45f, c) {
                    drawRoundRect(
                        brush = OrangeBrush,
                        topLeft = pt(-5f, -36f),
                        size = Size(10f * u, 11f * u),
                        cornerRadius = CornerRadius(2f * u, 2f * u)
                    )
                }
            }
            drawCircle(brush = OrangeBrush, radius = 29f * u, center = c)
        }
        drawCircle(color = Color.White, radius = 21f * u, center = c)
        val m = Path()
        LetterM.forEachIndexed { i, (x, y) ->
            val o = pt(x, y)
            if (i == 0) m.moveTo(o.x, o.y) else m.lineTo(o.x, o.y)
        }
        m.close()
        drawPath(m, color = Navy.copy(alpha = letterAlpha.coerceIn(0f, 1f)))
    }
}

/** Animated opening screen: gear spins in, ring pulses, M appears, shop name slides up. */
@Composable
fun BrandSplash(shopName: String, tagline: String) {
    val pop = remember { Animatable(0.3f) }
    val spin = remember { Animatable(-220f) }
    val letter = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
        launch { spin.animateTo(0f, tween(1200, easing = FastOutSlowInEasing)) }
        launch {
            delay(250)
            ring.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        }
        launch {
            delay(350)
            letter.animateTo(1f, tween(450))
        }
        launch {
            delay(650)
            words.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(HeaderBrush)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = ring.value
                    drawCircle(Color.White.copy(alpha = 0.18f * (1f - r)), radius = size.minDimension * (0.28f + 0.22f * r), center = center)
                    drawCircle(Orange.copy(alpha = 0.14f * (1f - r)), radius = size.minDimension * (0.24f + 0.30f * r), center = center)
                }
                MauliLogo(
                    Modifier
                        .size(132.dp)
                        .graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                            alpha = (pop.value * 1.5f).coerceIn(0f, 1f)
                        },
                    spin = spin.value,
                    letterAlpha = letter.value
                )
            }
            Text(
                shopName,
                Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        alpha = words.value
                        translationY = (1f - words.value) * 60f
                    },
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
            )
            if (tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tagline,
                    Modifier
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            alpha = words.value * 0.8f
                            translationY = (1f - words.value) * 80f
                        },
                    color = Color.White, fontSize = 14.sp, letterSpacing = 0.5.sp, textAlign = TextAlign.Center
                )
            }
        }
        Text(
            "Billing  •  Stock  •  Udhaar",
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
                .graphicsLayer { alpha = words.value * 0.6f },
            color = Color.White, fontSize = 12.sp, letterSpacing = 1.sp
        )
    }
}
