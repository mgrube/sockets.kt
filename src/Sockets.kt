
package fr.rhaz.sockets

import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey

var defaultPort = 8080
var defaultTimeout = 100L
var defaultDiscovery = true
var defaultLogger = fun(ex: Exception){ ex.message?.let(::println) }
var defaultBootstrap = mutableListOf<String>()

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
    val connectionsByTarget get() =
        readyConnections.associateBy{it.targetName}
    fun getConnection(target: String) = connectionsByTarget[target]

    fun accept() = run {server.accept()}
    fun connect(host: String) = run {Socket(host, port)}
    fun connect(hosts: List<String>) = hosts.forEach(::connect)

    fun log(ex: Exception) = logger(ex)

    // Run a connection
    private fun run(getter: () -> Socket) = Thread{
        var connection: Connection? = null
        try{
            connection = Connection(this, getter())
            this.connections += connection
            this.onConnect.forEach{it(connection)}
            connection.onReady {
                this.onReady.forEach{it(connection)}
            }
            connection.onMessage { msg ->
                this.onMessage.forEach{it(connection, msg)}
            }
            if(discovery) connection.discover()
            connection.run()
        }
        catch(ex: Exception){ log(ex) }
        finally {
            connection ?: return@Thread
            connection.socket.close()
            connection.interrupt()
            connections.remove(connection)
            onDisconnect.forEach{it(connection)}
        }
    }.start()

    // Listeners
    private val onConnect = mutableListOf<Connection.() -> Unit>()
    fun onConnect(listener: Connection.() -> Unit){ onConnect += listener }

    private val onDisconnect = mutableListOf<Connection.() -> Unit>()
    fun onDisconnect(listener: Connection.() -> Unit){ onDisconnect += listener}

    private val onReady = mutableListOf<Connection.() -> Unit>()
    fun onReady(listener: Connection.() -> Unit){ onReady += listener }

    private val onMessage = mutableListOf<Connection.(jsonMap) -> Unit>()
    fun onMessage(listener: Connection.(jsonMap) -> Unit){ onMessage += listener }

    val peers get() = readyConnections.map { it.socket.remoteSocketAddress.toString() }
    fun Connection.discover() {
        onReady {
            msg("Discover", jsonMap("peers" to peers))
        }
        onMessage { msg ->
            if(msg["channel"] == "discover"){
                val peers = msg["peers"] as? List<String>
                ?: throw Exception("Peers is not list of string")
                connect(peers)
            }
        }
    }
}

class Connection(val parent: MultiSocket, val socket: Socket){

    val thread = Thread.currentThread()
    fun interrupt() = thread.interrupt()

    var timeout = parent.timeout

    lateinit var targetName: String private set
    val selfName = parent.name

    private val reader = socket.getInputStream().bufferedReader()
    private val writer = PrintWriter(socket.getOutputStream())

    private lateinit var targetKey: SecretKey
    private val selfKey = AES.generate()

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

    var ready = false; private set

    private val onReady = mutableListOf<() -> Unit>()
    fun onReady(listener: () -> Unit) { onReady += listener }

    private val onMessage = mutableListOf<(jsonMap) -> Unit>()
    fun onMessage(listener: (jsonMap) -> Unit) { onMessage += listener }

    internal fun run() {

        msg("Sockets", jsonMap(
            "status", "pending",
            "key", AES.toString(selfKey),
            "name", selfName
        ))

        while(true){
            Thread.sleep(timeout)

            val msg = read()

            if(ready){
                onMessage.forEach{it(msg)}
                continue
            }

            if(msg["channel"] != "Sockets")
            throw Exception("Unexpected message: $msg")

            val status = msg["status"] as? String
            ?: throw Exception("Unexpected message: $msg")

            if(status == "ready"){
                ready = true
                onReady.forEach{it()}
                continue
            }

            if(status == "pending"){
                val key = msg["key"] as? String
                ?: throw Exception("AES is not a string")
                targetKey = AES.toKey(key)

                targetName = msg["name"] as? String
                ?: throw Exception("Name is not a string")

                msg("Sockets", jsonMap("status", "ready"))
                continue
            }

            throw Exception("Unexpected status: $status")
        }
    }
}