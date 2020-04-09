package sd1920.trab1.server.implementation;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.logging.Logger;

import javax.inject.Singleton;
import javax.jws.WebService;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.Service;

import com.sun.xml.ws.client.BindingProviderProperties;
import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.api.soap.MessageServiceSoap;
import sd1920.trab1.api.soap.UserServiceSoap;
import sd1920.trab1.clients.utils.MessageUtills;
import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.helpers.RequestHelperSoap;


@WebService(serviceName= MessageServiceSoap.NAME,
targetNamespace=MessageServiceSoap.NAMESPACE,
endpointInterface=MessageServiceSoap.INTERFACE)
@Singleton
public class MessageServiceImpl implements MessageServiceSoap {

	private Random randomNumberGenerator;
	private final BlockingQueue<RequestHelperSoap> queue = new SynchronousQueue<>();
	private final Map<Long,Message> allMessages; 
	private final Map<String,Set<Long>> userInboxs;

	private static Logger Log = Logger.getLogger(MessageServiceImpl.class.getName());

	private Discovery discovery_channel;

	public final static int MAX_RETRIES = 3;
	public final static long RETRY_PERIOD = 1000;
	public final static int CONNECTION_TIMEOUT = 1000;
	public final static int REPLY_TIMEOUT = 600;

	private static final String MESSAGES_WSDL = "/messages/?wsdl";
	private static final String USER_WSDL = "/users/?wsdl";

	public MessageServiceImpl(Discovery discovery_channel) {
		this.randomNumberGenerator = new Random(System.currentTimeMillis());
		this.allMessages = new HashMap<Long, Message>();
		this.userInboxs = new HashMap<String, Set<Long>>();
		this.discovery_channel = discovery_channel;
		this.spinThreads();
	}


	@Override
	public long postMessage(String pwd, Message msg) throws MessagesException {
		Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: "+msg.getSubject()+")");
		User u = null;
		try {
			u = getUser(msg.getSender(), pwd);
		} catch (IOException e) {
			e.printStackTrace();
		}


		System.out.println("USER: " + u.getName());

		//Check if message is valid, if not return HTTP CONFLICT (409)
		if(msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			System.out.println("Message no Exist");
			throw new MessagesException( "Message does not exists." );
		}

		long newID = 0;
		String formatedSender;

		if (msg.getSender().contains("@"))
			formatedSender = u.getDisplayName() + " <"+msg.getSender()+">";
		else
			formatedSender =  u.getDisplayName() + " <"+msg.getSender() +"@"+ u.getDomain()+">";

		msg.setSender( formatedSender);

		synchronized (this) {

			//Generate a new id for the message, that is not in use yet
			newID = Math.abs(randomNumberGenerator.nextLong());
			while(allMessages.containsKey(newID)) {
				newID = Math.abs(randomNumberGenerator.nextLong());
			}

			//Add the message to the global list of messages
			msg.setId(newID);
		}

		synchronized (this) {
			if (!allMessages.containsKey(newID))
				allMessages.put(newID, msg);
		}


		Log.info("Created new message with id: " + newID);
		MessageUtills.printMessage(allMessages.get(newID));


		try {
			requestPost(msg);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}


		//Return the id of the registered message to the client (in the body of a HTTP Response with 200)
		Log.info("Recorded message with identifier: " + newID);
		return newID;
	}

	private void requestPost(Message msg) throws InterruptedException {
		String url = "";

		List<String> domains = new ArrayList<>();

		for(String recipient: msg.getDestination()) {
			if (!domains.contains(recipient.split("@")[1]))
				domains.add(recipient.split("@")[1]);
		}

		for (String domain : domains){
			URI[] uris = discovery_channel.knownUrisOf(domain);
			url = uris[uris.length-1].toString();


			RequestHelperSoap rh = new RequestHelperSoap(url,msg, domain, url);


			queue.put(rh);


		}


	}

	private User getUser(String sender, String pwd) throws IOException, MessagesException {
		//Get the server URL
		String url = "";
		String name = sender.split("@")[0];
		try {
			String domain = InetAddress.getLocalHost().getCanonicalHostName();
			URI[] uris = discovery_channel.knownUrisOf(domain);
			url = uris[uris.length-1].toString();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		//Form Connection with server
		UserServiceSoap us = null;
		try {
			QName QNAME = new QName(UserServiceSoap.NAMESPACE, UserServiceSoap.NAME);
			Service service = Service.create( new URL(url + USER_WSDL), QNAME);
			us = service.getPort( sd1920.trab1.api.soap.UserServiceSoap.class );
		} catch (WebServiceException wse) {
			System.err.println("Could not conntact the server: " + wse.getMessage());
			throw new MessagesException( "Connection Eroor" );
		}

		//Set timeouts
		((BindingProvider) us).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		((BindingProvider) us).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

		//Execute connection
		short retries = 0;
		boolean success = false;
		User u = null;
		while(!success && retries < MAX_RETRIES) {
			try {
				u = us.getUser(name, pwd);
				System.out.println("Success:");
				success = true;
			} catch ( MessagesException me ) { //Error executing the method in the server
				System.out.println("User does not exist");
				throw new MessagesException( "User does not exists." );
			} catch ( WebServiceException wse) { //timeout
				System.out.println("Communication error");
				wse.printStackTrace(); //could be removed.
				retries ++;
				try {
					Thread.sleep( RETRY_PERIOD ); //wait until attempting again.
				} catch (InterruptedException e) {
					//Nothing to be done here, if this happens we will just retry sooner.
				}
				System.out.println("Retrying to execute request.");
			}
		}

		return u;
	}

	@Override
	public Message getMessage(String user, String pwd, long mid) throws MessagesException {
		Log.info("Received request for message with id: " + mid +".");
		Message m = null;

		User u = null;
		try {
			u = getUser(user,pwd);
		} catch (IOException e) {
			e.printStackTrace();
		}

		synchronized (this) {
			Set<Long> s = userInboxs.get(u.getName());
			for (Long l: s) {
				if (mid == l)
					m = allMessages.get(l);
			}
		}

		if(m == null) {  //check if message exists
			Log.info("Requested message does not exists.");
			throw new MessagesException("Message Not Found");//if not send HTTP 404 back to client
		}


		return m; //Return message to the client with code HTTP 200
	}

	@Override
	public List<Long> getMessages(String user, String pwd) throws MessagesException {
		Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
		try {
			User u = getUser(user, pwd);
		} catch (IOException e) {
			e.printStackTrace();
		}

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
		Message m = null;

		User u = null;
		try {
			u = getUser(user,pwd);
		} catch (IOException e) {
			e.printStackTrace();
		}

		synchronized (this) {
			Set<Long> s = userInboxs.get(u.getName());
			for (Long l: s) {
				if (mid == l)
					m = allMessages.get(l);
			}
		}

		if(m == null) {  //check if message exists
			Log.info("Requested message does not exists.");
			throw new MessagesException("Message Not Found"); //if not send HTTP 404 back to client
		}

		synchronized (this) {
			Set<Long> s = userInboxs.get(u.getName());
			s.remove(mid);
		}

	}

	@Override
	public void deleteMessage(String user, String pwd, long mid) throws MessagesException {

		Message m = null;

		User u = null;
		try {
			u = getUser(user,pwd);
		} catch (IOException e) {
			e.printStackTrace();
		}


		synchronized (this) {
			m = allMessages.get(mid);
		}


		if(m == null) {  //check if message exists
			System.out.println("Requested message does not exists." + mid);
			//throw new MessagesException("Message Not Found"); //if not send HTTP 404 back to client
			return;
		}



		String[] pre = m.getSender().split(" ");
		String name = pre[pre.length-1].split("@")[0].substring(1);


		if(!(name.equals(u.getName()))){
			System.out.println("namesd Do not match");
			//throw new MessagesException("Not the owner"); //if not send HTTP 404 back to client
			return;
		}


		Set<String> s = m.getDestination();
		List<String> doms = new LinkedList<>();
		for (String d: s) {
			String domain = d.split("@")[1];
			if (!doms.contains(domain)) doms.add(domain);
		}

		System.out.println("DOMS " + doms);


		for (String dom: doms) {
			try {
				requestDeletes(dom, mid);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

	}

	private void requestDeletes(String dom, long mid) throws InterruptedException {
		String url = "";

		URI[] uris = discovery_channel.knownUrisOf(dom);
		url = uris[uris.length-1].toString();


		RequestHelperSoap rh = new RequestHelperSoap(mid,dom,url);


		queue.put(rh);

	}

	@Override
	public long recieve_inboxes(String domain, Message msg)  {
		Long newID = msg.getId();

		List<String> usrs = new ArrayList<>();

		for (String rec:msg.getDestination()){
			String[] spl = rec.split("@");
			String name = spl[0];
			String dom = spl[1];

			if (dom.equals(domain)){
				usrs.add(name);
			}
		}

		if (usrs.size() != 0) {

			synchronized (this) {
				if (!allMessages.containsKey(newID))
					allMessages.put(newID, msg);
			}

			for (String name : usrs) {
				synchronized (this) {
					//Add the message (identifier) to the inbox of each recipient

					if (!userInboxs.containsKey(name)) {
						userInboxs.put(name, new HashSet<Long>());
					}
					userInboxs.get(name).add(newID);


				}
			}
		}
		return msg.getId();
	}

	@Override
	public void deleteRegardless(long mid) {
		synchronized (this){
			allMessages.remove(mid);
			for(Map.Entry<String,Set<Long>> e : userInboxs.entrySet()){
				Set<Long> s = e.getValue();
				s.remove(mid);
			}
		}
	}

	private void spinThreads(){
		BlockingQueue<RequestHelperSoap> lq = new LinkedBlockingQueue<>();

		new Thread(() -> {
			for (;;) {
				try {
					RequestHelperSoap rh = queue.take();
					//try to send non stop
					for (; ; ) {
						try {
							System.out.println("try 1st queue");

							MessageServiceSoap messages = null;

							QName QNAME = new QName(MessageServiceSoap.NAMESPACE, MessageServiceSoap.NAME);
							Service service = Service.create( new URL(rh.getUrl() + MESSAGES_WSDL), QNAME);
							messages = service.getPort( MessageServiceSoap.class );



							//Set timeouts
							((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
							((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

							if (rh.getMethod().equals("POST")) {
								Long mid = messages.recieve_inboxes(rh.getDomain(), rh.getMsg());
								System.out.println("Success, message posted with id: " + mid);
								break;
							} else {
								messages.deleteRegardless(rh.getMid());
								System.out.println("Success, message posted with id: " + rh.getMid());
								break;
							}
						}catch ( WebServiceException wse) { //timeout
							System.out.println("Communication error");
							try {
								lq.put(rh);
								Thread.sleep( RETRY_PERIOD );//wait until attempting again.
								break;
							} catch (InterruptedException e) {
								//Nothing to be done here, if this happens we will just retry sooner.
							}
							System.out.println("Retrying to execute request.");
						} catch (MalformedURLException e) {
							System.out.printf("Malformation");
						}

					}

				} catch (InterruptedException e) {
					System.out.println("Thread Exception");
					e.printStackTrace();
				}
			}

		}).start();

		new Thread(() -> {
			for (;;) {
				try {
					RequestHelperSoap rh = lq.take();
					//try to send non stop
					for (; ; ) {
						try {
							MessageServiceSoap messages = null;

							QName QNAME = new QName(MessageServiceSoap.NAMESPACE, MessageServiceSoap.NAME);
							Service service = Service.create( new URL(rh.getUrl() + MESSAGES_WSDL), QNAME);
							messages = service.getPort( MessageServiceSoap.class );

							//Set timeouts
							((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
							((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

							if (rh.getMethod().equals("POST")) {
								Long mid = messages.recieve_inboxes(rh.getDomain(), rh.getMsg());
								System.out.println("Success, message posted with id: " + mid);
								break;
							} else {
								messages.deleteRegardless(rh.getMid());
								System.out.println("Success, message posted with id: " + rh.getMid());
								break;
							}
						}catch ( WebServiceException wse) { //timeout
							System.out.println("Communication error");
							try {
								Thread.sleep( RETRY_PERIOD ); //wait until attempting again.
							} catch (InterruptedException e) {
								//Nothing to be done here, if this happens we will just retry sooner.
							}
							System.out.println("Retrying to execute request.");
						} catch (MalformedURLException e) {
							e.printStackTrace();
						}

					}

				} catch (InterruptedException e) {
					System.out.println("Thread Exception");
					e.printStackTrace();
				}
			}

		}).start();
	}
}
