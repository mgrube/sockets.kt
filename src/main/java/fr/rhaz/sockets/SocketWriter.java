package fr.rhaz.sockets;

import java.util.Map;

public interface SocketWriter {
	public void write(String data);
	public void writeJSON(String channel, String data);
	public void writeJSON(String channel, Map<String,Object> data);
}
