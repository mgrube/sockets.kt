
package fr.rhaz.sockets

import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.PublicKey
import java.util.function.BiConsumer
import java.util.function.Consumer
import javax.crypto.SecretKey

var defaultPort = 8080
var defaultPassword = ""
var defaultTimeout = 100L
var defaultDiscovery = true
var defaultLogger = fun(ex: Exception) = println("[ALERT] ${ex.message}")
var defaultDebug = fun(msg: String){}
val defaultBootstrap = mutableListOf<String>()
val selfHosts = mutableListOf("127.0.0.1", "localhost", "0.0.0.0")

@JvmOverloads
fun multiSocket(
    name: String,
    port: Int = defaultPort,
    bootstrap: List<String> = defaultBootstrap,
    password: String = defaultPassword,
    timeout: Long = defaultTimeout,
    discovery: Boolean = defaultDiscovery
) = MultiSocket(name, port, password, timeout, discovery).apply{connect(bootstrap)}

open class MultiSocket(
    val name: String,
    val port: Int,
    val password: String,
    var timeout: Long,
    var discovery: Boolean
){

    private val server = ServerSocket(port)
    private val connections = mutableListOf<Connection>()

    val readyConnections get() = connections.filter{it.ready}
    val connectionsByTarget get() = readyConnections.associateBy{it.targetName}
    fun getConnection(target: String) = connectionsByTarget[target]

    var logger = defaultLogger
    var debug = defaultDebug

    fun interrupt() = connections.forEach{it.interrupt()}

    @JvmOverloads
    fun accept(loop: Boolean = false): Unit = run {
        server.accept().also {
            if (loop) accept(true)
        }
    }

    fun connect(address: String)  {
        val (host,port) = address.split(":")

        if(host in selfHosts && port.toInt() == this.port)
        throw Exception("Trying to connect to self")

        if(connections.any{it.targetAddress==address})
        throw Exception("Connection already exists")

        run{ Socket(host, port.toInt()) }
    }
    fun connect(addresses: List<String>) = addresses.forEach(::connect)

    fun log(ex: Exception) = logger(ex)

    // Run a connection
    private fun run(getter: () -> Socket) = Thread{
        var connection: Connection? = null
        try{
            connection = Connection(this, getter())
            this.connections += connection
            onConnect(connection)
            connection.onReady{onReady(connection)}
            connection.onMessage{msg -> parent.onMessage(connection, msg)}
            if(discovery) connection.discover()
            connection.run()
        }
        catch(ex: Exception){log(ex)}
        finally {
            connection ?: return@Thread
            connection.socket.close()
            connection.interrupt()
            connections.remove(connection)
            onDisconnect(connection)
        }
    }.start()

    inline fun catch(throwable: () -> Unit){
        try { throwable() }
        catch(ex: Exception) { log(ex) }
    }

    // Listeners
    private val onConnect = mutableListOf<Connection.() -> Unit>()
    private fun onConnect(connection: Connection) = onConnect.forEach{catch{it(connection)}}

    fun onConnect(listener: Connection.() -> Unit) { onConnect += listener }
    fun onConnect(listener: Consumer<Connection>) = onConnect{listener.accept(this)}

    private val onDisconnect = mutableListOf<Connection.() -> Unit>()
    private fun onDisconnect(connection: Connection) = onDisconnect.forEach{catch{it(connection)}}

    fun onDisconnect(listener: Connection.() -> Unit) { onDisconnect += listener}
    fun onDisconnect(listener: Consumer<Connection>) = onConnect{listener.accept(this)}

    private val onReady = mutableListOf<Connection.() -> Unit>()
    private fun onReady(connection: Connection) = onReady.forEach{catch{it(connection)}}

    fun onReady(listener: Connection.() -> Unit) { onReady += listener }
    fun onReady(listener: Consumer<Connection>) = onReady{listener.accept(this)}
    fun onReady(target: String, listener: Connection.() -> Unit)
        { onReady += { if(targetName == target) listener() } }
    fun onReady(target: String, listener: Consumer<Connection>)
        = onReady(target){listener.accept(this)}

    private val onMessage = mutableListOf<Connection.(jsonMap) -> Unit>()
    private fun onMessage(connection: Connection, msg: jsonMap)
        = onMessage.forEach{catch{it(connection, msg)}}

    fun onMessage(listener: Connection.(jsonMap) -> Unit) { onMessage += listener }
    fun onMessage(listener: BiConsumer<Connection, jsonMap>) = onMessage{listener.accept(this, it)}
    fun onMessage(channel: String, listener: Connection.(jsonMap) -> Unit)
        { onMessage += { if(it["channel"] == channel) listener(it)}}
    fun onMessage(channel: String, listener: BiConsumer<Connection, jsonMap>)
        = onMessage(channel){listener.accept(this, it)}

    val peers get() = readyConnections.map{it.targetAddress}
    fun Connection.discover() {
        onReady {
            msg("Discover", jsonMap("peers" to peers))
        }
        onMessage { msg ->
            if(msg["channel"] == "Discover"){
                val peers = msg["peers"] as? List<String>
                ?: throw Exception("Peers is not list of string")
                try{connect(peers)}
                catch (ex: Exception){}
            }
        }
    }
}

class Connection(
    val parent: MultiSocket,
    val socket: Socket
){

    val targetHost get() = socket.inetAddress.hostAddress
    var targetPort: Int = 0; private set
    val targetAddress get() = "$targetHost:$targetPort"

    val thread = Thread.currentThread()
    fun interrupt() = thread.interrupt()

    var timeout = parent.timeout

    lateinit var targetName: String private set
    private val selfName = parent.name

    private val reader = socket.getInputStream().bufferedReader()
    private val writer = PrintWriter(socket.getOutputStream())

    private lateinit var targetRSA: PublicKey
    private val selfRSA = RSA.generate()

    private lateinit var targetAES: SecretKey
    private val selfAES = AES.generate()

    fun encrypt(msg: String) =
        if(::targetAES.isInitialized)
            AES.encrypt(msg, targetAES)
        else if(::targetRSA.isInitialized)
            RSA.encrypt(msg, targetRSA)
        else msg

    fun decrypt(msg: String) =
        if(::targetAES.isInitialized)
            AES.decrypt(msg, selfAES)
        else if(::targetRSA.isInitialized)
            RSA.decrypt(msg, selfRSA.private)
        else msg

    private var selfReady = false
        set(value) {field = value; if(ready) onReady()}
    private var targetReady = false
        set(value) {field = value; if(ready) onReady()}

    val ready get() = selfReady && targetReady

    fun msg(channel: String, data: String) = msg(channel, jsonMap("data", data))
    fun msg(channel: String, data: jsonMap){
        data["channel"] = channel
        parent.debug("--> $data")
        val msg = encrypt(data.toJson())
        writer.println(msg)
        writer.flush()
    }

    private fun read(): jsonMap {
        val line = reader.readLine()
        val msg = decrypt(line)
        return fromJson(msg)
    }

    private val onReady = mutableListOf<Connection.() -> Unit>()
    private fun onReady() = onReady.forEach{parent.catch{it(this)}}

    fun onReady(listener: Connection.() -> Unit) { onReady += listener }
    fun onReady(listener: Consumer<Connection>) = onReady{listener.accept(this)}

    private val onMessage = mutableListOf<Connection.(jsonMap) -> Unit>()
    private fun onMessage(msg: jsonMap) = onMessage.forEach{parent.catch{it(this, msg)}}

    fun onMessage(listener: Connection.(jsonMap) -> Unit) { onMessage += listener }
    fun onMessage(listener: BiConsumer<Connection, jsonMap>) = onMessage{listener.accept(this, it)}
    fun onMessage(channel: String, listener: Connection.(jsonMap) -> Unit)
        { onMessage += { if(it["channel"] == channel) listener(it)} }
    fun onMessage(channel: String, listener: BiConsumer<Connection, jsonMap>)
        = onMessage(channel){listener.accept(this, it)}


    internal fun run() {

        msg("Sockets", jsonMap(
            "status" to "rsa",
            "key" to RSA.savePublicKey(selfRSA.public)
        ))

        while(true){
            Thread.sleep(timeout)

            val msg = read()
            parent.debug("<--- $msg")

            if(ready){
                onMessage(msg)
                continue
            }

            if(msg["channel"] != "Sockets")
            throw Exception("Unexpected message: $msg")

            val status = msg["status"] as? String
            ?: throw Exception("Unexpected message: $msg")

            if(status == "rsa"){
                val key = msg["key"] as? String
                ?: throw Exception("RSA is not a string")
                targetRSA = RSA.loadPublicKey(key)

                msg("Sockets", jsonMap(
                    "status" to "aes",
                    "key" to AES.toString(selfAES)
                ))
                continue
            }

            if(status == "aes"){
                val key = msg["key"] as? String
                ?: throw Exception("AES is not a string")
                targetAES = AES.toKey(key)

                msg("Sockets", jsonMap(
                    "status" to "pending",
                    "name" to selfName,
                    "port" to parent.port,
                    "password" to parent.password
                ))
                continue
            }

            if(status == "pending"){
                targetName = msg["name"] as? String
                ?: throw Exception("Name is not a string")

                targetPort = (msg["port"] as? Double)?.toInt()
                ?: throw Exception("Port is not a number")

                val password = msg["password"] as? String
                ?: throw Exception("Password is not a string")
                if(password != parent.password)
                msg("Sockets", jsonMap(
                    "status" to "error",
                    "data" to "Bad password"
                ))

                selfReady = true
                msg("Sockets", jsonMap("status" to "ready"))
                continue
            }

            if(status == "ready"){
                targetReady = true
                continue
            }

            if(status == "error"){
                val data = msg["data"] as? String
                ?: throw Exception("Error data is not a string")
                throw Exception(data)
            }

            throw Exception("Unexpected status: $status")
        }
    }
}