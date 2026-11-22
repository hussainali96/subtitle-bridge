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

    private val BG = Color.parseColor("#050A1C")
    private val CARD = Color.parseColor("#0E1736")
    private val STROKE = Color.parseColor("#1C2A58")
    private val ACCENT = Color.parseColor("#1A7FE0")
    private val TEXT = Color.parseColor("#EDEFF3")
    private val MUTED = Color.parseColor("#8FA0C8")
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

        root.addView(label("TransGame", 30f, TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR })
        root.addView(label("ترجمة ألعابك فورياً على التلفاز", 14f, MUTED))

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
