package fr.rhaz.sockets.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import fr.rhaz.sockets.Sockets;
import fr.rhaz.sockets.utils.AES;
import fr.rhaz.sockets.utils.Message;
import fr.rhaz.sockets.utils.RSA;

public class SocketMessenger implements Runnable {
	
	private AtomicBoolean handshaked = new AtomicBoolean(false);
	
	private Message mRSA = new Message();
	private Message mAES = new Message();
	private Message message = new Message();
	
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
				
				if (Security.level == 0) 
					writeJSON("SocketAPI", "handshake");
				
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
				loop:{
				
					// Read the line
					String read = IO.reader.readLine();
					Data.server.Data.app.log("read: " + read);
					
					// If end of stream, close
					if (read == null) 
						break; 
				
					rsa:{
						
						// Is RSA enabled? Do we received the RSA key?
						if (!(Security.level >= 2 && Security.Target.RSA == null)) 
							break rsa; 
						
						// Is the message fully received?
						if (!read.equals("--end--")) { 
							mRSA.add(read); // Add this line
							break loop; // Read another line
						}
						
						String key = mRSA.egr();
						// Yay, we received the RSA key
						// Convert it to a PublicKey object
						Security.Target.RSA = RSA.loadPublicKey(key); 
						
						// Now we send our AES key encrypted with target RSA
						String aes = AES.toString(Security.Self.AES);
						String rsa = RSA.encrypt(aes, Security.Target.RSA);
						
						IO.writer.println(rsa);
						IO.writer.println("--end--");
						IO.writer.flush();
						break loop; // Wait until we receive another message
					}
					
					aes:{
						
						// Is AES enabled? Do we already received AES?
						if (!(Security.level >= 1 && Security.Target.AES == null))
							break aes;
	
						// Is it the end of the key?
						if (!read.equals("--end--")) {
							mAES.add(read); // Add this line
							break loop; // Read another line
						}
						
						String key = mAES.egr();
						
						Data.server.Data.app.log("Target AES: " + key);
						
						// Received AES key encrypted in RSA
						if(Security.level == 2) 
							key = RSA.decrypt(key, Security.Self.RSA.getPrivate());

						// We save it
						Security.Target.AES = AES.toKey(key);
						
						// Now we handshake
						writeJSON("SocketAPI", "handshake");
						break loop; // Wait until we receive another message
						
					}
					
					// Is the line encrypted in AES?
					if (Security.level >= 1)
						read = AES.decrypt(read, Security.Self.AES);
					
					Data.server.Data.app.log("<- " + read);
					
					// If line is null or empty, read another
					if (read == null || read.isEmpty())
						break loop;

					// Is message fully received?
					if (!read.equals("--end--")) {
						message.add(read); // Add line
						break loop; // Read another line
					}
					
					// Convert message to an object
					Map<String, String> map = message.emr();
					
					handshake:{
						
						// Is it our channel?
						if (!map.get("channel").equals("SocketAPI"))
							break handshake;
							
						// Is the message a handshake?
						if (!map.get("data").equals("handshake"))
							break handshake;
								
						handshaked.set(true);
						Data.name = map.get("name");
						
						Data.server.getApp().onHandshake(this, Data.name);
						writeJSON("SocketAPI", "handshaked");
						break loop; // Wait until we receive another message
					}
					
					// Send the object to the app
					Data.server.getApp().onJSON(this, map);
					
				}
			
				Thread.sleep(100); // Wait 100 milliseconds before reading another line
			} catch (IOException | GeneralSecurityException | InterruptedException e) {
				break;
			}
		}
		close();
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
			
			String json = Sockets.gson().toJson(hashmap);
			write(json);
			
		} catch (NullPointerException e) {}
	}

	private void write(String data) {
		try {
			
			String[] split = Sockets.split(data, 20);
			
			for (String str : split) {
				
				if(Security.level >= 1)
					str = AES.encrypt(str, Security.Target.AES);
				
				IO.writer.println(str);
				
			}
			
			String end = "--end--";
			
			if(Security.level >= 1)
				end = AES.encrypt(end, Security.Target.AES);
			
			IO.writer.println(end);
			IO.writer.flush();
			
		} catch (NullPointerException e) {}
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