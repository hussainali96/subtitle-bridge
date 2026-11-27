package com.example.subtitlebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

object Config {
    // Subtitle region as fractions of the screen (0.0 - 1.0)
    @Volatile var l = 0.05f
    @Volatile var t = 0.72f
    @Volatile var r = 0.95f
    @Volatile var b = 0.97f
    // true = read the whole screen
    @Volatile var full = false
    @Volatile var overlayAtTop = true
    val source: String = TranslateLanguage.ENGLISH
    val target: String = TranslateLanguage.ARABIC

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        l = p.getFloat("l", 0.05f)
        t = p.getFloat("t", 0.72f)
        r = p.getFloat("r", 0.95f)
        b = p.getFloat("b", 0.97f)
        full = p.getBoolean("full", false)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putFloat("l", l).putFloat("t", t).putFloat("r", r).putFloat("b", b)
            .putBoolean("full", full).apply()
    }
}

class TranslateService : Service() {
    private val SIG_W = 64
    private val SIG_H = 24
    private val CAPTURE_MAX = 1440

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var dpi = 320

    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var overlay: TextView? = null
    private var hint: View? = null

    private var translator: Translator? = null
    @Volatile private var modelReady = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastSig: IntArray? = null
    @Volatile private var busy = false
    private var lastOcrAt = 0L
    private var lastText = ""
    private val cache = HashMap<String, String>()
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            shutdown()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startAsForeground()
        Config.load(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val t = HandlerThread("transgame-capture").also { it.start() }
        thread = t
        bg = Handler(t.looper)

        setupTranslator()
        setupProjection(code, data)
        addOverlay()
        showRegionHint()
        bg?.postDelayed(loop, 4500)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("translate", "TransGame translate", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TranslateService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, "translate")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TransGame")
            .setContentText("الترجمة التلقائية تعمل")
            .addAction(0, "إيقاف", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 2, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
    }

    private fun setupTranslator() {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(Config.source)
            .setTargetLanguage(Config.target)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true }
            .addOnFailureListener { showText("تعذر تنزيل نموذج الترجمة، تأكد من الإنترنت") }
    }

    private fun computeSize() {
        val m = screenMetrics(this)
        dpi = m.densityDpi
        val maxSide = maxOf(m.widthPixels, m.heightPixels)
        val scale = if (maxSide > CAPTURE_MAX) CAPTURE_MAX.toFloat() / maxSide else 1f
        width = (m.widthPixels * scale).toInt()
        height = (m.heightPixels * scale).toInt()
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        }, main)
        computeSize()
        val r = newReader()
        reader = r
        display = p.createVirtualDisplay(
            "transgame", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, main)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            val ow = width
            val oh = height
            computeSize()
            if (ow != width || oh != height) {
                val old = reader
                val r = newReader()
                reader = r
                display?.resize(width, height, dpi)
                display?.surface = r.surface
                old?.close()
                lastSig = null
            }
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Exception) { }
            bg?.postDelayed(this, 150)
        }
    }

    private fun tick() {
        val r = reader ?: return
        val image = r.acquireLatestImage() ?: return
        val bmp: Bitmap = try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)

            val whole = Config.full
            val fl = if (whole) 0f else Config.l.coerceIn(0f, 0.95f)
            val ft = if (whole) 0f else Config.t.coerceIn(0f, 0.95f)
            val fr = if (whole) 1f else Config.r.coerceIn(fl + 0.05f, 1f)
            val fb = if (whole) 1f else Config.b.coerceIn(ft + 0.03f, 1f)
            val x = (image.width * fl).toInt()
            val y = (image.height * ft).toInt()
            val w = (image.width * (fr - fl)).toInt().coerceAtLeast(8).coerceAtMost(image.width - x)
            val h = (image.height * (fb - ft)).toInt().coerceAtLeast(8).coerceAtMost(image.height - y)
            val cropped = Bitmap.createBitmap(full, x, y, w, h)
            if (cropped !== full) full.recycle()
            cropped
        } finally {
            image.close()
        }

        val small = Bitmap.createScaledBitmap(bmp, SIG_W, SIG_H, true)
        val sig = IntArray(SIG_W * SIG_H)
        small.getPixels(sig, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        if (small !== bmp) small.recycle()
        for (i in sig.indices) {
            val c = sig[i]
            sig[i] = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
        val last = lastSig
        var diff = 255
        if (last != null && last.size == sig.size) {
            var s = 0L
            for (i in sig.indices) s += abs(sig[i] - last[i])
            diff = (s / sig.size).toInt()
        }
        val now = SystemClock.elapsedRealtime()
        val gap = if (Config.full) 800L else 0L
        if ((last == null || diff >= 4) && !busy && now - lastOcrAt >= gap) {
            lastSig = sig
            lastOcrAt = now
            busy = true
            runOcr(bmp)
        } else {
            bmp.recycle()
        }
    }

    private fun runOcr(bmp: Bitmap) {
        val whole = Config.full
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val list: List<String> = if (whole) {
                    res.textBlocks.map { b -> clean(b.text) }.filter { s -> s.isNotEmpty() }.take(6)
                } else {
                    val s = clean(res.text)
                    if (s.isEmpty()) emptyList() else listOf(s)
                }
                handleTexts(list)
            }
            .addOnCompleteListener {
                busy = false
                bmp.recycle()
            }
    }

    private fun clean(raw: String): String {
    val t = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    if (Regex("[\\u0600-\\u06FF]").containsMatchIn(t)) return ""
    val letters = t.filter { it.isLetter() }
    if (letters.length < 3) return ""
    val ascii = letters.count { it in 'A'..'Z' || it in 'a'..'z' }
    if (ascii.toFloat() / letters.length < 0.95f) return ""
    return t
    }
    
    private fun handleTexts(list: List<String>) {
        val key = list.joinToString("\n")
        if (key.isEmpty()) {
            if (lastText.isNotEmpty()) {
                lastText = ""
                showText("")
                Bridge.socket?.sendSubtitle("")
            }
            return
        }
        if (key == lastText) return

        val cached = list.map { cache[it] }
        if (cached.all { it != null }) {
            lastText = key
            present(cached.filterNotNull().joinToString("\n"))
            return
        }

        val tr = translator
        if (tr == null || !modelReady) return
        lastText = key
        val tasks = list.map { s ->
            val c = cache[s]
            if (c != null) Tasks.forResult(c)
            else tr.translate(s).addOnSuccessListener { out -> cache[s] = out }
        }
        Tasks.whenAllSuccess<String>(tasks).addOnSuccessListener { outs ->
            if (key == lastText) present(outs.joinToString("\n"))
        }
    }

    private fun present(out: String) {
        showText(out)
        Bridge.socket?.sendSubtitle(out)
    }

    private fun showText(s: String) {
        main.post {
            overlay?.let {
                it.text = s
                it.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun addOverlay() {
        val tv = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER
            maxLines = 8
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (Config.overlayAtTop) Gravity.TOP else Gravity.BOTTOM
            y = 40
        }
        wm.addView(tv, lp)
        overlay = tv
    }

    private fun showRegionHint() {
        val m = screenMetrics(this)
        val whole = Config.full
        val fl = if (whole) 0f else Config.l.coerceIn(0f, 0.95f)
        val ft = if (whole) 0f else Config.t.coerceIn(0f, 0.95f)
        val fr = if (whole) 1f else Config.r.coerceIn(fl + 0.05f, 1f)
        val fb = if (whole) 1f else Config.b.coerceIn(ft + 0.03f, 1f)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(6, Color.RED)
            }
        }
        val lp = WindowManager.LayoutParams(
            (m.widthPixels * (fr - fl)).toInt(),
            (m.heightPixels * (fb - ft)).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (m.widthPixels * fl).toInt()
            y = (m.heightPixels * ft).toInt()
        }
        wm.addView(v, lp)
        hint = v
        main.postDelayed({
            hint?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            hint = null
        }, 4000)
    }

    private fun shutdown() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        bg?.removeCallbacksAndMessages(null)
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (e: Exception) { }
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        overlay?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        overlay = null
        hint?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        hint = null
        translator?.close(); translator = null
        thread?.quitSafely(); thread = null
        Bridge.socket?.sendSubtitle("")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        recognizer.close()
        super.onDestroy()
    }
}
