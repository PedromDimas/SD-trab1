package sd1920.trab1.server;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.logging.Logger;
import java.net.InetSocketAddress;

import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.server.resources.MessageResource;
import sd1920.trab1.server.resources.UserResource;

public class MessageServer {

	private static Logger Log = Logger.getLogger(MessageServer.class.getName());
	private static Discovery discovery_channel;

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}
	
	public static final int PORT = 8080;
	public static final String SERVICE = "MessageService";

	
	public static void main(String[] args) throws UnknownHostException {
		String ip = InetAddress.getLocalHost().getHostAddress();

		discovery_channel = new Discovery(new InetSocketAddress("226.226.226.226",2266),InetAddress.getLocalHost().getCanonicalHostName(),"http://"+ip+":8080/rest");

		String domain = InetAddress.getLocalHost().getCanonicalHostName();

		MessageResource msgr = new MessageResource(domain,discovery_channel);
		UserResource usr = new UserResource(domain);

		ResourceConfig config = new ResourceConfig();
		config.register(msgr);
		config.register(usr);

		String serverURI = String.format("http://%s:%s/rest", ip, PORT);
		JdkHttpServerFactory.createHttpServer( URI.create(serverURI), config);

		discovery_channel.start();

		Log.info(String.format("%s Server ready @ %s\n",  SERVICE, serverURI));
		
		//More code can be executed here...
	}
	
}
