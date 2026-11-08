package com.voicerewriter

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The speech-model menu opened by flicking the floating bubble upward. A small card beside the
 * bubble listing the downloaded models, the active one checked.
 *
 * Like the bubble it is a FLAG_NOT_FOCUSABLE overlay, so opening it never takes focus or the
 * keyboard from the app being typed into. A touch anywhere else closes it (ACTION_OUTSIDE), and
 * so does doing nothing for a few seconds.
 */
class ModelPicker(
    private val context: Context,
    private val wm: WindowManager,
    private val overlayType: Int,
) {
    data class Item(val id: String, val label: String, val badge: String)

    private var root: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { dismiss() }

    val isShowing: Boolean get() = root != null

    /**
     * Show the card next to a bubble whose drawn disc spans [anchorLeft]..[anchorRight] at
     * vertical centre [anchorCy] (screen px). It opens toward the screen centre: right of a
     * left-docked bubble, left of a right-docked one.
     */
    fun show(
        items: List<Item>,
        currentId: String,
        dark: Boolean,
        anchorLeft: Int,
        anchorRight: Int,
        anchorCy: Int,
        dockedLeft: Boolean,
        screenW: Int,
        screenH: Int,
        onPick: (String) -> Unit,
        onMore: () -> Unit,
        quiet: Boolean = false,
        onQuiet: ((Boolean) -> Unit)? = null,
    ) {
        dismiss()
        val ink = if (dark) CREAM else INK
        val sub = if (dark) SUB_DK else SUB
        val font = runCatching { ResourcesCompat.getFont(context, R.font.mulish_semibold) }.getOrNull()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (dark) SURFACE_DK else SURFACE)
                setStroke(dp(1), if (dark) EDGE_DK else EDGE)
            }
            elevation = dp(8).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        card.addView(TextView(context).apply {
            text = tr("Speech model")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(sub)
            typeface = font
            setPadding(dp(16), dp(6), dp(16), dp(4))
        })
        // Quiet mode switch: its own model list (the checked model is Quiet mode's model).
        if (onQuiet != null) {
            card.addView(row(tr("Quiet mode") + " · " + tr(if (quiet) "On" else "Off"), if (quiet) "🤫" else null, quiet, ink, font) {
                dismiss()
                onQuiet(!quiet)
            })
        }
        for (item in items) {
            val active = item.id == currentId
            card.addView(row(item.label, item.badge, active, ink, font) {
                dismiss()
                if (!active) onPick(item.id)
            })
        }
        if (items.size <= 1) {
            card.addView(TextView(context).apply {
                text = tr("Download more in Settings")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(sub)
                setPadding(dp(16), dp(2), dp(16), dp(6))
            })
        }
        card.addView(row(tr("Models…"), null, false, sub, font) { dismiss(); onMore() })

        // Room around the card for its shadow.
        val frame = LinearLayout(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) dismiss()
                false
            }
        }
        frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = frame.measuredWidth
        val h = frame.measuredHeight

        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (dockedLeft) anchorRight else anchorLeft - w).coerceIn(0, maxOf(0, screenW - w))
            y = (anchorCy - h / 2).coerceIn(dp(24), maxOf(dp(24), screenH - h - dp(24)))
        }
        try {
            wm.addView(frame, p)
            root = frame
            handler.postDelayed(autoClose, AUTO_CLOSE_MS)
        } catch (e: Exception) {
            android.util.Log.e("ModelPicker", "addView failed", e)
        }
    }

    fun dismiss() {
        handler.removeCallbacks(autoClose)
        root?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        root = null
    }

    private fun row(label: String, badge: String?, active: Boolean, ink: Int, font: Typeface?, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            setOnClickListener { onClick() }
            // Keep the card alive while the user is reading it.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) handler.removeCallbacks(autoClose)
                false
            }
            if (badge != null) {
                addView(TextView(context).apply {
                    text = badge
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (active) CREAM else MARK)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        if (active) setColor(MARK) else setStroke(dp(1), MARK)
                    }
                    minWidth = dp(24)
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12)
                })
            }
            addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ink)
                typeface = if (active) Typeface.create(font, Typeface.BOLD) else font
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = if (active) "✓" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(MARK)
                minWidth = dp(20)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
            })
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_CLOSE_MS = 6000L
        // The bubble's palette (BubbleView).
        const val SURFACE = 0xFFFFFDF9.toInt()
        const val EDGE = 0xFFE0D6CA.toInt()
        const val SURFACE_DK = 0xFF2F231E.toInt()
        const val EDGE_DK = 0xFF594941.toInt()
        const val MARK = 0xFFCD6E4C.toInt()
        const val INK = 0xFF3B2921.toInt()
        const val SUB = 0xFF8A7466.toInt()
        const val SUB_DK = 0xFFB9A79A.toInt()
        const val CREAM = 0xFFFFFBF4.toInt()
    }
}
