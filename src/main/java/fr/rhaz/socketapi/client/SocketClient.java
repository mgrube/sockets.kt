package fr.rhaz.socketapi.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import com.google.gson.JsonSyntaxException;

import fr.rhaz.socketapi.SocketAPI;
import fr.rhaz.socketapi.utils.AES;
import fr.rhaz.socketapi.utils.RSA;

public class SocketClient implements Runnable {
	private boolean enabled = true;
	private boolean handshaked = false;
	private Data Data = new Data();
	private Security Security = new Security();
	private IO IO = new IO();

	public class Data {
		private String name;
		private String host;
		private int port;
		private Socket socket;
		private SocketClientApp app;

		public void set(String name, String host, int port, Socket socket, SocketClientApp app) {
			Data.name = name;
			Data.host = host;
			Data.port = port;
			Data.socket = socket;
			Data.app = app;
		}
	}

	public class IO {
		private BufferedReader reader;
		private PrintWriter writer;

		public void set(Socket socket) throws IOException {
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			writer = new PrintWriter(socket.getOutputStream());
		}
	}

	public class Security {
		private int level; // 0 = no security; 1 = AES encryption (b64 key sent); 2 = AES encryption, RSA
							// handshake (RSA used for sending AES key)
		private Target Target = new Target();
		private Self Self = new Self();

		private class Target {
			private PublicKey RSA;
			private SecretKey AES;
		}

		private class Self {
			private KeyPair RSA;
			private SecretKey AES;
		}

		public void reset() {
			Target.AES = null;
			Target.RSA = null;
			Self.AES = AES.generateKey();
			Self.RSA = RSA.generateKeys();
		}
	}

	public SocketClient(SocketClientApp app, String name, String host, int port, int security) {
		Data.set(name, host, port, new Socket(), app);
		Security.level = security;
		enabled = true;
	}

	public void run() {
		while (enabled) {
			try {
				Data.socket = new Socket(Data.host, Data.port); // Connection
				Data.socket.setTcpNoDelay(true); // Socket option

				Data.app.onConnect(this); // Trigger onConnect event
				Security.reset(); // Reset security data
				IO.set(Data.socket); // Open socket streams
				handshaked = false; // Default not handshaked

				String RSA_key = "";
				String AES_key = "";
				String message = "";
				while (enabled && isConnectedAndOpened()) {
					String read = IO.reader.readLine();
					if (read == null)
						close(); // If end of stream, close it

					else { // This isn't the end of stream, continue

						if (Security.level >= 2 && Security.Target.RSA == null) { // Is RSA encryption enabled?
																					// Do we have received the
																					// RSA key?

							if (!read.equals("--end--"))
								RSA_key += read; // The message is not fully received, continue

							else { // Yay, we received the full message, convert it to PublicKey object
								Security.Target.RSA = RSA.loadPublicKey(RSA_key); // Done

								// Now we need to send our RSA key
								IO.writer.println(RSA.savePublicKey(Security.Self.RSA.getPublic()));
								IO.writer.println("--end--");
								IO.writer.flush();
							}

						} else if (Security.level >= 1 && Security.Target.AES == null) {

							if (!read.equals("--end--"))
								AES_key += read;

							else {
								if (Security.level == 1) {
									Data.app.log("Target AES: " + AES_key);
									Security.Target.AES = AES.toKey(AES_key);
									Data.app.log("Self AES: " + AES.toString(Security.Self.AES));
									IO.writer.println(AES.toString(Security.Self.AES));
									IO.writer.println("--end--");
									IO.writer.flush();
								}
								if (Security.level == 2) {
									Security.Target.AES = AES
											.toKey(RSA.decrypt(AES_key, Security.Self.RSA.getPrivate()));
									IO.writer.println(RSA.encrypt(AES.toString(Security.Self.AES),
											Security.Target.RSA));
									IO.writer.println("--end--");
									IO.writer.flush();
								}
							}

						} else { // We have received the RSA key
							String decrypted = "";
							if (Security.level == 0)
								decrypted = read;
							if (Security.level >= 1)
								decrypted = AES.decrypt(read, Security.Self.AES);
							Data.app.log("<- " + read);
							Data.app.log("<- (" + decrypted + ")");
							if (decrypted != null && !decrypted.isEmpty()) {
								if (!decrypted.equals("--end--"))
									message += decrypted;
								else {
									if (message != null && !message.isEmpty()) {
										try {
											@SuppressWarnings("unchecked")
											Map<String, String> map = SocketAPI.gson().fromJson(message,
													Map.class);
											if (map.get("channel").equals("SocketAPI")) {
												if (map.get("data").equals("handshake")) {
													writeJSON("SocketAPI", "handshake");
												} else if (map.get("data").equals("handshaked")) {
													handshaked = true;
													Data.app.onHandshake(this);
												}
											} else
												Data.app.onJSON(this, map);
										} catch (JsonSyntaxException e) {
										}
									}
									message = "";
								}
							}
						}
					}
				}
			} catch (Exception e) {
				if (e.getClass().getSimpleName().equals("SocketException"))
					close();
			}
		}
	}

	public int getPort() {
		return Data.port;
	}

	public String getHost() {
		return Data.host;
	}

	public Socket getSocket() {
		return Data.socket;
	}
	
	public String getName() {
		return Data.name;
	}
	
	public int getSecurityLevel() {
		return Security.level;
	}

	public boolean isConnectedAndOpened() {
		return Data.socket.isConnected() && !Data.socket.isClosed();
	}

	public boolean isHandshaked() {
		return handshaked;
	}

	public void writeJSON(String channel, String data) {
		try {
			HashMap<String, String> hashmap = new HashMap<>();
			hashmap.put("channel", channel);
			hashmap.put("data", data);
			hashmap.put("name", Data.name);
			String json = SocketAPI.gson().toJson(hashmap);
			write(json);
		} catch (NullPointerException e) {
		}
	}

	private void write(String data) {
		try {
			String[] split = SocketAPI.split(data, 20);
			if (Security.level == 0) {
				for (String str : split)
					IO.writer.println(str);
				IO.writer.println("--end--");
			}
			if (Security.level >= 1) {
				for (String str : split) {
					Data.app.log("-> " + AES.encrypt(str, Security.Target.AES));
					IO.writer.println(AES.encrypt(str, Security.Target.AES));
				}
				Data.app.log("-> " + AES.encrypt("--end--", Security.Target.AES));
				IO.writer.println(AES.encrypt("--end--", Security.Target.AES));
			}
			IO.writer.flush();
		} catch (NullPointerException e) {
		}
	}

	public IOException close() {
		if (!Data.socket.isClosed()) {
			try {
				Data.socket.close();
				Data.app.onDisconnect(this);
			} catch (IOException e) {
				return e;
			}
		}
		return null;
	}

	public IOException interrupt() {
		enabled = false;
		return close();
	}

	public boolean isEnabled() {
		return enabled;
	}
}