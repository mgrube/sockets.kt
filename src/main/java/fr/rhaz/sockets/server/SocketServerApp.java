package fr.rhaz.sockets.server;

import java.util.Map;

public interface SocketServerApp {
	public void log(String err);

	public void onConnect(SocketMessenger mess);

	public void onHandshake(SocketMessenger mess, String name);

	public void onJSON(SocketMessenger mess, Map<String, String> map);

	public void onDisconnect(SocketMessenger mess);

	public void run(SocketMessenger mess);

	public void run(SocketServer server);
}