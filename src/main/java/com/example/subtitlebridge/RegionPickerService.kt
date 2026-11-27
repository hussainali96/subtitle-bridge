package com.example.subtitlebridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun screenMetrics(ctx: Context): DisplayMetrics {
    val m = DisplayMetrics()
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    @Suppress("DEPRECATION")
    dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
    return m
}

class PickerView(
    ctx: Context,
    private val fl: Float,
    private val ft: Float,
    private val fr: Float,
    private val fb: Float
) : View(ctx) {
    val rect = RectF()
    private var ready = false
    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private var mode = 0 // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!ready && w > 0 && h > 0) {
            val m = screenMetrics(context)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            rect.set(
                fl * m.widthPixels - loc[0], ft * m.heightPixels - loc[1],
                fr * m.widthPixels - loc[0], fb * m.heightPixels - loc[1]
            )
            ready = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, rect.top, dim)
        canvas.drawRect(0f, rect.bottom, w, h, dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
        canvas.drawRect(rect, border)
        val rr = 9f * d
        canvas.drawCircle(rect.left, rect.top, rr, handle)
        canvas.drawCircle(rect.right, rect.top, rr, handle)
        canvas.drawCircle(rect.left, rect.bottom, rr, handle)
        canvas.drawCircle(rect.right, rect.bottom, rr, handle)
    }

    private fun near(x: Float, y: Float, px: Float, py: Float): Boolean {
        val r = 44f * d
        return (x - px) * (x - px) + (y - py) * (y - py) <= r * r
    }

    private fun hit(x: Float, y: Float): Int = when {
        near(x, y, rect.left, rect.top) -> 2
        near(x, y, rect.right, rect.top) -> 3
        near(x, y, rect.left, rect.bottom) -> 4
        near(x, y, rect.right, rect.bottom) -> 5
        rect.contains(x, y) -> 1
        else -> 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hit(e.x, e.y)
                lastX = e.x
                lastY = e.y
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x
                lastY = e.y
                val minW = 80f * d
                val minH = 30f * d
                when (mode) {
                    1 -> {
                        val ox = dx.coerceIn(-rect.left, width - rect.right)
                        val oy = dy.coerceIn(-rect.top, height - rect.bottom)
                        rect.offset(ox, oy)
                    }
                    2 -> {
                        rect.left = (rect.left + dx).coerceIn(0f, rect.right - minW)
                        rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minH)
                    }
                    3 -> {
                        rect.right = (rect.right + dx).coerceIn(rect.left + minW, width.toFloat())
                        rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minH)
                    }
                    4 -> {
                        rect.left = (rect.left + dx).coerceIn(0f, rect.right - minW)
                        rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minH, height.toFloat())
                    }
                    5 -> {
                        rect.right = (rect.right + dx).coerceIn(rect.left + minW, width.toFloat())
                        rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minH, height.toFloat())
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                return true
            }
        }
        return true
    }
}

class RegionPickerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ show() }, 6000)
        return START_NOT_STICKY
    }

    private fun show() {
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        Config.load(this)
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val picker = PickerView(this, Config.l, Config.t, Config.r, Config.b)

        val hint = TextView(this).apply {
            text = "حرّك الإطار الأحمر أو اسحب زواياه ليغطي مكان الترجمة فقط"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val save = Button(this).apply {
            text = "حفظ"
            isAllCaps = false
            setOnClickListener { saveRegion(picker) }
        }
        val cancel = Button(this).apply {
            text = "إلغاء"
            isAllCaps = false
            setOnClickListener { close() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancel)
            addView(save)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setPadding(24, 12, 24, 12)
            addView(hint)
            addView(buttons)
        }

        val frame = FrameLayout(this).apply {
            addView(
                picker,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        manager.addView(frame, lp)
        root = frame
    }

    private fun saveRegion(picker: PickerView) {
        val m = screenMetrics(this)
        val loc = IntArray(2)
        picker.getLocationOnScreen(loc)
        val r = picker.rect
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        Config.l = ((r.left + loc[0]) / w).coerceIn(0f, 0.95f)
        Config.t = ((r.top + loc[1]) / h).coerceIn(0f, 0.95f)
        Config.r = ((r.right + loc[0]) / w).coerceIn(Config.l + 0.05f, 1f)
        Config.b = ((r.bottom + loc[1]) / h).coerceIn(Config.t + 0.03f, 1f)
        Config.save(this)
        Toast.makeText(this, "تم حفظ منطقة الترجمة", Toast.LENGTH_SHORT).show()
        close()
    }

    private fun close() {
        root?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        root = null
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        root?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        root = null
        super.onDestroy()
    }
}
