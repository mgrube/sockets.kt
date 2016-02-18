package fr.rhaz.socketapi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Event;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import fr.rhaz.socketapi.SocketAPI.Server.SocketMessenger;
import fr.rhaz.socketapi.SocketAPI.Server.SocketServer;
import fr.rhaz.socketapi.SocketAPI.Server.SocketServerApp;

public class Bungee extends Plugin implements SocketServerApp {
	private static Bungee plugin;
	private Configuration config;
	private SocketServer server;
	private KeyPair keys;

	@Override
	public void onEnable(){
		plugin = this;
		reload();
		getProxy().getPluginManager().registerCommand(this, new Command("sock", "sock.reload", "bungeesock"){
			@Override
			public void execute(CommandSender sender, String[] args) {
				if(sender.hasPermission("sock.reload")){
					if(args.length >= 1 && args[0].equalsIgnoreCase("reload")){
						reload();
						sender.sendMessage(new TextComponent("Config reloaded."));
					} else if(args.length >= 1 && args[0].equalsIgnoreCase("restart")){
						restart();
						sender.sendMessage(new TextComponent("Restarted."));
					} else sender.sendMessage(new TextComponent("/bungeesock <reload|restart>"));
				} else sender.sendMessage(new TextComponent("§cYou don't have permission."));
			}
		});
		start();
	}
	
	public void reload(){
		config = loadConfig("config.yml");
		keys = SocketAPI.RSA.generateKeys();
	}
	
	public Configuration loadConfig(String name){
		if (!getDataFolder().exists()) getDataFolder().mkdir();
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            try {
				Files.copy(this.getResourceAsStream("bungeeconfig.yml"), file.toPath());
			} catch (IOException e) {
				e.printStackTrace();
			}
        } try {
			return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
		} catch (IOException e) {
			e.printStackTrace();
		} return null;
	}
	
	public class BungeeSocketConnectEvent extends Event {
		private SocketMessenger mess;
		
		public BungeeSocketConnectEvent(SocketMessenger mess){
			this.mess = mess;
		}
		
		public SocketMessenger getMessenger(){
			return mess;
		}
	}
	
	public class BungeeSocketDisconnectEvent extends Event {
		private SocketMessenger mess;
		
		public BungeeSocketDisconnectEvent(SocketMessenger mess){
			this.mess = mess;
		}
		
		public SocketMessenger getMessenger(){
			return mess;
		}
	}
	
	public class BungeeSocketJSONEvent extends Event {
		private SocketMessenger mess;
		private Map<String, String> map;
		
		public BungeeSocketJSONEvent(SocketMessenger mess, Map<String, String> map){
			this.mess = mess;
			this.map = map;
		}
		
		public String getChannel(){
			return map.get("channel");
		}
		
		public String getData(){
			return map.get("data");
		}
		
		public String getName(){
			return mess.getName();
		}
		
		public SocketMessenger getMessenger(){
			return mess;
		}
		
		public void write(String data){
			mess.writeJSON(getChannel(), data);
		}
	}
	
	public class BungeeSocketHandshakeEvent extends Event {
		private String name;
		private SocketMessenger mess;
		
		public BungeeSocketHandshakeEvent(SocketMessenger mess, String name){
			this.mess = mess;
			this.name = name;
		}
		
		public SocketMessenger getMessenger(){
			return mess;
		}
		
		public String getName(){
			return name;
		}
	}
	
	public static Bungee instance(){
		return plugin;
	}
	
	public SocketServer getSocketServer(){
		return server;
	}
	
	public boolean start(){
		server = new SocketServer(plugin, config.getInt("port"), keys);
		IOException err = server.start();
		if(err != null){
			getLogger().warning("Could not start socket server on port "+server.getPort());
			err.printStackTrace();
			return false;
		} else {
			getLogger().info("Successfully started socket server on port "+server.getPort());
			return true;
		}
	}
	
	public boolean stop(){
		IOException err = server.close();
		if(err != null){
			getLogger().warning("Could not stop socket server on port "+server.getPort());
			err.printStackTrace();
			return false;
		} else {
			getLogger().info("Successfully stopped socket server on port "+server.getPort());
			return true;
		}
	}
	
	public void restart(){
		if(stop()){
			getProxy().getScheduler().schedule(this, new Runnable(){
				@Override
				public void run() {
					start();
				}
			}, 1000, TimeUnit.MILLISECONDS);
		}
	}
	
	@Override
	public void run(SocketServer server){
		getProxy().getScheduler().runAsync(plugin, server);
	}
		
	@Override
	public void run(SocketMessenger mess){
		getProxy().getScheduler().runAsync(plugin, mess);
	}
	
	@Override
	public void onConnect(SocketMessenger mess) {
		getProxy().getPluginManager().callEvent(new BungeeSocketConnectEvent(mess));
	}

	@Override
	public void onHandshake(SocketMessenger mess, String name) {
		getProxy().getPluginManager().callEvent(new BungeeSocketHandshakeEvent(mess, name));
	}

	@Override
	public void onJSON(SocketMessenger mess, Map<String, String> map) {
		getProxy().getPluginManager().callEvent(new BungeeSocketJSONEvent(mess, map));
	}

	@Override
	public void onDisconnect(SocketMessenger mess) {
		getProxy().getPluginManager().callEvent(new BungeeSocketDisconnectEvent(mess));
	}
}
