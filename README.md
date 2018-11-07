<h3 align=center>
    <img src="https://i.imgur.com/7SRZq5u.jpg"/><br>
</h3>
<br>

### Short example

    val bob = multiSocket(
        name = "Bob", port = 8080,
        bootstrap = listOf("localhost:8081")
    )
    bob.onReady{
        msg("MyChannel", "What is the answer to life?")
    }
    bob.accept(true)

    val alice = multiSocket(
        name = "Alice", port = 8081,
        bootstrap = listOf("localhost:8080")
    )
    alice.onMessage( msg ->
        if(msg["channel"]
    )

### Use it

- [Example in Kotlin](https://github.com/RHazDev/RHazSockets/blob/master/test/KotlinTest.kt)

- [Example in Java](https://github.com/RHazDev/RHazSockets/blob/master/test/JavaTest.java)

### Implement it

- Kotlin DSL: add this to your build.gradle.kts

      repositories {
          maven { url = URI("https://mymavenrepo.com/repo/NIp3fBk55f5oF6VI1Wso/")}
      }

      dependencies {
          compile("fr.rhaz:sockets:3.0.8")
      }

- Gradle: add this to your build.gradle

      repositories {
          maven { url 'https://mymavenrepo.com/repo/NIp3fBk55f5oF6VI1Wso/' }
      }

      dependencies {
          compile 'fr.rhaz:sockets:3.0.8'
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
            <version>3.0.8</version>
            <scope>compile</scope>
        </dependency>
      </dependencies>
