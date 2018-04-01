package fr.rhaz.sockets.client;

import java.util.Map;

public interface SocketClientApp {
	public void log(String err);

	public void onConnect(SocketClient client);

	public void onDisconnect(SocketClient client);

	public void onHandshake(SocketClient client);

	public void onJSON(SocketClient client, Map<String, String> map);
	
	public void run(SocketClient client);
}