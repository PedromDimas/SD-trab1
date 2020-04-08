package sd1920.trab1.server.implementation;

import java.util.*;
import java.util.logging.Logger;

import javax.inject.Singleton;
import javax.jws.WebService;

import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.api.soap.MessageServiceSoap;
import sd1920.trab1.clients.utils.MessageUtills;
import sd1920.trab1.discovery.Discovery;


@WebService(serviceName= MessageServiceSoap.NAME,
targetNamespace=MessageServiceSoap.NAMESPACE,
endpointInterface=MessageServiceSoap.INTERFACE)
@Singleton
public class MessageServiceImpl implements MessageServiceSoap {

	private Random randomNumberGenerator;

	private final Map<Long,Message> allMessages; 
	private final Map<String,Set<Long>> userInboxs;

	private static Logger Log = Logger.getLogger(MessageServiceImpl.class.getName());

	private Discovery discovery_channel;

	public MessageServiceImpl(Discovery discovery_channel) {
		this.randomNumberGenerator = new Random(System.currentTimeMillis());
		this.allMessages = new HashMap<Long, Message>();
		this.userInboxs = new HashMap<String, Set<Long>>();
		this.discovery_channel = discovery_channel;
	}


	@Override
	public long postMessage(String pwd, Message msg) throws MessagesException {
		Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: "+msg.getSubject()+")");

		//User u = getUser(msg.getSender(),pwd);

		//Check if message is valid, if not return HTTP CONFLICT (409)
		if(msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			System.out.println("Message no Exist");
			throw new MessagesException( "Message does not exists." );
		}

		long newID = 0;

		synchronized (this) {

			//Generate a new id for the message, that is not in use yet
			newID = Math.abs(randomNumberGenerator.nextLong());
			while(allMessages.containsKey(newID)) {
				newID = Math.abs(randomNumberGenerator.nextLong());
			}

			//Add the message to the global list of messages
			allMessages.put(newID, msg);

			System.out.println("INSIDE ALLMESSAGES");
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
		System.out.println("INSERTERD IN USERINBOX");
		//Return the id of the registered message to the client (in the body of a HTTP Response with 200)
		Log.info("Recorded message with identifier: " + newID);
		return newID;
	}

	@Override
	public Message getMessage(String user, String pwd, long mid) throws MessagesException {
		Log.info("Received request for message with id: " + mid +".");
		Message m = null;

		synchronized (this) {
			m = allMessages.get(mid);
		}

		if(m == null) {  //check if message exists
			Log.info("Requested message does not exists.");
			throw new MessagesException("Message does not exists"); //if not send HTTP 404 back to client
		}

		Log.info("Returning requested message to user.");
		return m; //Return message to the client with code HTTP 200
	}

	@Override
	public List<Long> getMessages(String user, String pwd) throws MessagesException {
		printmessages();
		Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
		//User u = getUser(user,pwd);
		List<Long> messages = new ArrayList<>();
		if(user == null) {
			Log.info("Collecting all messages in server");
			synchronized (this) {
				messages.addAll(allMessages.keySet());
			}

		} else {
			Log.info("Collecting all messages in server for user " + user);

			Set<Long> mids;


			synchronized (this) {
				mids = userInboxs.getOrDefault(user, Collections.emptySet());
			}

			messages.addAll(mids);



		}
		Log.info("Returning message list to user with " + messages.size() + " messages.");

		System.out.println(messages + " MESSAGES");

		return messages;
	}

	private void printmessages() {
		for (Map.Entry<Long,Message> e : allMessages.entrySet()){
			System.out.println("MID : " + e.getKey());
		}

		for (Map.Entry<String, Set<Long>> e : userInboxs.entrySet()){
			for (Long l : e.getValue()){
				System.out.println("mid : " + l);
			}
		}
	}

	@Override
	public void removeFromUserInbox(String user, String pwd, long mid) throws MessagesException {
		throw new Error("Not Implemented...");
	}

	@Override
	public void deleteMessage(String user, String pwd, long mid) throws MessagesException {
		throw new Error("Not Implemented...");
	}
}
