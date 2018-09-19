package fr.rhaz.sockets

import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.util.HashMap

val gson = Gson()

fun split(input: String, max: Int) = input.split("(?<=\\G.{$max})")

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
        while(entries.size >= 2) {
            val it = entries.take(2)
            map.put(it[0] as? String ?: continue, it[1])
        }
    }

    fun <T> getExtra(key: String) = get(key) as? T?
    fun getExtraMap(key: String) = getExtra<Map<String, Any>>(key)?.let(::JSONMap)

}