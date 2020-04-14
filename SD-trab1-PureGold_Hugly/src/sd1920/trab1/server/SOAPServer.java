package sd1920.trab1.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import javax.xml.ws.Endpoint;
import com.sun.net.httpserver.HttpServer;
import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.server.implementation.MessageServiceImpl;
import sd1920.trab1.server.implementation.UserServiceImpl;

public class SOAPServer {

	private static Logger Log = Logger.getLogger(SOAPServer.class.getName());
	private static Discovery discovery_channel;

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}
	
	public static final int PORT = 8080;
	public static final String SERVICE = "MessageService";
	public static final String SOAP_MESSAGES_PATH = "/soap/messages";
	public static final String SOAP_USERS_PATH = "/soap/users";
	
	public static void main(String[] args) throws Exception {
		String ip = InetAddress.getLocalHost().getHostAddress();
		String serverURI = String.format("http://%s:%s/soap", ip, PORT);

		discovery_channel = new Discovery(new InetSocketAddress("226.226.226.226", 2266), InetAddress.getLocalHost().getCanonicalHostName(), serverURI);

		
		// Create an HTTP server, accepting requests at PORT (from all local interfaces)
		HttpServer server = HttpServer.create(new InetSocketAddress(ip, PORT), 0);
		System.out.println("http server created");
		
		// Provide an executor to create threads as needed...
		server.setExecutor(Executors.newCachedThreadPool());
		
		// Create a SOAP Endpoint (you need one for each service)
		Endpoint soapMessagesEndpoint = Endpoint.create(new MessageServiceImpl(discovery_channel));
		Endpoint soapUsersEndpoint = Endpoint.create(new UserServiceImpl(InetAddress.getLocalHost().getCanonicalHostName()));

		// Publish a SOAP webservice, under the "http://<ip>:<port>/soap"
		soapMessagesEndpoint.publish(server.createContext(SOAP_MESSAGES_PATH));
		soapUsersEndpoint.publish(server.createContext(SOAP_USERS_PATH));
		
		server.start();
		discovery_channel.start();

		Log.info(String.format("\n%s Server ready @ %s\n",  SERVICE, serverURI));
		
		//More code can be executed here...
	}
	
}
