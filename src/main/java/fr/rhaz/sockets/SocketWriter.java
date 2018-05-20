package fr.rhaz.sockets;

import java.io.IOException;
import java.util.Map;

public interface SocketWriter {
	public void write(String data);
	public void writeJSON(String channel, String data);
	public void writeJSON(String channel, Map<String,Object> data);
	public IOException close();
	public boolean isEnabled();
	public boolean isHandshaked();
	public boolean isConnectedAndOpened();
	public String getName();
}
