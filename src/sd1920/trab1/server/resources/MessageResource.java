package sd1920.trab1.server.resources;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Logger;

import javax.inject.Singleton;
import javax.swing.plaf.synth.SynthOptionPaneUI;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.rest.MessageService;
import sd1920.trab1.api.rest.UserService;
import sd1920.trab1.clients.GetMessageClient;
import sd1920.trab1.clients.utils.MessageUtills;
import sd1920.trab1.discovery.Discovery;

@Singleton
public class MessageResource implements MessageService {

	private Random randomNumberGenerator;

	private final Map<Long,Message> allMessages = new HashMap<>();
	private final Map<String,Set<Long>> userInboxs = new HashMap<>();

	private static Logger Log = Logger.getLogger(MessageResource.class.getName());

	private Discovery discovery_channel;

	public MessageResource(Discovery discovery_channel) {
		this.discovery_channel = discovery_channel;
		this.randomNumberGenerator = new Random(System.currentTimeMillis());
	}


	@Override
	public long postMessage(String pwd, Message msg) {
		//TODO passwd check
		Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: "+msg.getSubject()+")");

		User u = getUser(msg.getSender(),pwd);

		//Check if message is valid, if not return HTTP CONFLICT (409)
		if(msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			Log.info("Message was rejected due to lack of recepients.");
			throw new WebApplicationException( Status.CONFLICT );
		}

		long newID = 0;

		String formatedSender =  u.getDisplayName() + msg.getSender() + u.getDomain();

		msg.setSender( formatedSender);

		synchronized (this) {

			//Generate a new id for the message, that is not in use yet
			newID = Math.abs(randomNumberGenerator.nextLong());
			while(allMessages.containsKey(newID)) {
				newID = Math.abs(randomNumberGenerator.nextLong());
			}

			//Add the message to the global list of messages
			msg.setId(newID);
			allMessages.put(newID, msg);
		}

		Log.info("Created new message with id: " + newID);
		MessageUtills.printMessage(allMessages.get(newID));

		synchronized (this) {
			//Add the message (identifier) to the inbox of each recipient
			for(String recipient: msg.getDestination()) {
				if(!userInboxs.containsKey(recipient)) {
					userInboxs.put(recipient, new HashSet<Long>());
				}
				userInboxs.get(recipient).add(newID);
			}
		}

		//Return the id of the registered message to the client (in the body of a HTTP Response with 200)
		Log.info("Recorded message with identifier: " + newID);
		return newID;
	}

	@Override
	public Message getMessage(String user, long mid, String pwd) {
		Log.info("Received request for message with id: " + mid +".");
		Message m = null;

		synchronized (this) {
			m = allMessages.get(mid);
		}

		if(m == null) {  //check if message exists
			Log.info("Requested message does not exists.");
			throw new WebApplicationException( Status.NOT_FOUND ); //if not send HTTP 404 back to client
		}

		System.out.println(m.getId() + "CONAÇA");

		Log.info("Returning requested message to user.");
		return m; //Return message to the client with code HTTP 200
	}

	public byte[] getMessageBody(long mid) {
		Log.info("Received request for body of message with id: " + mid +".");
		byte[] contents = null;
		synchronized (this) {
			Message m = allMessages.get(mid);
			if(m != null)
				contents = m.getContents();
		}


		if(contents != null) { //implicitaly checks if message exists
			Log.info("Requested message does not exists.");
			throw new WebApplicationException( Status.NOT_FOUND ); //if not send HTTP 404 back to client
		}

		Log.info("Returning requested message body to user.");
		return contents; //Return message contents to the client with code HTTP 200
	}


	@Override
	public List<Long> getMessages(String user, String pwd) {
		Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
		List<Long> messages = new ArrayList<>();
		if(user == null) {
			Log.info("Collecting all messages in server");
			synchronized (this) {
				messages.addAll(allMessages.keySet());
			}

		} else {
			Log.info("Collecting all messages in server for user " + user);

			Set<Long> mids;
			String domain;
			String formated = "";
			try {
				domain = InetAddress.getLocalHost().getCanonicalHostName();
				formated = user + "@" + domain;
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}



			synchronized (this) {
				mids = userInboxs.getOrDefault(formated, Collections.emptySet());
			}

			messages.addAll(mids);


		}
		Log.info("Returning message list to user with " + messages.size() + " messages.");

		return messages;
	}


	@Override
	public void removeFromUserInbox(String user, long mid, String pwd) {
		throw new Error("Not Implemented...");
	}

	@Override
	public void deleteMessage(String user, long mid, String pwd) {

		throw new Error("Not Implemented...");
	}

	public User getUser(String name, String pwd){
		String url = "";
		try {
			String domain = InetAddress.getLocalHost().getCanonicalHostName();
			URI[] uris = discovery_channel.knownUrisOf(domain);
			url = uris[uris.length-1].toString();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}



		ClientConfig config = new ClientConfig();
		//How much time until timeout on opening the TCP connection to the server
		config.property(ClientProperties.CONNECT_TIMEOUT, GetMessageClient.CONNECTION_TIMEOUT);
		//How much time to wait for the reply of the server after sending the request
		config.property(ClientProperties.READ_TIMEOUT, GetMessageClient.REPLY_TIMEOUT);

		Client client = ClientBuilder.newClient(config);
		WebTarget target = client.target("http://"+url+":8080/rest").path(UserService.PATH).path(name).queryParam("pwd",pwd);

		System.out.println("PATH + " + target);


		short retries = 0;

		while(retries < GetMessageClient.MAX_RETRIES) {
			try {

				Response r = target.request()
						.accept(MediaType.APPLICATION_JSON)
						.get();
				System.out.println("RESPONSE + " + r);
				if( r.getStatus() == Status.OK.getStatusCode() && r.hasEntity() ) {
					System.out.println("Success:");
					User u = r.readEntity(User.class);
					return u;
				} else
					throw new WebApplicationException(r.getStatus());

			} catch ( ProcessingException pe ) { //Error in communication with server
				System.out.println("Timeout occurred.");
				//pe.printStackTrace(); //Could be removed
				retries ++;
				try {
					Thread.sleep( GetMessageClient.RETRY_PERIOD ); //wait until attempting again.
				} catch (InterruptedException e) {
					System.out.println("interrupted");
					//Nothing to be done here, if this happens we will just retry sooner.
				}
				System.out.println("Retrying to execute request.");
			}
		}
		throw new WebApplicationException(Status.FORBIDDEN);
	}

}
