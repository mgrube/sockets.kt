package fr.rhaz.sockets

import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.util.ArrayList

val gson = Gson()

typealias jsonMap = MutableMap<String, Any>
fun jsonMap() = mutableMapOf<String, Any>()
fun jsonMap(map: Map<String, Any>) = map.toMutableMap()
fun jsonMap(vararg pairs: Pair<String, Any>) = mutableMapOf(*pairs)
fun jsonMap(vararg entries: Any) = jsonMap().also{
    var mentries = entries.toList()
    while(mentries.size >= 2) {
        val pair = mentries.take(2)
        it[pair[0] as? String ?: continue] = pair[1]
        mentries = mentries.drop(2)
    }
}

fun jsonMap.toJson() = gson.toJson(this)
fun fromJson(msg: String) = gson.fromJson<Map<String, Any>>(msg, Map::class.java).toMutableMap()
