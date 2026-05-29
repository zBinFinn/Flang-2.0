package com.zbinfinn.flangmod

import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class CodeClientTalker : WebSocket.Listener {
    private var ws: WebSocket? = null

    private var waitingFuture = CompletableFuture<String>()

    fun placeFromClipboard(clipboard: String) {
        ws = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:31375"), this)
            .join()

        send("scopes clear_plot write_code read_plot movement")?.join()

        val auth = wait()
        println("Auth Response: $auth")

        send("clear")?.join()
        send("place")?.join()
        for (str in clipboard.split("\n")) {
            send("place ${str.trim()}")?.join()
        }
        send("place go")?.join()

        val done = wait()
        println("Place Response: $done")
    }

    fun wait(): String = waitingFuture.join()


    private fun send(msg: String): CompletableFuture<WebSocket?>? {
        println("OUT: $msg")
        return ws?.sendText(msg, true)
    }

    override fun onOpen(webSocket: WebSocket?) {
        super.onOpen(webSocket)
        println("CodeClient WS Connected")
    }

    override fun onText(webSocket: WebSocket?, data: CharSequence?, last: Boolean): CompletionStage<*>? {
        val msg = data.toString()
        println("INC $msg")
        waitingFuture.complete(msg)
        waitingFuture = CompletableFuture<String>()
        return super.onText(webSocket, data, last)
    }
}