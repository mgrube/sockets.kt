<h3 align=center>
    <img src="https://i.imgur.com/7SRZq5u.jpg"/><br>
</h3>
<br>

### Short example

We will use 3 sockets: Bob, Alice and Dave

```kotlin
// ---------- Bob ----------

// Bob will ask some question to Alice
// and print the response

// Create a socket on port 8080
// and try to connect to port 8081 (Alice)
val bob = multiSocket(
    name = "Bob", port = 8080,
    bootstrap = listOf("localhost:8081")
)

// When the connection to Alice is ready
bob.onReady(target = "Alice"){

    // Ask question to Alice on channel "MyChannel"
    msg("MyChannel", "What is the answer to life?")

    // When a message is received over this connection
    // and on the channel "MyChannel"
    this.onMessage(channel = "MyChannel"){ msg ->
        // Retrieve message data
        val data = msg["data"] as? String
        println("The answer to life is: $data")
    }
}

// Accept incoming connections
// To accept only one connection: bob.accept()
// To accept multiple connections: bob.accept(loop = true)
bob.accept(true)

// ---------- Alice ----------

// Alice will receive questions and will answer

// Create a socket on port 8081
// and try to connect to 8080 (Bob)
val alice = multiSocket(
    name = "Alice", port = 8081,
    bootstrap = listOf("localhost:8080")
)

// When a message is received on channel "MyChannel"
// (no matter the connection)
alice.onMessage(channel = "MyChannel") { msg ->
    if(msg["data"] == "What is the answer to life?")
    msg("MyChannel", "42")
}

// Accept incoming connections
alice.accept(true)

// ---------- Dave ----------

// By connecting to Bob, Dave will
// automatically connect to Alice.
// This feature is called discovery:
// peers automatically find each other.
// You can disable it by putting
// "discovery = false" in multiSocket()

// Create a socket on port 8082
// and try to connect to 8080 (Bob)
val dave = multiSocket(
    name = "Dave", port = 8082,
    bootstrap = listOf("localhost:8080")
)

dave.onReady(target = "Bob"){
    println("Connected to Bob")
}

dave.onReady(target = "Alice"){
    println("Connected to Alice")
    // msg("MyChannel", "What is the answer to life?")
}

dave.accept(true)
```

### Implement it

- Kotlin DSL: add this to your build.gradle.kts

      repositories {
          maven { url = URI("https://mymavenrepo.com/repo/NIp3fBk55f5oF6VI1Wso/")}
      }

      dependencies {
          compile("fr.rhaz:sockets:4.0.1")
      }

- Gradle: add this to your build.gradle

      repositories {
          maven { url 'https://mymavenrepo.com/repo/NIp3fBk55f5oF6VI1Wso/' }
      }

      dependencies {
          compile 'fr.rhaz:sockets:4.0.1'
      }


- Maven: add this to your pom.xml

      <repositories>
        <repository>
            <id>rhazdev</id>
            <url>https://mymavenrepo.com/repo/NIp3fBk55f5oF6VI1Wso/</url>
        </repository>
      </repositories>

      <dependencies>
        <dependency>
            <groupId>fr.rhaz</groupId>
            <artifactId>sockets</artifactId>
            <version>4.0.1</version>
            <scope>compile</scope>
        </dependency>
      </dependencies>
