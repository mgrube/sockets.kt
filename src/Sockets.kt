
package fr.rhaz.sockets

import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey

fun main(args: Array<String>){
    val socket = multiSocket(args[0],
        port = args[1].toInt(),
        bootstrap = args.drop(2)
    )
    socket.accept(true)
    while(true){
        Thread.sleep(10000)
        println("Peers: ${socket.peers}")
    }
}

var defaultPort = 8080
var defaultTimeout = 100L
var defaultDiscovery = true
var defaultLogger = fun(ex: Exception) = println("[ALERT] ${ex.message}")
val defaultBootstrap = mutableListOf<String>()
val selfHosts = mutableListOf("127.0.0.1", "localhost", "0.0.0.0")

@JvmOverloads
fun multiSocket(
    name: String,
    port: Int = defaultPort,
    timeout: Long = defaultTimeout,
    discovery: Boolean = defaultDiscovery,
    logger: (Exception) -> Unit = defaultLogger,
    bootstrap: List<String> = defaultBootstrap
) = MultiSocket(name, port, timeout, discovery, logger).apply{connect(bootstrap)}

open class MultiSocket(
    val name: String,
    val port: Int,
    var timeout: Long,
    var discovery: Boolean,
    var logger: (Exception) -> Unit
){

    private val server = ServerSocket(port)
    private val connections = mutableListOf<Connection>()

    val readyConnections get() = connections.filter{it.ready}
    val connectionsByTarget get() = readyConnections.associateBy{it.targetName}
    fun getConnection(target: String) = connectionsByTarget[target]

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
            connection.onMessage{msg -> onMessage(connection, msg)}
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
    fun onConnect(listener: Connection.() -> Unit){ onConnect += listener }
    private fun onConnect(connection: Connection)
    = onConnect.forEach{catch{it(connection)}}

    private val onDisconnect = mutableListOf<Connection.() -> Unit>()
    fun onDisconnect(listener: Connection.() -> Unit){ onDisconnect += listener}
    private fun onDisconnect(connection: Connection)
    = onDisconnect.forEach{catch{it(connection)}}

    private val onReady = mutableListOf<Connection.() -> Unit>()
    fun onReady(listener: Connection.() -> Unit){ onReady += listener }
    private fun onReady(connection: Connection)
    = onReady.forEach{catch{it(connection)}}

    private val onMessage = mutableListOf<Connection.(jsonMap) -> Unit>()
    fun onMessage(listener: Connection.(jsonMap) -> Unit){ onMessage += listener }
    private fun onMessage(connection: Connection, msg: jsonMap)
    = onMessage.forEach{catch{it(connection, msg)}}

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
    val selfName = parent.name

    private val reader = socket.getInputStream().bufferedReader()
    private val writer = PrintWriter(socket.getOutputStream())

    private lateinit var targetKey: SecretKey
    private val selfKey = AES.generate()

    private var selfReady = false
        set(value) {field = value; if(ready) onReady()}
    private var targetReady = false
        set(value) {field = value; if(ready) onReady()}

    val ready get() = selfReady && targetReady

    fun encrypt(msg: String) = if(ready) AES.encrypt(msg, selfKey) else msg
    fun decrypt(msg: String) = if(ready) AES.decrypt(msg, targetKey) else msg

    fun msg(channel: String, data: jsonMap){
        data["channel"] = channel
        val msg = encrypt(data.toJson())
        writer.println(msg)
        writer.flush()
    }

    private fun read(): jsonMap {
        val line = reader.readLine()
        val msg = decrypt(line)
        ?: throw Exception("Could not decrypt $line")
        return fromJson(msg)
    }

    private val onReady = mutableListOf<() -> Unit>()
    fun onReady(listener: () -> Unit) { onReady += listener }
    private fun onReady() = onReady.forEach{parent.catch(it)}

    private val onMessage = mutableListOf<(jsonMap) -> Unit>()
    fun onMessage(listener: (jsonMap) -> Unit) { onMessage += listener }
    private fun onMessage(msg: jsonMap) = onMessage.forEach{parent.catch{it(msg)}}

    internal fun run() {

        msg("Sockets", jsonMap(
            "status", "pending",
            "key", AES.toString(selfKey),
            "name", selfName,
            "port", parent.port
        ))

        while(true){
            Thread.sleep(timeout)

            val msg = read()

            if(ready){
                onMessage(msg)
                continue
            }

            if(msg["channel"] != "Sockets")
            throw Exception("Unexpected message: $msg")

            val status = msg["status"] as? String
            ?: throw Exception("Unexpected message: $msg")

            if(status == "ready"){
                targetReady = true
                continue
            }

            if(status == "pending"){
                val key = msg["key"] as? String
                ?: throw Exception("AES is not a string")
                targetKey = AES.toKey(key)

                targetName = msg["name"] as? String
                ?: throw Exception("Name is not a string")

                targetPort = (msg["port"] as? Double)?.toInt()
                ?: throw Exception("Port is not a number")

                selfReady = true
                msg("Sockets", jsonMap("status", "ready"))
                continue
            }

            throw Exception("Unexpected status: $status")
        }
    }
}