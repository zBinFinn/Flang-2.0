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

        send("scopes clear_plot write_code read_plot movement").join()

        val auth = wait()
        println("Auth Response: $auth")

        send("clear").thenCompose {
            send("place")
        }.thenCompose {
            CompletableFuture.allOf(
                *clipboard.split("\n")
                    .filter { it.trim().isNotEmpty() }
                    .map { send("place ${it.trim()}") }
                    .toTypedArray()
            )
        }.thenCompose {
            send("place go")
        }

        val done = wait()
        println("Place Response: $done")
    }

    fun wait(): String = waitingFuture.join()


    private fun send(msg: String): CompletableFuture<WebSocket?> {
        println("OUT: $msg")
        return ws?.sendText(msg, true) ?: CompletableFuture.failedFuture(IllegalStateException("WebSocket is null"))
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