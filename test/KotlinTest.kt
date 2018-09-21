import fr.rhaz.sockets.*

fun main(args: Array<String>){
    when(args[0]){
        "server" -> SocketServer(MyServerApp, "MyServer", 800, "mypassword").start()
        "client" -> SocketClient(MyClientApp, "MyClient", "localhost", 800, "mypassword").start()
        "both" -> {
            SocketServer(MyServerApp, "MyServer", 800, "mypassword").start()
            SocketClient(MyClientApp, "MyClient", "localhost", 800, "mypassword").start()
        }
    }
}

object MyServerApp: SocketApp.Server() {

    // Start the conversation
    override fun onHandshake(mess: SocketMessenger, name: String) {
        mess.write("MyChannel", "Hello world!")
    }

    override fun onMessage(mess: SocketMessenger, map: JSONMap) {

        val name = map.getExtra<String>("name")
        val channel = map.getExtra<String>("channel")
        val message = map.getExtra<String>("data")
        println("$name ($channel): $message")

        if(channel == "MyChannel" && message == "It works!")
            mess.write("AnotherChannel", "What is the answer to life?")
    }
}

object MyClientApp: SocketApp.Client(){

    override fun onMessage(client: SocketClient, map: JSONMap) {

        val name = map.getExtra<String>("name")
        val channel = map.getExtra<String>("channel")
        val message = map.getExtra<String>("data") ?: return
        println("$channel: $message")

        if(channel == "MyChannel" && message == "Hello world!")
            client.write("MyChannel", "It works!")

        if(channel == "AnotherChannel" && "answer to life" in message)
            client.write("AnotherChannel", "42")
    }
}