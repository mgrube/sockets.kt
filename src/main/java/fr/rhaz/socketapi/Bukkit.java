package fr.rhaz.socketapi;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import fr.rhaz.socketapi.SocketAPI.Client.SocketClient;
import fr.rhaz.socketapi.SocketAPI.Client.SocketClientApp;

public class Bukkit extends JavaPlugin implements SocketClientApp {
	private static Bukkit plugin;
	private Configuration config;
	private SocketClient client;
	private KeyPair keys;

	@Override
	public void onEnable(){
		plugin = this;
		reload();
		getCommand("sock").setExecutor(new CommandExecutor(){
			@Override
			public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
				if(sender.hasPermission("sock.reload")){
					if(args.length >= 1 && args[0].equalsIgnoreCase("reload")){
						reload();
						sender.sendMessage("Config reloaded.");
					} if(args.length >= 1 && args[0].equalsIgnoreCase("restart")){
						restart();
						sender.sendMessage("Restarted.");
					} else sender.sendMessage("/bukkitsock <reload|restart>");
				} else sender.sendMessage("§cYou don't have permission.");
				return true;
			}
		});
		start();
	}
	
	public void reload(){
		config = loadConfig("config.yml");
		keys = SocketAPI.RSA.generateKeys();
	}
	
	public void start(){
		client = new SocketClient(this, config.getString("name"), config.getString("host"), config.getInt("port"), keys);
		getServer().getScheduler().runTaskAsynchronously(plugin, client);
	}
	
	public boolean stop(){
		IOException err = client.interrupt();
		if(err != null){
			getLogger().warning("Could not stop socket client on port "+client.getPort());
			err.printStackTrace();
			return false;
		} else {
			getLogger().info("Successfully stopped socket client on port "+client.getPort());
			return true;
		}
	}
	
	public void restart(){
		if(stop()) start();
	}
	
	public Configuration loadConfig(String name){
		if (!getDataFolder().exists()) getDataFolder().mkdir();
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
	}
	
	public static Bukkit instance(){
		return plugin;
	}
	
	public static class BukkitSocketConnectEvent extends Event {
		private final static HandlerList handlers = new HandlerList();
		private SocketClient client;
		
		public BukkitSocketConnectEvent(SocketClient client) {
			this.client = client;
		}

		public SocketClient getClient(){
			return client;
		}
		
		@Override
		public HandlerList getHandlers() {
			return handlers;
		}
		
		public static HandlerList getHandlerList(){
			return handlers;
		}
	}
	
	public static class BukkitSocketDisconnectEvent extends Event {
		private final static HandlerList handlers = new HandlerList();
		private SocketClient client;
		
		public BukkitSocketDisconnectEvent(SocketClient client) {
			this.client = client;
		}

		public SocketClient getClient(){
			return client;
		}
		
		@Override
		public HandlerList getHandlers() {
			return handlers;
		}
		
		public static HandlerList getHandlerList(){
			return handlers;
		}
	}
	
	public static class BukkitSocketHandshakeEvent extends Event {
		private final static HandlerList handlers = new HandlerList();
		private SocketClient client;
		
		public BukkitSocketHandshakeEvent(SocketClient client) {
			this.client = client;
		}

		public SocketClient getClient(){
			return client;
		}
		
		@Override
		public HandlerList getHandlers() {
			return handlers;
		}
		
		public static HandlerList getHandlerList(){
			return handlers;
		}
	}
	
	public static class BukkitSocketJSONEvent extends Event {
		private final static HandlerList handlers = new HandlerList();
		private Map<String, String> map;
		private SocketClient client;

		public BukkitSocketJSONEvent(SocketClient client, Map<String, String> map) {
			this.map = map;
			this.client = client;
		}
		
		public SocketClient getClient(){
			return client;
		}
		
		public String getChannel(){
			return map.get("channel");
		}
		
		public String getData(){
			return map.get("data");
		}
		
		public void write(String data){
			client.writeJSON(getChannel(), data);
		}
		
		@Override
		public HandlerList getHandlers() {
			return handlers;
		}
		
		public static HandlerList getHandlerList(){
			return handlers;
		}
	}
	
	public SocketClient getSocketClient(){
		return client;
	}

	@Override
	public void onConnect(SocketClient client) {
		getLogger().info("Successfully connected to "+client.getHost()+" on port "+client.getPort());
		getServer().getPluginManager().callEvent(new BukkitSocketConnectEvent(client));
	}

	@Override
	public void onDisconnect(SocketClient client) {
		getLogger().warning("Disconnected from "+client.getHost()+" on port "+client.getPort());
		getServer().getPluginManager().callEvent(new BukkitSocketDisconnectEvent(client));
	}

	@Override
	public void onHandshake(SocketClient client) {
		getServer().getPluginManager().callEvent(new BukkitSocketHandshakeEvent(client));
	}

	@Override
	public void onJSON(SocketClient client, Map<String, String> map) {
		getServer().getPluginManager().callEvent(new BukkitSocketJSONEvent(client, map));
	}
}
