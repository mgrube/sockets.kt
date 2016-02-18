package fr.rhaz.socketapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;
import javax.xml.bind.DatatypeConverter;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class SocketAPI {
	public static Gson gson = new Gson();
	
	public static Gson gson(){
		return gson;
	}
	
	public static interface SocketLogger{
		public void info(String str);
		public void warning(String str);
	}
	
	public static class Server{
		public static interface SocketServerApp{
			public void onConnect(SocketMessenger mess);
			public void onHandshake(SocketMessenger mess, String name);
			public void onJSON(SocketMessenger mess, Map<String, String> map);
			public void onDisconnect(SocketMessenger mess);
			public void run(SocketMessenger mess);
			public void run(SocketServer server);
		}
		
		public static class SocketServer implements Runnable {
			private ServerSocket server;
			private int port;
			public KeyPair keys;
			private SocketServerApp app;
			private ArrayList<SocketMessenger> messengers;
			
			public SocketServer(SocketServerApp app, int port, KeyPair keys){
				this.keys = keys;
				this.port = port;
				this.app = app;
				messengers = new ArrayList<>();
				try { server = new ServerSocket();
				} catch (IOException e) {}
		    }
			
			public IOException start(){
				try {
					server = new ServerSocket(port);
					app.run(this);
					return null;
				} catch (IOException e) {
					return e;
				}
			}
			
			public int getPort(){
				return port;
			}
			
			public SocketServerApp getApp(){
				return app;
			}

			public KeyPair getKeys(){
				return keys;
			}
			
			public ServerSocket getServerSocket(){
				return server;
			}
			
			@Override
		    public void run(){
				while(!server.isClosed()){
					try {
						Socket socket = server.accept();
						socket.setTcpNoDelay(true);
						SocketMessenger messenger = new SocketMessenger(this, socket);
						messengers.add(messenger);
						app.onConnect(messenger);
						app.run(messenger);
					} catch (IOException e) {}
				} 
		    }
		    
			public IOException close(){
				if(!server.isClosed()){
					try {
						server.close();
						for(SocketMessenger messenger:new ArrayList<>(messengers)) messenger.close();
					} catch (IOException e) {
						return e;
					}
				} return null;
			}
		    
		    public boolean isEnabled(){
		    	return !server.isClosed();
		    }
		}
		
		public static class SocketMessenger implements Runnable{
			private Socket socket;
			private SocketServer server;
			private PublicKey key;
			private PrintWriter writer;
			private BufferedReader reader;
			private String keyread = "";
			private String fullmessage = "";
			private AtomicBoolean handshaked = new AtomicBoolean(false);
			private String name;

			public SocketMessenger(SocketServer socketServer, final Socket socket){
				this.socket = socket;
				this.server = socketServer;
				if(server.isEnabled() && socket.isConnected() && !socket.isClosed()){
					try {
						reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
						writer = new PrintWriter(socket.getOutputStream());
						try {
							writer.println(SocketAPI.RSA.savePublicKey(server.keys.getPublic()));
							writer.println("--end--");
							writer.flush();
						} catch (GeneralSecurityException e) {
							e.printStackTrace();
						}
					} catch (IOException e) {}
				}
			}
			
			@Override
			public void run() {
				while(server.isEnabled() && socket.isConnected() && !socket.isClosed()){
					try {
						String read = reader.readLine();
						if(read == null) close(); else {
							if(key == null){
								if(!read.equals("--end--")) keyread += read; else {
									try {
										key = SocketAPI.RSA.loadPublicKey(keyread);
										writeJSON("SocketAPI", "handshake");
									} catch (GeneralSecurityException e) {
										e.printStackTrace();
									}
								}
							} else {
								String message = SocketAPI.RSA.decrypt(read, server.keys.getPrivate());
								if(message != null && !message.isEmpty()){
									if(!message.equals("--end--")) fullmessage += message; else {
										if(fullmessage != null && !fullmessage.isEmpty()){
											try{
												@SuppressWarnings("unchecked")
												Map<String, String> map = SocketAPI.gson().fromJson(fullmessage, Map.class);
												if(map.get("channel").equals("SocketAPI")){
													if(map.get("data").equals("handshake")){
														handshaked.set(true);
														name = map.get("name");
														server.getApp().onHandshake(this, name);
														writeJSON("SocketAPI", "handshaked");
													}
												} else server.getApp().onJSON(this, map);
											} catch (JsonSyntaxException e){}
										} fullmessage = "";
									}
								}
							}
						}
					} catch (IOException e) {
						if(e.getClass().getSimpleName().equals("SocketException")) close();
					}
				}
			}
			
			public SocketServer getServer(){
				return server;
			}
			
			public boolean isConnectedAndOpened(){
				return getSocket().isConnected() && !getSocket().isClosed();
			}
			
			public boolean isHandshaked() {
				return handshaked.get();
			}
			
			public String getName() {
				return name;
			}

			public void writeJSON(String channel, String data){
				try{
					HashMap<String, String> hashmap = new HashMap<>();
					hashmap.put("channel", channel);
					hashmap.put("data", data);
					String json = SocketAPI.gson().toJson(hashmap);
					write(json);
				} catch(NullPointerException e){}
			}
			
			private void write(String data){
				try{
					String[] split = SocketAPI.split(data, 20);
					for(String str:split) writer.println(SocketAPI.RSA.encrypt(str, key));
					writer.println(SocketAPI.RSA.encrypt("--end--", key));
					writer.flush();
				} catch(NullPointerException e){}
			}
			
			public IOException close() {
				if(!socket.isClosed()){
					try {
						socket.close();
						server.messengers.remove(this);
						server.getApp().onDisconnect(this);
					} catch (IOException e) {
						return e;
					}
				} return null;
			}

			public Socket getSocket(){
				return socket;
			}
		}
	}
	
	public static class Client{
		public static interface SocketClientApp{
			public void onConnect(SocketClient client);
			public void onDisconnect(SocketClient client);
			public void onHandshake(SocketClient client);
			public void onJSON(SocketClient client, Map<String, String> map);
		}
		
		public static class SocketClient implements Runnable {
			private String host;
			private int port;
			private Socket socket;
			private AtomicBoolean enabled = new AtomicBoolean(true);
			private PublicKey key;
			private BufferedReader reader;
			private PrintWriter writer;
			private KeyPair keys;
			private AtomicBoolean handshaked = new AtomicBoolean(false);
			private SocketClientApp app;
			private String name;
			
			public SocketClient(SocketClientApp app, String name, String host, int port, KeyPair keys){
				this.host = host;
				this.port = port;
				this.keys = keys;
				this.app = app;
				this.name = name;
				enabled.set(true);
				socket = new Socket();
			}
			
			public void run() {
				while(enabled.get()){
					try {
						socket = new Socket(host, port);
						socket.setTcpNoDelay(true);
						app.onConnect(this);
						
						key = null;
						handshaked.set(false);
						String keyread = "";
						String fullmessage = "";
						reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
						writer = new PrintWriter(socket.getOutputStream());
						while(enabled.get() && socket.isConnected() && !socket.isClosed()){
							String read = reader.readLine();
							if(read == null) close(); else {
								if(key == null){
									if(!read.equals("--end--")) keyread += read; else {
										try {
											key = SocketAPI.RSA.loadPublicKey(keyread);
											writer.println(SocketAPI.RSA.savePublicKey(keys.getPublic()));
											writer.println("--end--");
											writer.flush();
										} catch (GeneralSecurityException e) {
											e.printStackTrace();
										}
									}
								} else{
									String message = SocketAPI.RSA.decrypt(read, keys.getPrivate());
									if(message != null && !message.isEmpty()){
										if(!message.equals("--end--")) fullmessage += message; else {
											if(fullmessage != null && !fullmessage.isEmpty()){ 
												try{
													@SuppressWarnings("unchecked")
													Map<String, String> map = SocketAPI.gson().fromJson(fullmessage, Map.class);
													if(map.get("channel").equals("SocketAPI")){
														if(map.get("data").equals("handshake")){
															handshake();
														} else if(map.get("data").equals("handshaked")){
															handshaked.set(true);
															app.onHandshake(this);
														}
													} else app.onJSON(this, map);
												} catch (JsonSyntaxException e){}
											} fullmessage = "";
										}
									}
								}
							}
						}
					} catch (IOException e) {
						if(e.getClass().getSimpleName().equals("SocketException")) close();
					}
				}
			}

			public int getPort(){
				return port;
			}
			
			public String getHost(){
				return host;
			}
			
			public Socket getSocket(){
				return socket;
			}
			
			public boolean isConnectedAndOpened(){
				return socket.isConnected() && !socket.isClosed();
			}
			
			public boolean isHandshaked() {
				return handshaked.get();
			}
			
			private void handshake(){
				try{
					HashMap<String, String> hashmap = new HashMap<>();
					hashmap.put("channel", "SocketAPI");
					hashmap.put("data", "handshake");
					hashmap.put("name", name);
					String json = SocketAPI.gson().toJson(hashmap);
					write(json);
				} catch(NullPointerException e){}
			}

			public void writeJSON(String channel, String data){
				try{
					HashMap<String, String> hashmap = new HashMap<>();
					hashmap.put("channel", channel);
					hashmap.put("data", data);
					String json = SocketAPI.gson().toJson(hashmap);
					write(json);
				} catch(NullPointerException e){}
			}
			
			private void write(String data){
				try{
					String[] split = SocketAPI.split(data, 20);
					for(String str:split) writer.println(SocketAPI.RSA.encrypt(str, key));
					writer.println(SocketAPI.RSA.encrypt("--end--", key));
					writer.flush();
				} catch(NullPointerException e){}
			}
			
			public IOException close(){
				if(!socket.isClosed()){
					try {
						socket.close();
						app.onDisconnect(this);
					} catch (IOException e) {
						return e;
					}
				} return null;
			}
			
			public IOException interrupt(){
				enabled.set(false);
				return close();
			}
			
			public boolean isEnabled(){
				return enabled.get();
			}
		}
	}
	
	public static String encrypt(String data, String pass){
		try{
			while(pass.length() < 24) pass += pass;
            SecretKeyFactory factory = SecretKeyFactory.getInstance("DESede");
            SecretKey key = factory.generateSecret(new DESedeKeySpec(pass.getBytes()));
            Cipher cipher = Cipher.getInstance("DESede");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            String str = DatatypeConverter.printBase64Binary(cipher.doFinal(data.getBytes()));
            return str;
        } catch(Exception e) {
        	e.printStackTrace();
        } return null;
	}
	
	
	public static String decrypt(String data, String pass){
		try{
			while(pass.length() < 24) pass += pass;
			SecretKeyFactory factory = SecretKeyFactory.getInstance("DESede");
	        SecretKey key = factory.generateSecret(new DESedeKeySpec(pass.getBytes()));
	        Cipher cipher = Cipher.getInstance("DESede");
			cipher.init(Cipher.DECRYPT_MODE, key);
	        String str = new String(cipher.doFinal(DatatypeConverter.parseBase64Binary(data)));
	        return str;
		} catch(Exception e){}
		return null;
	}
	
	public static String[] split(String input, int max){
	    return input.split("(?<=\\G.{"+max+"})");
	}
	
	public static class RSA {
		public static KeyPair generateKeys(){
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
				KeyPair keys = generator.generateKeyPair();
				return keys;
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			} return null;
		}
		
		public static String encrypt(String data, PublicKey key){
			try {
				Cipher rsa = Cipher.getInstance("RSA");
				rsa.init(Cipher.ENCRYPT_MODE, key); 
				return DatatypeConverter.printBase64Binary(rsa.doFinal(data.getBytes()));
			} catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException e) {
				e.printStackTrace();
			} catch (InvalidKeyException e) {}
			return null;
		}
		
		public static String decrypt(String data, PrivateKey key){
			try {
				Cipher rsa = Cipher.getInstance("RSA");
				rsa.init(Cipher.DECRYPT_MODE, key);
				return new String(rsa.doFinal(DatatypeConverter.parseBase64Binary(data)));
			} catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException e) {
				e.printStackTrace();
			} catch (InvalidKeyException e) {}
			return null;
		}
		
		public static PrivateKey loadPrivateKey(String key64) throws GeneralSecurityException, IOException {
		    byte[] clear = new BASE64Decoder().decodeBuffer(key64);
		    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(clear);
		    KeyFactory fact = KeyFactory.getInstance("RSA");
		    PrivateKey priv = fact.generatePrivate(keySpec);
		    Arrays.fill(clear, (byte) 0);
		    return priv;
		}

		public static PublicKey loadPublicKey(String stored) throws GeneralSecurityException, IOException {
		    byte[] data = new BASE64Decoder().decodeBuffer(stored);
		    X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
		    KeyFactory fact = KeyFactory.getInstance("RSA");
		    return fact.generatePublic(spec);
		}

		public static String savePrivateKey(PrivateKey priv) throws GeneralSecurityException {
		    KeyFactory fact = KeyFactory.getInstance("RSA");
		    PKCS8EncodedKeySpec spec = fact.getKeySpec(priv, PKCS8EncodedKeySpec.class);
		    byte[] packed = spec.getEncoded();
		    String key64 = new BASE64Encoder().encode(packed);
		    Arrays.fill(packed, (byte) 0);
		    return key64;
		}

		public static String savePublicKey(PublicKey publ) throws GeneralSecurityException {
		    KeyFactory fact = KeyFactory.getInstance("RSA");
		    X509EncodedKeySpec spec = fact.getKeySpec(publ, X509EncodedKeySpec.class);
		    return new BASE64Encoder().encode(spec.getEncoded());
		}
	}
	
	public static SocketAPI instance(){
		return new SocketAPI();
	}
	
	public static Bungee bungee(){
		return Bungee.instance();
	}
	
	public static Bukkit bukkit(){
		return Bukkit.instance();
	}
}
