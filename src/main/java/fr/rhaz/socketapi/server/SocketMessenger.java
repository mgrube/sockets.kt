package fr.rhaz.socketapi.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import com.google.gson.JsonSyntaxException;

import fr.rhaz.socketapi.SocketAPI;
import fr.rhaz.socketapi.utils.AES;
import fr.rhaz.socketapi.utils.RSA;

public class SocketMessenger implements Runnable {
	private AtomicBoolean handshaked = new AtomicBoolean(false);
	private String RSA_key = "";
	private String AES_key = "";
	private String message = "";
	private Data Data = new Data();
	private Security Security = new Security();
	private IO IO = new IO();

	public class Data {
		private String name;
		private Socket socket;
		private SocketServer server;
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

	public class IO {
		private BufferedReader reader;
		private PrintWriter writer;

		public void set(Socket socket) throws IOException {
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			writer = new PrintWriter(socket.getOutputStream());
		}
	}

	public SocketMessenger(SocketServer socketServer, final Socket socket, int security) {
		Data.socket = socket;
		Data.server = socketServer;
		Security.level = security; // Setting security level
		Security.reset(); // Reset security data
		if (Data.server.isEnabled() && isConnectedAndOpened()) {
			try {
				IO.set(socket);
				if (Security.level == 0) {
					writeJSON("SocketAPI", "handshake");
				}
				if (Security.level == 1) {
					Data.server.Data.app.log("Self AES: " + AES.toString(Security.Self.AES));
					IO.writer.println(AES.toString(Security.Self.AES));
					IO.writer.println("--end--");
					IO.writer.flush();
				}
				if (Security.level == 2) {
					IO.writer.println(RSA.savePublicKey(Security.Self.RSA.getPublic()));
					IO.writer.println("--end--");
					IO.writer.flush();
				}
			} catch (Exception e) {
			}
		}
	}

	@Override
	public void run() {
		while (Data.server.isEnabled() && isConnectedAndOpened()) { // While connected
			try {
				String read = IO.reader.readLine();
				if (read == null)
					close(); // If end of stream, close
				else {
					if (Security.level >= 2 && Security.Target.RSA == null) { // Is RSA enabled? Do we received
																				// the RSA key?

						if (!read.equals("--end--"))
							RSA_key += read; // Is the message fully received?

						else { // Yay, we received the RSA key
							Security.Target.RSA = RSA.loadPublicKey(RSA_key); // Convert it to a PublicKey
																					// object
							// Now we send our AES key encrypted with RSA
							IO.writer.println(RSA.encrypt(AES.toString(Security.Self.AES),
									Security.Target.RSA));
							IO.writer.println("--end--");
							IO.writer.flush();
						}
					} else if (Security.level >= 1 && Security.Target.AES == null) {

						if (!read.equals("--end--"))
							AES_key += read;

						else {
							if (Security.level == 1) {
								Data.server.Data.app.log("Target AES: " + AES_key);
								Security.Target.AES = AES.toKey(AES_key);
							}
							if (Security.level == 2) {
								Security.Target.AES = AES
										.toKey(RSA.decrypt(AES_key, Security.Self.RSA.getPrivate()));
							}
							writeJSON("SocketAPI", "handshake");
						}
					} else {
						String decrypted = "";
						if (Security.level == 0)
							decrypted = read;
						if (Security.level >= 1)
							decrypted = AES.decrypt(read, Security.Self.AES);
						Data.server.Data.app.log("<- " + read);
						Data.server.Data.app.log("<- (" + decrypted + ")");
						if (decrypted != null && !decrypted.isEmpty()) {

							if (!decrypted.equals("--end--"))
								message += decrypted;

							else {
								if (message != null && !message.isEmpty()) {
									try {
										@SuppressWarnings("unchecked")
										Map<String, String> map = SocketAPI.gson().fromJson(message, Map.class);
										if (map.get("channel").equals("SocketAPI")) { // Is it our channel?
											if (map.get("data").equals("handshake")) {
												handshaked.set(true);
												Data.name = map.get("name");
												Data.server.getApp().onHandshake(this, Data.name);
												writeJSON("SocketAPI", "handshaked");
											}
										} else
											Data.server.getApp().onJSON(this, map);
									} catch (JsonSyntaxException e) {
									}
								}
								message = "";
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

	public SocketServer getServer() {
		return Data.server;
	}

	public boolean isConnectedAndOpened() {
		return getSocket().isConnected() && !getSocket().isClosed();
	}

	public boolean isHandshaked() {
		return handshaked.get();
	}

	public String getName() {
		return Data.name;
	}

	public void writeJSON(String channel, String data) {
		try {
			HashMap<String, String> hashmap = new HashMap<>();
			hashmap.put("name", Data.server.Data.name);
			hashmap.put("channel", channel);
			hashmap.put("data", data);
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
					Data.server.Data.app.log("-> " + AES.encrypt(str, Security.Target.AES));
					IO.writer.println(AES.encrypt(str, Security.Target.AES));
				}
				Data.server.Data.app.log("-> " + AES.encrypt("--end--", Security.Target.AES));
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
				Data.server.Data.messengers.remove(this);
				Data.server.getApp().onDisconnect(this);
			} catch (IOException e) {
				return e;
			}
		}
		return null;
	}

	public Socket getSocket() {
		return Data.socket;
	}
}
