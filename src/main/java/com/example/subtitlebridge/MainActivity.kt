package com.example.subtitlebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet

class PageServer(private val context: Context) : NanoHTTPD(8080) {
    override fun serve(session: IHTTPSession): Response = try {
        val html = context.assets.open("index.html").bufferedReader().readText()
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "page not found")
    }
}

class SubtitleSocket : NanoWSD(8081) {
    private val clients = CopyOnWriteArraySet<Client>()

    inner class Client(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() { clients.add(this) }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, byRemote: Boolean) { clients.remove(this) }
        override fun onMessage(message: WebSocketFrame?) {}
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) { clients.remove(this) }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    fun clientCount(): Int = clients.size

    private fun broadcast(json: JSONObject) {
        val msg = json.toString()
        clients.forEach { c -> try { c.send(msg) } catch (e: Exception) { clients.remove(c) } }
    }

    fun sendSubtitle(text: String, speaker: String? = null) = broadcast(
        JSONObject().put("type", "sub").put("text", text).apply { speaker?.let { put("speaker", it) } }
    )

    fun sendStyle(position: String? = null, size: Double? = null, color: String? = null) =
        broadcast(JSONObject().put("type", "style").apply {
            position?.let { put("position", it) }
            size?.let { put("size", it) }
            color?.let { put("color", it) }
        })
}

class MainActivity : AppCompatActivity() {
    private lateinit var pages: PageServer
    private lateinit var socket: SubtitleSocket
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val BG = Color.parseColor("#0E1014")
    private val CARD = Color.parseColor("#1A1D24")
    private val STROKE = Color.parseColor("#2A2E38")
    private val ACCENT = Color.parseColor("#7C5CFF")
    private val TEXT = Color.parseColor("#EDEFF3")
    private val MUTED = Color.parseColor("#8A90A0")
    private val OK = Color.parseColor("#2ECC71")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tick = object : Runnable {
        override fun run() {
            val n = socket.clientCount()
            dot.background = oval(if (n > 0) OK else MUTED, false)
            statusText.text = if (n > 0) "الشاشة متصلة" else "بانتظار اتصال الشاشة"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = PageServer(this).also { it.start() }
        socket = SubtitleSocket().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }

        // Header
        root.addView(label("TransGame", 30f, TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR })
        root.addView(label("ترجمة ألعابك فورياً على التلفاز", 14f, MUTED))

        // Status + address card
        val statusCard = card()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this).apply {
            background = oval(MUTED, false)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
        }
        statusText = label("بانتظار اتصال الشاشة", 15f, TEXT, true)
        statusRow.addView(dot); statusRow.addView(statusText)
        statusCard.addView(statusRow)
        statusCard.addView(label("افتح هذا العنوان في متصفح تلفاز LG", 13f, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
        val address = "http://${localIp()}:8080"
        statusCard.addView(label(address, 20f, ACCENT, true).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR
        })
        statusCard.addView(pill("نسخ العنوان", false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("address", address))
            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
        })
        root.addView(statusCard)

        // Test card
        val testCard = card()
        testCard.addView(label("اختبار الترجمة", 16f, TEXT, true))
        val input = EditText(this).apply {
            setText("هذه ترجمة تجريبية من الهاتف")
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 16f
            background = rounded(BG, 12, STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        testCard.addView(input)
        testCard.addView(pill("إرسال إلى الشاشة", true) { socket.sendSubtitle(input.text.toString(), "TransGame") })
        root.addView(testCard)

        // Style card
        val styleCard = card()
        styleCard.addView(label("مظهر الترجمة", 16f, TEXT, true))

        styleCard.addView(label("الموضع", 13f, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
        styleCard.addView(segmented { isTop -> socket.sendStyle(position = if (isTop) "top" else "bottom") })

        styleCard.addView(label("الحجم", 13f, MUTED).apply { setPadding(0, dp(16), 0, dp(2)) })
        styleCard.addView(SeekBar(this).apply {
            max = 40; progress = 14
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) socket.sendStyle(size = (p + 20) / 10.0)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        styleCard.addView(label("اللون", 13f, MUTED).apply { setPadding(0, dp(14), 0, dp(8)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("#FFFFFF", "#FFD93D", "#4DD0E1").forEach { c ->
            chips.addView(View(this).apply {
                background = oval(Color.parseColor(c), true)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
                setOnClickListener { socket.sendStyle(color = c) }
            })
        }
        styleCard.addView(chips)
        root.addView(styleCard)

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        ui.post(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick); pages.stop(); socket.stop(); super.onDestroy()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun oval(color: Int, outlined: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (outlined) setStroke(dp(2), STROKE)
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20, STROKE)
        setPadding(dp(18), dp(16), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else TEXT)
        background = if (filled) rounded(ACCENT, 14) else rounded(Color.TRANSPARENT, 14, STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun segmented(onPick: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(BG, 12, STROKE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        fun seg(t: String) = TextView(this).apply {
            text = t; gravity = Gravity.CENTER; textSize = 15f; setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val top = seg("أعلى"); val bottom = seg("أسفل")
        fun select(isTop: Boolean) {
            top.background = rounded(if (isTop) ACCENT else Color.TRANSPARENT, 10)
            bottom.background = rounded(if (!isTop) ACCENT else Color.TRANSPARENT, 10)
        }
        top.setOnClickListener { select(true); onPick(true) }
        bottom.setOnClickListener { select(false); onPick(false) }
        select(false)
        row.addView(top); row.addView(bottom)
        return row
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "؟"
}
