package sd1920.trab1.clients;

import java.io.IOException;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import sd1920.trab1.api.Message;
import sd1920.trab1.api.rest.MessageService;

public class PostMessageClient {

	public final static int MAX_RETRIES = 3;
	public final static long RETRY_PERIOD = 1000;
	public final static int CONNECTION_TIMEOUT = 1000;
	public final static int REPLY_TIMEOUT = 600;
	
	public static void main(String[] args) throws IOException {
		
		Scanner sc = new Scanner(System.in);
		
		//You should replace this by the discovery class developed last week
		System.out.println("Provide the server url:");
		String serverUrl = sc.nextLine();
		
		System.out.println("Provide sender username:");
		String sender = sc.nextLine();
		
		System.out.println("Provide destination username(s) -- separated by commas:");
		Set<String> destinations = new HashSet<String>();
		for(String s: sc.nextLine().split(",")) {
			destinations.add(s.trim());
		}
		
		System.out.println("Provide subject of message:");
		String subject = sc.nextLine();
		
		System.out.println("Provide message contents/body (terminate with a line with a single dot):");
		String contents = "";
		while(true) {
			String s = sc.nextLine();
			if(!s.equalsIgnoreCase(".")) {
				contents += s + "\n";
			} else {
				break;
			}
		}
		
		sc.close();
		
		Message m = new Message(sender, destinations, subject, contents.getBytes());
		
		System.out.println("Sending request to server.");
		
		ClientConfig config = new ClientConfig();
		//How much time until timeout on opening the TCP connection to the server
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		//How much time to wait for the reply of the server after sending the request
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		Client client = ClientBuilder.newClient(config);
		
		WebTarget target = client.target( serverUrl ).path( MessageService.PATH );
		
		short retries = 0;
		boolean success = false;
		
		while(!success && retries < MAX_RETRIES) {
			try {
				Response r = target.request()
						.accept(MediaType.APPLICATION_JSON)
						.post(Entity.entity(m, MediaType.APPLICATION_JSON));
		
				if( r.getStatus() == Status.OK.getStatusCode() && r.hasEntity() )
					System.out.println("Success, message posted with id: " + r.readEntity(Long.class) );
				else
					System.out.println("Error, HTTP error status: " + r.getStatus() );
				
				success = true;
			} catch ( ProcessingException pe ) { //Error in communication with server
				System.out.println("Timeout occurred.");
				pe.printStackTrace(); //Could be removed
				retries ++;
				try {
					Thread.sleep( RETRY_PERIOD ); //wait until attempting again.
				} catch (InterruptedException e) {
					//Nothing to be done here, if this happens we will just retry sooner.
				}
				System.out.println("Retrying to execute request.");
			}
		}
	}
	
}
