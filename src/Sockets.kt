package fr.rhaz.sockets

import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey

open class MultiSocket(val name: String, val port: Int) {

    // Changing timeout won't affect current connections
    var timeout = 100L

    val server = ServerSocket(port)

    private val connections = mutableListOf<Connection>()
    val readyConnections get() = connections.filter{it.ready}
    val connectionsByTarget get()
        = readyConnections.associateBy{it.targetName}
    fun getConnection(target: String) = connectionsByTarget[target]

    fun accept() = run {server.accept()}
    fun connect(host: String) = run {Socket(host, port)}

    val log = fun(ex: Exception){ ex.message?.let(::println) }

    // Run a connection
    private fun run(getter: () -> Socket) = Thread{
        var connection: Connection? = null
        try{
            connection = Connection(this, getter())
            connections += connection
            onConnect.forEach{it(connection)}
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
    val onConnect = mutableListOf<Connection.() -> Unit>()
    val onDisconnect = mutableListOf<Connection.() -> Unit>()

}

class Connection(val parent: MultiSocket, val socket: Socket){

    var thread = Thread.currentThread()
    fun interrupt() = thread.interrupt()

    var timeout = parent.timeout

    lateinit var targetName: String private set
    val selfName = parent.name

    val reader = socket.getInputStream().bufferedReader()
    val writer = PrintWriter(socket.getOutputStream())

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
    val onReady = mutableListOf<() -> Unit>()
    val onMessage = mutableListOf<(jsonMap) -> Unit>()

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

            if("status" in msg.keys){

                val status = msg["status"] as? String
                ?: throw Exception("Status is not a string")

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

            throw Exception("Unexpected message: $msg")
        }
    }
}