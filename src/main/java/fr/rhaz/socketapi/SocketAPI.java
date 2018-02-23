package fr.rhaz.socketapi;

import com.google.gson.Gson;

public class SocketAPI {
	public static Gson gson = new Gson();

	public static Gson gson() {
		return gson;
	}

	public static String[] split(String input, int max) {
		return input.split("(?<=\\G.{" + max + "})");
	}

	public static SocketAPI instance() {
		return new SocketAPI();
	}
}
