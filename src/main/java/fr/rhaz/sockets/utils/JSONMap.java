package fr.rhaz.sockets.utils;

import java.util.HashMap;
import java.util.Map;

public class JSONMap extends HashMap<String,Object> {
	
	private static final long serialVersionUID = 4878083193340506183L;

	public JSONMap() {
		super();
	}
	
	public JSONMap(Map<String,Object> map) {
		super(map);
	}
	
	public JSONMap(Object... entries) {
		
		String key = null;
		for(Object o:entries){
			
			if(key == null) {
				if(o instanceof String)
					key = (String) o;
			
			}else {
				if(!key.isEmpty())
					this.put(key, o);
				
				key = null;
			}
		}
	}
	
}