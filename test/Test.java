import fr.rhaz.sockets.MultiSocket;
import fr.rhaz.sockets.SocketsKt;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.out;

public class Test {

    public static void main(String[] args){
        if(args.length >= 1){
            String name = args[0].toLowerCase();
            switch (name) {
                case "bob": bob(); break;
                case "alice": alice(); break;
                case "dave": dave(); break;
                default:
                    out.println("Available names: bob, alice, dave");
                    return;
            }
            out.println("Successfully started");
        }
        else out.println("Available names: bob, alice, dave");
    }

    public static void bob(){
        List<String> bootstrap = new ArrayList<>();
        bootstrap.add("localhost:8081");
        MultiSocket bob = SocketsKt.multiSocket("Bob", 8080, bootstrap, "password");
        bob.onReady("Alice", connection -> {
            connection.msg("MyChannel", "What is the answer to life?");
            connection.onMessage("MyChannel", (connection1, msg) -> {
                String data = (String) msg.get("data");
                out.println("The answer to life is: "+data);
            });
        });
        bob.accept(true);
    }

    public static void alice(){
        List<String> bootstrap = new ArrayList<>();
        bootstrap.add("localhost:8080");
        MultiSocket alice = SocketsKt.multiSocket("Alice", 8081, bootstrap);
        alice.onMessage("MyChannel", (connection, msg) -> {
            if(msg.get("data").equals("What is the answer to life?"))
            connection.msg("MyChannel", "42");
        });
        alice.accept(true);
    }

    public static void dave(){
        List<String> bootstrap = new ArrayList<>();
        bootstrap.add("localhost:8080");
        MultiSocket dave = SocketsKt.multiSocket("Dave", 8082, bootstrap);
        dave.onReady("Bob", connection -> {
            out.println("Connected to Bob");
        });
        dave.onReady("Alice", connection -> {
            out.println("Connected to Alice");
        });
        dave.accept(true);
    }

}
