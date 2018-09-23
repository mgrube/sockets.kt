package fr.rhaz.sockets

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.PublicKey
import java.util.*
import javax.crypto.SecretKey

open class SocketServer(val app: SocketApp.Server, val name: String, val port: Int, val password: String) : Runnable, SocketHandler {

    lateinit var server: ServerSocket
    var messengers = mutableListOf<SocketMessenger>()

    val running get() = ::server.isInitialized && !server.isClosed

    var config = Config()
    open class Config{
        open var security = 1
        open var buffer = 20
        open var timeout: Long = 100
    }

    fun start(): IOException? = try {
        server = ServerSocket(port)
        app.run(this)
        null
    } catch (ex: IOException){ex}

    override fun run() {
        while(running) {
            try {
                // Accept new connection
                val socket = server.accept().apply {
                    tcpNoDelay = true
                    setPerformancePreferences(0, 1, 2)
                }

                // Create a new messenger for this socket
                SocketMessenger(this, socket).also {
                    messengers.add(it)
                    app.onConnect(it)
                    app.run(it)
                }
            } catch (ex: IOException) {ex.message?.let {app.log(it)}; Unit}

            try {
                Thread.sleep(config.timeout)
            } catch (ex: InterruptedException) {
                ex.message?.let {app.log(it)}; break
            }
        }
    }

    fun close(): IOException? = try {
        if(running){
            messengers.forEach{close()}
            server.close()
        }
        null
    } catch (ex: IOException) { ex }
}

open class SocketMessenger(var server: SocketServer, var socket: Socket) : Runnable, SocketWriter {

    val mRSA = Message() ; val mAES = Message()

    val messages = HashMap<String, Message>()

    override var handshaked = false
    override val running get() = server.running
    override val ready get() = socket.isConnected && !socket.isClosed

    val name get() = server.name
    val config get() = server.config
    val security get() = config.security
    val timeout get() = config.timeout

    var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    var writer = PrintWriter(socket.getOutputStream())

    val target = Target()
    open class Target {
        lateinit var name: String
        var rsa: PublicKey? = null
        var aes: SecretKey? = null
    }

    val self = Self()
    open class Self {
        var rsa: KeyPair = RSA.generate()
        var aes: SecretKey = AES.generate()
    }

    init {
        try {
            when(security){
                0 -> write("SocketAPI", "handshake")
                1 -> writer.apply {
                    println("1")
                    println(AES.toString(self.aes)
                        .also { server.app.log("$name --> $it") })
                    println("--end--")
                    flush()
                }
                2 -> writer.apply{
                    println("2")
                    println(RSA.savePublicKey(self.rsa.public)
                        .also { server.app.log("$name --> $it") })
                    println("--end--")
                    flush()
                }
            }
        } catch (ex: Exception) {ex.message?.let {server.app.log(it)}}
    }

    override fun run(): Unit = try{
        val close: ()->Unit = {close()}
        server.app.log("$name running")
        while(running && ready) run loop@{ // While connected

            // Wait before reading another line
            Thread.sleep(timeout)

            // Read the line, if end of stream: close
            var read: String = reader.readLine() ?: return close()

            // Is RSA enabled? Do we received the RSA key?
            if (security == 2 && target.rsa == null){

                // Is the message fully received?
                // Add this line; Read another line
                if (read != "--end--") return@loop mRSA.add(read)

                // Yay, we received the RSA key
                // Let's save it
                val rsa = RSA.loadPublicKey(mRSA.egr())
                    .also { target.rsa = it }

                // Now we send our AES key encrypted with target RSA
                val aes = AES.toString(self.aes)

                writer.apply {
                    println(RSA.encrypt(aes, rsa))
                    println("--end--")
                    flush()
                }
            }

            // Is AES enabled? Do we already received AES?
            else if (security >= 1 && target.aes == null) {

                server.app.log("$name <--- $read")

                // Is it the end of the key?
                if (read != "--end--") return@loop mAES.add(read)

                mAES.egr().let{
                    if(security != 2) return@let it
                    // Received AES key encrypted in RSA
                    RSA.decrypt(it, self.rsa.private) ?: return@loop
                }.also { target.aes = AES.toKey(it) }

                write("SocketAPI", "handshake")
            }

            else {
                // Is the line encrypted in AES?
                if (security >= 1)
                    read = AES.decrypt(read, self.aes) ?: return@loop server.app.log("$name could not decrypt")

                server.app.log("$name <--- $read")

                // If line is null or empty, read another
                if (read.isEmpty()) return@loop

                val split = read.split("#")
                if (split.size < 2) return@loop

                val id = split[0]

                val data = read.substring(id.length + 1)

                val message = messages[id] ?: Message().also{messages[id] = it}

                // Is message fully received?
                if(data != "--end--")
                    return@loop message.add(data)// Add line; Read another line

                messages.remove(id)

                // Convert message to an object
                val map = message.emr()

                if(map["password"] != server.password) return@loop

                // Send the object to the app
                val msg = {server.app.onMessage(this, JSONMap(map))}

                if (map["channel"] != "SocketAPI") return@loop msg()
                if (map["data"] != "handshake") return@loop msg()

                val name = map["name"] as? String ?: return@loop
                target.name = name

                handshaked = true
                server.app.log("$name handshaked")
                server.app.onHandshake(this, name)
                write("SocketAPI", JSONMap(
                    "data", "handshaked",
                    "name", server.name
                ))
            }

        }
    } catch (ex: Exception) {
        when(ex){
            is IOException,
            is GeneralSecurityException,
            is InterruptedException
            -> Unit.also{ close()?.let { it.message?.let {server.app.log(it)}; Unit } }
            else -> {ex.message?.let {server.app.log(it)}; Unit}
        }
    }

    override fun write(channel: String, data: String) =
        write(channel, JSONMap("data", data))

    override fun write(channel: String, data: JSONMap) = try {
        data["channel"] = channel
        write(gson.toJson(data))
    } catch(ex: NullPointerException) {ex.message?.let {server.app.log(it)}; Unit}

    @Synchronized
    override fun write(data: String) {
        try {
            server.app.log("$name ---> $data")

            val id = Random().nextInt(1000)
            val split = split(data, config.buffer)

            for (str in split)
                "$id#$str".let {
                    if (security == 0) return@let it
                    val aes = target.aes ?: return
                    AES.encrypt(it, aes) ?: return
                }.also { writer.println(it) }

            "$id#--end--".let {
                if(security == 0) return@let it
                val aes = target.aes ?: return
                AES.encrypt(it, aes) ?: return
            }.also { writer.println(it) }

            writer.flush()
            Thread.sleep(timeout)
        } catch (ex: InterruptedException){ex.message?.let {server.app.log(it)}}
    }

    override fun close(): IOException? = try {
        if(ready){
            socket.close()
            server.messengers.remove(this)
            server.app.onDisconnect(this)
        }
        null
    } catch(ex: IOException) {ex}
}