package com.voicerewriter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.Mulish
import com.voicerewriter.ui.SunsetBrush

enum class AppTab { HOME, SETTINGS }

/** Where the bottom bar's buttons go, shared by every screen that shows it. */
object AppNav {
    fun talk(context: Context) {
        context.startActivity(Intent(context, RewriteActivity::class.java).putExtra(RewriteActivity.EXTRA_AUTO_RECORD, true))
    }

    /** Back to the Home already underneath (or a new one), closing the screen we're on. */
    fun home(activity: Activity) {
        activity.startActivity(
            Intent(activity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        activity.finish()
    }
}

/** Content slides toward the tab you picked, in the order the tabs sit on the bar. */
fun AnimatedContentTransitionScope<AppTab>.tabSlide(): ContentTransform {
    val dir = if (targetState.ordinal > initialState.ordinal) AnimatedContentTransitionScope.SlideDirection.Left
    else AnimatedContentTransitionScope.SlideDirection.Right
    return slideIntoContainer(dir, tween(250)) togetherWith slideOutOfContainer(dir, tween(250))
}

/**
 * Material-3 style bottom nav (Home · Talk · Settings). Tapping the tab you're on does nothing but
 * the haptic tick.
 */
@Composable
fun AppBottomBar(selected: AppTab, onHome: () -> Unit, onTalk: () -> Unit, onSettings: () -> Unit) {
    // Light tap feedback so the bar feels physical. VIRTUAL_KEY is the standard
    // "button press" haptic and respects the system's touch-feedback setting.
    val view = LocalView.current
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    // Plain Box (not Surface) so the raised Talk FAB can overflow above the bar without being clipped.
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp, 8.dp, 14.dp, 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val home = selected == AppTab.HOME
            NavItem(label = "Home", selected = home, onClick = { haptic(); if (!home) onHome() }) {
                Icon(painterResource(R.drawable.ic_aperture), null,
                    tint = if (home) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
            // Talk (raised FAB)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.offset(y = (-18).dp).size(58.dp).clip(CircleShape).background(SunsetBrush)
                        .clickable { haptic(); onTalk() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_aperture), null, tint = MarkCream,
                        modifier = Modifier.size(30.dp))
                }
                Text("Talk", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.offset(y = (-12).dp))
            }
            val settings = selected == AppTab.SETTINGS
            NavItem(label = "Settings", selected = settings, onClick = { haptic(); if (!settings) onSettings() }) {
                Icon(painterResource(R.drawable.ic_settings_lines), null,
                    tint = if (settings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(88.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(label, fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
