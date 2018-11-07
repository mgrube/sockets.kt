package fr.rhaz.sockets

fun main(args: Array<String>){
    val name = args.elementAtOrNull(0)?.toLowerCase()
    when(name){
        "bob" -> bob()
        "alice" -> alice()
        "dave" -> dave()
        else -> return println("Available names: bob, alice, dave")
    }
    println("Successfully started")
}

fun bob(){
    val bob = multiSocket(
        name = "Bob", port = 8080,
        bootstrap = listOf("localhost:8081")
    )

    bob.onReady(target = "Alice"){
        msg("MyChannel", "What is the answer to life?")
        this.onMessage(channel = "MyChannel"){ msg ->
            val data = msg["data"] as? String
            println("The answer to life is: $data")
        }
    }

    bob.accept(true)
}

fun alice(){
    val alice = multiSocket(
        name = "Alice", port = 8081,
        bootstrap = listOf("localhost:8080")
    )

    alice.onMessage(channel = "MyChannel") { msg ->
        if(msg["data"] == "What is the answer to life?")
            msg("MyChannel", "42")
    }

    alice.accept(true)
}

fun dave(){
    val dave = multiSocket(
        name = "Dave", port = 8082,
        bootstrap = listOf("localhost:8080")
    )

    dave.onReady(target = "Bob"){
        println("Connected to Bob")
    }

    dave.onReady(target = "Alice"){
        println("Connected to Alice")
        msg("MyChannel", "What is the answer to life?")
    }

    dave.accept(true)
}