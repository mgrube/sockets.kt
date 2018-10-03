package fr.rhaz.sockets

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.util.*
import javax.crypto.SecretKey

class SocketClient(
    val app: SocketApp.Client,
    val name: String,
    val host: String,
    val port: Int,
    val password: String
) : Runnable, SocketWriter, SocketHandler {

    val mRSA = Message() ; val mAES = Message()
    val messages = HashMap<String, Message>()

    override var running = true
    override var handshaked = false
    override val ready get() = ::socket.isInitialized && socket.isConnected && !socket.isClosed

    lateinit var socket: Socket

    lateinit var io: IO
    open inner class IO{
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream())
    }

    val reader get() = io.reader
    val writer get() = io.writer

    var config = Config()
    open class Config{
        open var buffer = 20
        open var timeout: Long = 100
    }


    var self = Self()
    open class Self{
        var rsa: KeyPair = RSA.generate()
        var aes: SecretKey = AES.generate()
    }

    lateinit var target: Target
    open class Target{
        var security = 1
        lateinit var name: String
        var rsa: PublicKey? = null
        var aes: SecretKey? = null
    }

    fun start() = app.run(this)

    override fun run() {
        while(running) try {
            target = Target()
            handshaked = false

            socket = Socket(host, port).apply {
                tcpNoDelay = true
                setPerformancePreferences(0, 0, 2)
            }

            io = IO()
            app.onConnect(this)
            interact()
        } catch (e: IOException) { close() }
    }

    fun interact() = try {
        app.log("$name running")
        while (running && ready) run loop@{

            Thread.sleep(config.timeout) // Wait 100 milliseconds before reading another line

            // Read the line
            var read = reader.readLine() ?: return {close(); Unit}()

            if (read == "1") return@loop {target.security = 1}()
            if (read == "2") return@loop {target.security = 2}()

            // Is RSA enabled? Do we received the RSA key?
            if (target.security == 2 && target.rsa == null){

                app.log("$name <--- $read")

                // Is the message fully received?
                if (read != "--end--")
                    return@loop mRSA.add(read)

                target.rsa = RSA.loadPublicKey(mRSA.egr())

                writer.apply {
                    println(RSA.savePublicKey(self.rsa.public))
                    println("--end--")
                    flush()
                }
            }

            // Is AES enabled? Do we already received AES?
            else if(target.security >= 1 && target.aes == null){

                app.log("$name <--- $read")

                // Is it the end of the key?
                if (read != "--end--")
                    return@loop mAES.add(read)

                mAES.egr().let {
                    if(target.security == 1) it
                    else RSA.decrypt(it, self.rsa.private) ?: return@loop
                }.also { target.aes = AES.toKey(it) }

                // Now we send our AES key encrypted with server RSA
                val aes = AES.toString(self.aes).let {
                    if(target.security == 1) return@let it
                    val rsa = target.rsa ?: return@loop
                    RSA.encrypt(it, rsa)
                }

                writer.apply {
                    println(aes.also { app.log("$name --> $it") })
                    println("--end--")
                    flush()
                }
            }

            else{

                // Is the line encrypted in AES?
                if (target.security >= 1)
                    read = AES.decrypt(read, self.aes) ?: return@loop app.log("$name could not decrypt")

                app.log("$name <--- $read")

                if (read.isEmpty()) return@loop

                val split = read.split("#")
                if (split.size < 2) return@loop

                val id = split[0]
                val data = read.substring(id.length + 1)

                val message = messages[id] ?: Message().also { messages[id] = it }

                // Is message fully received?
                if (data != "--end--")
                    return@loop message.add(data)

                messages.remove(id)

                // Convert message to an object
                val map = message.emr()

                // Send the object to the app
                val msg: ()->Unit = {app.onMessage(this, JSONMap(map))}

                // Is it our channel?
                if(map["channel"] != "SocketAPI") return@loop msg()

                // Is the message a handshake?
                if (map["data"] == "handshake")
                    return@loop write("SocketAPI", JSONMap(
                        "data", "handshake",
                        "name", name
                    ))

                if (map["data"] != "handshaked")
                    return@loop msg()

                target.name = map["name"] as? String ?: return@loop
                handshaked = true
                app.log("$name handshaked")
                app.onHandshake(this)
            }
        }
    } catch (ex: Exception) {
        when (ex) {
            is IOException,
            is GeneralSecurityException,
            is InterruptedException
            -> close()?.let { throw it }
            else -> throw ex
        }
    }

    override fun write(channel: String, data: String) =
        write(channel, JSONMap("data", data))

    override fun write(channel: String, data: JSONMap) {
        data["channel"] = channel
        data["password"] = password
        write(gson.toJson(data))
    }

    override fun write(data: String) = app.run {
        app.log("$name ---> $data")

        val id = Random().nextInt(1000)
        val split = split(data, config.buffer)

        for (str in split)
            "$id#$str".let {
                if(target.security == 0) return@let it
                val aes = target.aes ?: return
                AES.encrypt(it, aes)
            }.also { writer.println(it) }

        "$id#--end--".let {
            if(target.security == 0) return@let it
            val aes = target.aes ?: return
            AES.encrypt(it, aes)
        }.also { writer.println(it) }

        writer.flush()
    }

    override fun close(): IOException? = try {
        if(ready){
            handshaked = false
            socket.close()
            app.onDisconnect(this)
        }
        null
    } catch (ex: IOException) {ex}

    fun interrupt(): IOException? = close().also { running = false }

}

