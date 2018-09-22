import fr.rhaz.sockets.*;

public class JavaTest {

    public static void main(String [] args){
        switch(args[0]){
            case "server":{
                new SocketServer(myServerApp, "MyServer", 800, "mypassword").start();
                break;
            }
            case "client":{
                new SocketClient(myClientApp, "MyClient", "localhost", 800, "mypasword").start();
                break;
            }
            case "both":{
                new SocketServer(myServerApp, "MyServer", 800, "mypassword").start();
                new SocketClient(myClientApp, "MyClient", "localhost", 800, "mypasword").start();
                break;
            }
        }
    }

    public static SocketApp.Server myServerApp = new SocketApp.Server(){

        // Start the conversation
        public void onHandshake(SocketMessenger mess, String name) {
            mess.write("MyChannel", "Hello world!");
        }

        public void onMessage(SocketMessenger mess, JSONMap map) {

            String name = mess.getTarget().getName();

            String channel = map.getChannel();

            String message = map.getExtra("data");
            if(message == null) return;

            System.out.println(name+" ("+channel+"): "+message);

            if(channel.equals("MyChannel") && message.equals("It works!"))
                mess.write("AnotherChannel", "What is the answer to life?");
        }
    };

    public static SocketApp.Client myClientApp = new SocketApp.Client() {
        public void onMessage(SocketClient client, JSONMap map) {

            String name = client.getTarget().getName();

            String channel = map.getChannel();

            String message = map.getExtra("data");
            if(message == null) return;

            System.out.println(name+" ("+channel+"): "+message);

            if(channel.equals("MyChannel") && message.equals("Hello world!"))
                client.write("MyChannel", "It works!");

            if(channel.equals("AnotherChannel") && message.contains("answer to life"))
                client.write("AnotherChannel", "42");
        }
    };
}
