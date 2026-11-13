package com.voicerewriter

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerewriter.DictationStatus.Phase
import com.voicerewriter.ui.BrandLavender
import com.voicerewriter.ui.BrandPlum
import com.voicerewriter.ui.MarkLight
import kotlinx.coroutines.delay

/**
 * The widget's take, full screen over the lock screen. On the lock screen the bubble says
 * nothing, the display drops back to the always-on screen within seconds, and ending the take
 * meant waking the phone and finding the widget again. This shows over the keyguard, keeps the
 * display on while the take runs, ends it on a tap anywhere, and closes itself once the text
 * has gone out. It never shows the text: the phone is locked.
 *
 * It only watches ([DictationStatus]) and stops; [BubbleService] started the take before
 * launching it, so a keyguard that refuses to show this still records as before.
 */
class LockDictationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always the dark screen below, so light bar icons whatever the phone's theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        // Visible now: the one moment a take that could not get the microphone can.
        BubbleService.instance?.widgetTakeFromScreen()
    }

    override fun onStop() {
        super.onStop()
        // Screen off or covered: the take goes on in the service (auto-stop ends it); this
        // screen does not wait behind the lock screen to reappear later.
        if (!isChangingConfigurations) finish()
    }

    private fun buzz(ok: Boolean) {
        val effect = when {
            Build.VERSION.SDK_INT >= 30 -> if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        window.decorView.performHapticFeedback(effect)
    }

    @Composable
    private fun Screen() {
        val phase by DictationStatus.phase.collectAsState()
        val amp by DictationStatus.amplitude.collectAsState()
        // The recorder reports the wait about 18 times a second; drawn as-is the ring steps.
        // Glide between reports at the display's rate, and drop back at once when speech resumes.
        val silenceRaw by DictationStatus.silence.collectAsState()
        val silence by animateFloatAsState(
            silenceRaw,
            animationSpec = if (silenceRaw == 0f) snap() else tween(110, easing = LinearEasing),
            label = "silence",
        )
        // The take is started just before this screen; IDLE means "not yet" until it has been
        // seen running, and "ended with nothing" after.
        var seen by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(phase) { if (phase != Phase.IDLE) seen = true }

        LaunchedEffect(Unit) {
            delay(START_TIMEOUT_MS)
            if (!seen) { failed = true; buzz(false); delay(FAIL_HOLD_MS); finish() }
        }
        // Close a little after the result. A PC failure arrives after the phone-side fallback
        // (INSERTED/COPIED, then NO_PC), which restarts this wait so it is read too.
        LaunchedEffect(phase, seen) {
            if (!seen) return@LaunchedEffect
            when {
                DictationStatus.isResult(phase) -> {
                    buzz(phase == Phase.TO_PC || phase == Phase.PC_CLIP)
                    delay(if (phase == Phase.TO_PC || phase == Phase.PC_CLIP) RESULT_HOLD_MS else FAIL_HOLD_MS)
                    finish()
                }
                phase == Phase.IDLE -> { buzz(false); delay(RESULT_HOLD_MS); finish() }
            }
        }
        // Back gives the take up instead of sending it.
        BackHandler {
            if (phase == Phase.LISTENING) HeadlessDictation.abandon()
            finish()
        }

        val listening = phase == Phase.LISTENING
        // Whispers peak low; a square root keeps them visible without pinning loud speech.
        val level = if (listening) kotlin.math.sqrt((amp / 6000f).coerceIn(0f, 1f)) else 0f
        val pulse by animateFloatAsState(1f + 0.9f * level, label = "pulse")

        Box(
            Modifier.fillMaxSize().background(Color(0xFF1E1826)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) {
                if (listening) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    BubbleService.recordingStopper?.invoke()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    if (listening) Box(Modifier.size(120.dp).scale(pulse).background(BrandLavender.copy(alpha = 0.22f), CircleShape))
                    // The silence wait running out: a ring closing round the mark. Not drawn
                    // for the gaps between words (under a quarter of the wait).
                    if (listening && silence > 0.25f) {
                        Canvas(Modifier.size(150.dp)) {
                            drawArc(
                                color = MarkLight, startAngle = -90f, sweepAngle = 360f * (silence - 0.25f) / 0.75f,
                                useCenter = false, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                    }
                    Box(Modifier.size(120.dp).background(BrandPlum, CircleShape), contentAlignment = Alignment.Center) {
                        if (phase == Phase.WORKING) {
                            CircularProgressIndicator(Modifier.size(56.dp), color = MarkLight, strokeWidth = 4.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_mark), null, Modifier.size(72.dp), tint = MarkLight)
                        }
                    }
                }
                Text(title(phase, seen, failed), color = MarkLight, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (!listening) "" else if (silence > 0.25f) tr("Finishing · tap to finish now") else tr("Tap anywhere to finish"),
                    color = MarkLight.copy(alpha = 0.6f), fontSize = 16.sp,
                )
            }
        }
    }

    private fun title(phase: Phase, seen: Boolean, failed: Boolean): String = when {
        failed -> tr("Couldn't start dictation")
        phase == Phase.LISTENING -> tr("Listening")
        phase == Phase.WORKING -> tr("Transcribing")
        phase == Phase.TO_PC -> tr("Sent to PC")
        phase == Phase.PC_CLIP -> tr("On the PC clipboard")
        phase == Phase.NO_PC -> tr("PC not reached")
        phase == Phase.INSERTED -> tr("Typed on the phone")
        phase == Phase.COPIED -> tr("Copied on the phone")
        seen -> tr("Nothing was recognized.")
        else -> tr("Starting")
    }

    private companion object {
        const val START_TIMEOUT_MS = 2500L
        const val RESULT_HOLD_MS = 1200L
        const val FAIL_HOLD_MS = 2500L
    }
}
