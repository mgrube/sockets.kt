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

abstract class SocketServerApp {
    fun log(err: String) {}
    fun onConnect(mess: SocketMessenger) {}
    fun onDisconnect(mess: SocketMessenger) {}
    fun onHandshake(mess: SocketMessenger, name: String) {}
    abstract fun onMessage(mess: SocketMessenger, map: JSONMap)
    fun run(runnable: Runnable) = Thread(runnable).start()
}

open class SocketServer(val app: SocketServerApp, val name: String, val port: Int, val password: String) : Runnable {

    lateinit var server: ServerSocket
    lateinit var messengers: MutableList<SocketMessenger>

    val running = server.isClosed

    var config = Config()
    open class Config{
        var security = 1
        var buffer = 20
        var timeout: Long = 100
    }

    fun start(): IOException? = try {
        server = ServerSocket(port)
        app.run(this)
        null
    } catch (e: IOException){e }

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
            } catch (e: IOException) {}

            try {
                Thread.sleep(config.timeout)
            } catch (ex: InterruptedException) {
                println(ex.message); break
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

    val config get() = server.config
    val security get() = config.security
    val timeout get() = config.timeout

    var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    var writer = PrintWriter(socket.getOutputStream())

    val target = Target()
    open class Target {
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
                    println(AES.toString(self.aes))
                    println("--end--")
                    flush()
                }
                2 -> writer.apply{
                    println("2")
                    println(RSA.savePublicKey(self.rsa.public))
                    println("--end--")
                    flush()
                }
            }
        } catch (e: Exception) {}
    }

    override fun run(): Unit = try{
        val close: ()->Unit = {close()}
        while(running && ready) run loop@{ // While connected

            // Wait before reading another line
            Thread.sleep(timeout)

            val reader = reader ?: return close()

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
                    read = AES.decrypt(read, self.aes) ?: return@loop

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

                handshaked = true
                server.app.onHandshake(this, name)
                write("SocketAPI", "handshaked")
            }

        }

    } catch (ex: Exception) {
        when(ex){
            is IOException,
            is GeneralSecurityException,
            is InterruptedException
            -> Unit.also{ close()?.let { throw it } }
            else -> throw ex
        }
    }

    override fun write(channel: String, data: String) =
        write(channel, JSONMap("data", data))

    override fun write(channel: String, data: JSONMap) = try {
        data["name"] = server.name
        data["channel"] = channel
        write(gson.toJson(data))
    } catch(ex: NullPointerException) {}

    @Synchronized
    override fun write(data: String) {
        try {

            val aes = target.aes ?: return

            val id = Random().nextInt(1000)
            val split = split(data, config.buffer)

            for (str in split)
                "$id#$str".let {
                    if (security == 1) it
                    else AES.encrypt(it, aes) ?: return
                }.also { writer.println(it) }

            "$id#--end--".let {
                if(security == 1) it
                else AES.encrypt(it, aes) ?: return
            }.also { writer.println(it) }

            writer.flush()
            Thread.sleep(timeout)
        }
        catch (e: NullPointerException){}
        catch (e: InterruptedException){}
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