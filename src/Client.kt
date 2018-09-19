package fr.rhaz.sockets

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.util.*
import javax.crypto.SecretKey

abstract class SocketClientApp {
    fun log(err: String) {}
    fun onConnect(client: SocketClient) {}
    fun onDisconnect(client: SocketClient) {}
    fun onHandshake(client: SocketClient) {}
    abstract fun onMessage(client: SocketClient, map: JSONMap)
    fun run(runnable: Runnable) = Thread(runnable).start()
}

class SocketClient(
    val app: SocketClientApp,
    val name: String,
    val host: String,
    val port: Int,
    val password: String
) : Runnable, SocketWriter {

    val mRSA = Message() ; val mAES = Message()
    val messages = HashMap<String, Message>()

    override var running = true
    override var handshaked = false
    override val ready get() = socket.isConnected && !socket.isClosed

    lateinit var socket: Socket
    val reader get() = BufferedReader(InputStreamReader(socket.getInputStream()))
    val writer get() = PrintWriter(socket.getOutputStream())

    var config = Config()
    open class Config{
        var buffer = 20
        var timeout: Long = 100
    }

    val buffer get() = config.buffer
    val timeout get() = config.timeout

    var security = -1

    var self = Self()
    open class Self{
        var rsa: KeyPair = RSA.generate()
        var aes: SecretKey = AES.generate()
    }

    var target = Target()
    open class Target{
        var rsa: PublicKey? = null
        var aes: SecretKey? = null
    }

    fun start() = app.run(this)

    override fun run() {
        while(running) try {
            socket = Socket(host, port).apply {
                tcpNoDelay = true
                setPerformancePreferences(0, 1, 2)
            }

            app.onConnect(this)
            self = Self() ; target = Target()
            handshaked = false
            interact()
        } catch (e: IOException) { close() }
    }

    fun interact() = try {
        val close: ()->Unit = {close()}
        while (running && ready) run loop@{

            Thread.sleep(timeout) // Wait 100 milliseconds before reading another line

            // Read the line
            var read = reader.readLine() ?: return close()

            if (read == "1") return@loop {security = 1}()
            if (read == "2") return@loop {security = 2}()

            // Is RSA enabled? Do we received the RSA key?
            if (security == 2 && target.rsa == null){

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
            else if(security >= 1 && target.aes == null){

                // Is it the end of the key?
                if (read != "--end--")
                    return@loop mAES.add(read)

                mAES.egr().let {
                    if(security == 1) it
                    else RSA.decrypt(it, self.rsa.private) ?: return@loop
                }.also { target.aes = AES.toKey(it) }

                // Now we send our AES key encrypted with server RSA
                val aes = AES.toString(self.aes).let {
                    if(security == 1) return@let it
                    val rsa = target.rsa ?: return@loop
                    RSA.encrypt(it, rsa)
                }

                writer.apply {
                    println(aes)
                    println("--end--")
                    flush()
                }
            }

            else{

                // Is the line encrypted in AES?
                if (security >= 1)
                    read = AES.decrypt(read, self.aes) ?: return@loop

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
                    return@loop write("SocketAPI", "handshake")

                if (map["data"] != "handshaked")
                    return@loop msg()

                handshaked = true
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

    override fun write(channel: String, data: JSONMap) = try {
        data["name"] = name
        data["channel"] = channel
        data["password"] = password
        write(gson.toJson(data))
    } catch (ex: NullPointerException) {}

    @Synchronized
    override fun write(data: String){
        try {

            val aes = target.aes ?: return

            val id = Random().nextInt(1000)
            val split = split(data, buffer)

            for (str in split)
                "$id#$str".let {
                    if(security == 0) it
                    else AES.encrypt(it, aes)
                }.also { writer.println(it) }

            "$id#--end--".let {
                if(security == 0) it
                else AES.encrypt(it, aes)
            }.also { writer.println(it) }

            writer.flush()
            Thread.sleep(timeout)

        }
        catch (e: NullPointerException) { }
        catch (e: InterruptedException) { }
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

