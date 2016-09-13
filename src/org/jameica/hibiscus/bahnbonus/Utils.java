package org.jameica.hibiscus.bahnbonus;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import com.gargoylesoftware.htmlunit.ProxyConfig;
import com.gargoylesoftware.htmlunit.WebClient;

import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

public class Utils {

	public static void setProxyCfg(WebClient webClient, String url)  {
		boolean useSystem = Application.getConfig().getUseSystemProxy();

		ProxyConfig pc = null;
		if (useSystem) {
			try {
				List<Proxy> proxies = ProxySelector.getDefault().select(new URI(url));
				Logger.info("Using system proxy settings: " + proxies);
				for (Proxy p : proxies) {
					if (p.type() == Proxy.Type.HTTP && p.address() instanceof InetSocketAddress) {
						pc = new ProxyConfig();
						InetSocketAddress addr = (InetSocketAddress) p.address();
						pc.setProxyHost(addr.getHostString());
						pc.setProxyPort(addr.getPort());
						webClient.getOptions().setProxyConfig(pc);
						Logger.info("Setting Proxy to " + pc);
						return;
					}
				}
				Logger.error("No default Proxy found");
			} catch (URISyntaxException e) {
				Logger.error("No default Proxy found", e);
			}
		} else {
			String host = Application.getConfig().getHttpsProxyHost();
			int port = Application.getConfig().getHttpsProxyPort();
			if (host != null && host.length() > 0 && port > 0) {
				pc = new ProxyConfig();
				pc.setProxyHost(host);
				pc.setProxyPort(port);
				webClient.getOptions().setProxyConfig(pc);
				Logger.info("Setting Proxy to " + pc);
				return;
			}
		}
		Logger.info("Keine gültige Proxy-Einstellunge gefunden. (" + useSystem + ")");
	}

	
	  // Zerlegt einen String intelligent in max. 27 Zeichen lange Stücke
	  public static String[] parse(String line)
	  {
	    if (line == null || line.length() == 0)
	      return new String[0];
	    List<String> out = new ArrayList<String>();
	    String rest = line.trim();
	    int lastpos = 0;
	    while (rest.length() > 0) {
	    	if (rest.length() < 28) {
	    		out.add(rest);
	    		rest = "";
	    		continue;
	    	}
	    	int pos = rest.indexOf(' ', lastpos + 1);
	    	boolean zulang = (pos > 28) || pos == -1;
	    	// 1. Fall: Durchgehender Text mit mehr als 27 Zeichen ohne Space
	    	if (lastpos == 0 && zulang) {
	    		out.add(rest.substring(0, 27));
	    		rest = rest.substring(27).trim();
	    		continue;
	    	} 
	    	// 2. Fall Wenn der String immer noch passt, weitersuchen
	    	if (!zulang) {
	    		lastpos = pos;
	    		continue;
	    	}
	    	// Bis zum Space aus dem vorherigen Schritt den String herausschneiden
	    	out.add(rest.substring(0, lastpos));
	    	rest = rest.substring(lastpos + 1).trim();
	    	lastpos = 0;
	    }
	    return out.toArray(new String[0]);
	  }



	
}
