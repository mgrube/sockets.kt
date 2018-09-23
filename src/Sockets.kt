package fr.rhaz.sockets

import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.util.HashMap
import sun.reflect.annotation.AnnotationParser.toArray
import java.util.ArrayList



val gson = Gson()

fun split(text: String, size: Int): Array<String> {
    val parts = ArrayList<String>()
    val length = text.length
    var i = 0
    while (i < length) {
        parts.add(text.substring(i, Math.min(length, i + size)))
        i += size
    }
    return parts.toTypedArray()
}

abstract class SocketApp{
    open fun log(err: String) {}
    open fun run(runnable: Runnable) = Thread(runnable).start()
    abstract class Server: SocketApp() {
        open fun onConnect(mess: SocketMessenger) {}
        open fun onDisconnect(mess: SocketMessenger) {}
        open fun onHandshake(mess: SocketMessenger, name: String) {}
        abstract fun onMessage(mess: SocketMessenger, map: JSONMap)
    }
    abstract class Client: SocketApp() {
        open fun onConnect(client: SocketClient) {}
        open fun onDisconnect(client: SocketClient) {}
        open fun onHandshake(client: SocketClient) {}
        abstract fun onMessage(client: SocketClient, map: JSONMap)
    }
}

interface SocketHandler{
    fun isServer() = this is SocketServer
    fun isClient() = this is SocketClient
}

interface SocketWriter {
    val running: Boolean
    val handshaked: Boolean
    val ready: Boolean
    fun write(data: String)
    fun write(channel: String, data: String)
    fun write(channel: String, data: JSONMap)
    fun close(): IOException?
}

open class Logger : ByteArrayOutputStream() {
    var writer = PrintWriter(System.out)
    val inputStream = ByteArrayInputStream(this.buf, 0, this.count)
}

open class Message {

    var msg = ""
    var ended = false

    @Throws(IllegalStateException::class)
    fun add(line: String) {
        if (ended) throw IllegalStateException()
        msg += line
    }

    @Throws(IllegalStateException::class)
    fun get() = msg.also{
        if (!ended) throw IllegalStateException()
    }

    fun end() { ended = true }

    fun reset() {
        if (!ended) throw IllegalStateException()
        msg = ""
        ended = false
    }

    fun map(): Map<String, Any> {
        if (!ended) throw IllegalStateException()
        return gson.fromJson<Map<String, Any>>(msg, Map::class.java)
    }

    fun egr(): String {
        end()
        val msg = get()
        reset()
        return msg
    }

    fun emr(): JSONMap {
        end()
        val map = JSONMap(map())
        reset()
        return map
    }
}

open class JSONMap : HashMap<String, Any> {

    val channel = getExtra<String>("channel")

    constructor() : super()
    constructor(map: Map<String, Any>): super(map)
    constructor(vararg entries: Any) {
        val map = mutableMapOf<String, Any>()
        var mentries = entries.toList()
        while(mentries.size >= 2) {
            val it = mentries.take(2)
            mentries = mentries.drop(2)
            map[it[0] as? String ?: continue] = it[1]
        }
        putAll(map)
    }

    fun <T> getExtra(key: String) = get(key) as? T?
    fun getExtraMap(key: String) = getExtra<Map<String, Any>>(key)?.let(::JSONMap)

}