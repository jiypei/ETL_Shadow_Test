package com.etlshadowtest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A caller's callback endpoint. Answers with the scripted status codes in order, then 200. */
class WebhookReceiver(vararg script: Int) : AutoCloseable {
    private val mapper = ObjectMapper()
    private val statuses = ConcurrentLinkedQueue(script.toList())
    val bodies = CopyOnWriteArrayList<JsonNode>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            bodies.add(mapper.readTree(exchange.requestBody.readAllBytes()))
            val status = statuses.poll() ?: 200
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/hook"

    /** Waits until [count] callbacks have arrived (or gives up), then returns what arrived. */
    fun await(count: Int, timeoutMillis: Long = 15_000): List<JsonNode> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (bodies.size < count && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return bodies.toList()
    }

    override fun close() = server.stop(0)
}
