package fr.rhaz.sockets.utils;

import java.util.Map;

import fr.rhaz.sockets.Sockets;

public class Message {
	
	public String msg;
	private boolean ended;
	
	public Message() {
		msg = "";
		ended = false;
	}
	
	public void add(String line) throws IllegalStateException{
		if(ended) throw new IllegalStateException();
		msg += line;
	}
	
	public String get() throws IllegalStateException{
		if(!ended) throw new IllegalStateException();
		return msg;
	}
	
	public void end() {
		ended = true;
	}
	
	public boolean ended() {
		return ended;
	}
	
	public void reset() {
		if(!ended) throw new IllegalStateException();
		msg = "";
		ended = false;
	}
	
	@SuppressWarnings("unchecked")
	public Map<String, Object> map(){
		if(!ended) throw new IllegalStateException();
		return Sockets.gson().fromJson(msg, Map.class);
	}
	
	public String egr() {
		end();
		String msg = get();
		reset();
		return msg;
	}
	
	public Map<String, Object> emr(){
		end();
		Map<String, Object> map = map();
		reset();
		return map;
	}
}
