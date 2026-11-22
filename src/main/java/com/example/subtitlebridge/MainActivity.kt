package com.example.subtitlebridge

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

    private fun broadcast(json: JSONObject) {
        val msg = json.toString()
        clients.forEach { c -> try { c.send(msg) } catch (e: Exception) { clients.remove(c) } }
    }

    fun sendSubtitle(text: String, speaker: String? = null) = broadcast(
        JSONObject().put("type", "sub").put("text", text).apply { speaker?.let { put("speaker", it) } }
    )

    fun sendStyle(position: String) = broadcast(JSONObject().put("type", "style").put("position", position))
}

class MainActivity : AppCompatActivity() {
    private lateinit var pages: PageServer
    private lateinit var socket: SubtitleSocket

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = PageServer(this).also { it.start() }
        socket = SubtitleSocket().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

        val address = TextView(this).apply {
            text = "افتح هذا العنوان في متصفح شاشة LG:\nhttp://${localIp()}:8080"
            textSize = 18f; gravity = Gravity.CENTER
        }
        val input = EditText(this).apply {
            setText("هذه ترجمة تجريبية من الهاتف"); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val send = Button(this).apply {
            text = "أرسل إلى الشاشة"
            setOnClickListener { socket.sendSubtitle(input.text.toString(), "اختبار") }
        }
        val toggle = Button(this).apply {
            text = "بدّل الموضع (أعلى/أسفل)"
            var top = false
            setOnClickListener { top = !top; socket.sendStyle(if (top) "top" else "bottom") }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            listOf(address, input, send, toggle).forEach { addView(it) }
        })
    }

    override fun onDestroy() { pages.stop(); socket.stop(); super.onDestroy() }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "؟"
}
