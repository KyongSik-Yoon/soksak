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
import android.widget.FrameLayout
import android.widget.ImageView
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
        /** The "Send to PC" mode ([PcLink.MODES]); null hides the row (no PC set up). */
        pcSend: String? = null,
        onPcSend: () -> Unit = {},
        /** What the PC does with the text ([Settings.pcType]); its row shows while sending. */
        pcType: Boolean = false,
        onPcType: () -> Unit = {},
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
        // Switches first, each a line icon, its name and its current value; then the models.
        val accent = if (dark) MARK_DK else MARK
        var switches = false
        if (onQuiet != null) {
            switches = true
            val tint = if (quiet) (if (dark) QUIET_DK else QUIET) else sub
            card.addView(switchRow(R.drawable.ic_menu_quiet, tr("Quiet mode"), tr(if (quiet) "On" else "Off"), tint, quiet, ink, sub, font) {
                dismiss()
                onQuiet(!quiet)
            })
        }
        if (pcSend != null) {
            switches = true
            val on = pcSend != PcLink.OFF
            val tint = if (on) accent else sub
            card.addView(switchRow(R.drawable.ic_menu_pc, tr("Send to PC"), tr(PcLink.label(pcSend)), tint, on, ink, sub, font) {
                dismiss()
                onPcSend()
            })
            if (on) {
                val icon = if (pcType) R.drawable.ic_menu_keyboard else R.drawable.ic_clipboard
                card.addView(switchRow(icon, tr("On the PC"), tr(PcLink.actionLabel(pcType)), sub, false, ink, sub, font) {
                    dismiss()
                    onPcType()
                })
            }
        }
        if (switches) card.addView(divider(dark))
        card.addView(TextView(context).apply {
            text = tr("Speech model")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(sub)
            typeface = font
            setPadding(dp(16), dp(6), dp(16), dp(4))
        })
        for (item in items) {
            val active = item.id == currentId
            card.addView(row(item.label, item.badge, active, ink, accent, font) {
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
        card.addView(divider(dark))
        card.addView(row(tr("Models…"), null, false, sub, accent, font) { dismiss(); onMore() })

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

    /** A model (or the footer link): badge chip, name, and a check on the active one. */
    private fun row(label: String, badge: String?, active: Boolean, ink: Int, accent: Int, font: Typeface?, onClick: () -> Unit): View =
        rowShell(onClick).apply {
            addView(lead(if (badge == null) null else TextView(context).apply {
                text = badge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (active) CREAM else accent)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    if (active) setColor(MARK) else setStroke(dp(1), accent)
                }
                minWidth = dp(24)
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }))
            addView(name(label, ink, if (active) Typeface.create(font, Typeface.BOLD) else font),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                if (active) {
                    setImageResource(R.drawable.ic_menu_check)
                    setColorFilter(accent)
                }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(16) })
        }

    /** A setting that a tap flips or steps: line icon, name, and what it is set to now. */
    private fun switchRow(
        icon: Int, label: String, value: String, tint: Int, on: Boolean,
        ink: Int, sub: Int, font: Typeface?, onClick: () -> Unit,
    ): View =
        rowShell(onClick).apply {
            addView(lead(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(tint)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL)
            }))
            addView(name(label, ink, font), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (on) tint else sub)
                typeface = if (on) Typeface.create(font, Typeface.BOLD) else font
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(20)
            })
        }

    /**
     * The column every row starts with, one width for icons and model chips alike (and empty on
     * the footer), so all the names begin on the same line.
     */
    private fun lead(child: View?): View =
        FrameLayout(context).apply {
            if (child != null) {
                addView(child, child.layoutParams ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                ))
            }
            layoutParams = LinearLayout.LayoutParams(dp(LEAD_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
        }

    private fun rowShell(onClick: () -> Unit): LinearLayout =
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
        }

    private fun name(label: String, ink: Int, face: Typeface?): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ink)
            typeface = face
            maxLines = 1
        }

    private fun divider(dark: Boolean): View =
        View(context).apply {
            setBackgroundColor(if (dark) EDGE_DK else EDGE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_CLOSE_MS = 6000L
        /** Wide enough for a two-letter model chip. */
        const val LEAD_DP = 30
        // The bubble's palette (BubbleView).
        const val SURFACE = 0xFFFDFCFE.toInt()
        const val EDGE = 0xFFDCD5E6.toInt()
        const val SURFACE_DK = 0xFF282233.toInt()
        const val EDGE_DK = 0xFF4A4258.toInt()
        const val MARK = 0xFF6E56A8.toInt()
        const val MARK_DK = 0xFFBBA6EE.toInt()
        // Quiet mode's rose (BubbleView's badge).
        const val QUIET = 0xFFB4608E.toInt()
        const val QUIET_DK = 0xFFE8A3C6.toInt()
        const val INK = 0xFF2E2A38.toInt()
        const val SUB = 0xFF6B6478.toInt()
        const val SUB_DK = 0xFFC5BDD3.toInt()
        const val CREAM = 0xFFF7F5FB.toInt()
    }
}
