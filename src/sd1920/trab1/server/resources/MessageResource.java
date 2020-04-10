package sd1920.trab1.server.resources;


import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.logging.Logger;

import javax.inject.Singleton;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;

import com.sun.xml.ws.client.BindingProviderProperties;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.rest.MessageService;
import sd1920.trab1.api.rest.UserService;
import sd1920.trab1.api.soap.MessageServiceSoap;
import sd1920.trab1.clients.GetMessageClient;
import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.helpers.RequestHelper;
import sd1920.trab1.helpers.RequestHelperSoap;

@Singleton
public class MessageResource implements MessageService {

	private Random randomNumberGenerator;

	private final BlockingQueue<RequestHelper> queue = new SynchronousQueue<>();
	private ClientConfig config;
	private final Map<Long,Message> allMessages = new HashMap<>();
	private final Map<String,Set<Long>> userInboxs = new HashMap<>();

	private static Logger Log = Logger.getLogger(MessageResource.class.getName());

	private Discovery discovery_channel;
	private String my_domain;

	public MessageResource(String ip, Discovery discovery_channel) {
		this.discovery_channel = discovery_channel;
		this.randomNumberGenerator = new Random(System.currentTimeMillis());
		this.my_domain = ip;
		this.spinThreads();
		config = new ClientConfig();
		//How much time until timeout on opening the TCP connection to the server
		config.property(ClientProperties.CONNECT_TIMEOUT, GetMessageClient.CONNECTION_TIMEOUT);
		//How much time to wait for the reply of the server after sending the request
		config.property(ClientProperties.READ_TIMEOUT, GetMessageClient.REPLY_TIMEOUT);
	}


	@Override
	public long postMessage(String pwd, Message msg) {
		User u = getUser(msg.getSender(),pwd);

		if(msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
			throw new WebApplicationException( Status.CONFLICT );
		}

		String formatedSender;
		long newID = 0;

		if (msg.getSender().contains("@"))
			formatedSender = String.format("%s <%s>",u.getDisplayName(),msg.getSender());
		else
			formatedSender = String.format("%s <%s@%s>",u.getDisplayName(),msg.getSender(),u.getDomain());

		msg.setSender( formatedSender);


		synchronized (this) {

			//Generate a new id for the message, that is not in use yet
			newID = Math.abs(randomNumberGenerator.nextLong());
			while(allMessages.containsKey(newID)) {
				newID = Math.abs(randomNumberGenerator.nextLong());
			}
			if (!allMessages.containsKey(newID)){
				allMessages.put(newID, msg);
			}

		}

		msg.setId(newID);

		try {
			requestPost(msg);
		} catch (InterruptedException e) {
			System.out.println("Interrupted");
		}

		return newID;
	}

	@Override
	public Message getMessage(String user, long mid, String pwd) {
		Log.info("Received request for message with id: " + mid +".");
		Message m = null;

		User u = getUser(user,pwd);

		synchronized (this) {
			Set<Long> s = userInboxs.getOrDefault(u.getName(),Collections.emptySet());
			for (Long l : s) {
				if (mid == l) {
					m = allMessages.get(l);
				}
			}
		}

		if(m == null) {
			throw new WebApplicationException( Status.NOT_FOUND );
		}
		return m;
	}

	@Override
	public List<Long> getMessages(String user, String pwd) {
		User u = getUser(user,pwd);

		List<Long> messages = new ArrayList<>();

		if(user == null) {
			synchronized (this) {
				messages.addAll(allMessages.keySet());
			}

		} else {
			Set<Long> mids;
			synchronized (this) {
				mids = userInboxs.getOrDefault(user, Collections.emptySet());
			}
			messages.addAll(mids);
		}
		return messages;
	}

	@Override
	public void removeFromUserInbox(String user, long mid, String pwd) {
		Message m = null;

		User u = getUser(user,pwd);

		synchronized (this) {
			Set<Long> s = userInboxs.getOrDefault(u.getName(),Collections.emptySet());
			for (Long l: s) {
				if (mid == l)
					m = allMessages.get(l);
			}
		}

		if(m == null) {
			throw new WebApplicationException( Status.NOT_FOUND );
		}

		synchronized (this) {
			Set<Long> s = userInboxs.get(u.getName());
			s.remove(mid);
		}

	}

	@Override
	public void deleteMessage(String user, long mid, String pwd) {
		Message m = null;

		User u = getUser(user,pwd);


		synchronized (this) {
			m = allMessages.get(mid);
		}


		if(m == null) {
			throw new WebApplicationException( Status.NO_CONTENT );
		}

		String[] pre = m.getSender().split(" ");
		String name = pre[pre.length-1].split("@")[0].substring(1);


		if(!(name.equals(u.getName()))){
			throw new WebApplicationException( Status.NO_CONTENT );
		}


		Set<String> s = m.getDestination();
		List<String> doms = new LinkedList<>();
		for (String d: s) {
			String domain = d.split("@")[1];
			if (!doms.contains(domain)) doms.add(domain);
		}

		System.out.println("HOLA FROM DOMS " + doms);

		for (String dom: doms) {
			try {
				requestDeletes(dom, mid);
			} catch (InterruptedException e) {
				System.out.println("Interrupted");
			}
		}

	}

	private void requestDeletes(String dom, long mid) throws InterruptedException {
		String url = "";

		URI[] uris = discovery_channel.knownUrisOf(dom);
		url = uris[uris.length-1].toString();


		Client client = ClientBuilder.newClient(config);
		WebTarget target = client.target(url).path(MessageResource.PATH).path("delete").path(String.valueOf(mid));

		RequestHelper rh = new RequestHelper(url,client,target,mid);

		queue.put(rh);

	}

	@Override
	public void deleteRegardless(long mid) {
		System.out.println("DELETErEGARDLESS");
		synchronized (this){
			allMessages.remove(mid);
			for(Map.Entry<String,Set<Long>> e : userInboxs.entrySet()){
				Set<Long> s = e.getValue();
				s.remove(mid);
			}
		}
	}

	@Override
	public void recieve_inboxes(String domain, Message msg) {
		Long newID = msg.getId();

		synchronized (this) {
			if (!allMessages.containsKey(newID)){
				allMessages.put(newID, msg);
			}
		}

		List<String> usrs = new ArrayList<>();

		for (String rec:msg.getDestination()){
			String[] spl = rec.split("@");
			String name = spl[0];
			String dom = spl[1];

			if (dom.equals(domain)){
				usrs.add(name);
			}
		}

		synchronized (this) {
			for (String name : usrs) {
					if (!userInboxs.containsKey(name)) {
						userInboxs.put(name, new HashSet<Long>());
					}
					userInboxs.get(name).add(newID);

				}
			}
	}

	public User getUser(String name_unform, String pwd){
		String url = "";
		String name = name_unform.split("@")[0];

		URI[] uris = discovery_channel.knownUrisOf(my_domain);
		url = uris[uris.length-1].toString();



		Client client = ClientBuilder.newClient(config);
		WebTarget target = client.target(url).path(UserService.PATH).path(name).queryParam("pwd",pwd);



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

	private void requestPost(Message msg) throws InterruptedException {
		String url = "";

		List<String> domains = new ArrayList<>();

		for(String recipient: msg.getDestination()) {
			String r = recipient.split("@")[1];
			if (!domains.contains(r))
				domains.add(r);
		}


		for (String domain : domains){
			URI[] uris = discovery_channel.knownUrisOf(domain);
			url = uris[uris.length-1].toString();

			Client client = ClientBuilder.newClient(config);
			WebTarget target = client.target(url).path(MessageService.PATH).path("add").path(domain);

			RequestHelper rh = new RequestHelper(url, client,target,msg);


			queue.put(rh);


		}

	}

	private void spinThreads(){
		BlockingQueue<RequestHelper> lq = new LinkedBlockingQueue<>();

		/*new Thread(() -> {
			for (;;) {
				try {
					RequestHelper rh = queue.take();
						try {
							if (rh.getMethod().equals("POST")) {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
							}
							else {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
							}
						} catch ( ProcessingException pe ) {
							System.out.println("Timeout occurred.");
							try {
								lq.put(rh);
								Thread.sleep( GetMessageClient.RETRY_PERIOD );
								break;
							} catch (InterruptedException e) {
								System.out.println("interrupted");
							}
							System.out.println("Retrying to execute request.");
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
					RequestHelper rh = lq.take();
					//try to send non stop
					try {
							if (rh.getMethod().equals("POST")) {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
							} else {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
							}
					} catch (ProcessingException pe) {
						System.out.println("Timeout occurred.");
						lq.put(rh);
						try {
							Thread.sleep(GetMessageClient.RETRY_PERIOD);
						} catch (InterruptedException e) {
							System.out.println("interrupted");
						}
						System.out.println("Retrying to execute request.");
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}


			}
		}).start();*/



		new Thread(() -> {
			for (;;) {
				try {
					RequestHelper rh = queue.take();
					//try to send non stop

						try {
							if (rh.getMethod().equals("POST")) {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
							}
							else {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
							}
						}catch ( ProcessingException pe ) {
							System.out.println("Timeout occurred.");
							try {
								lq.put(rh);
								Thread.sleep( GetMessageClient.RETRY_PERIOD );
								break;
							} catch (InterruptedException e) {
								System.out.println("interrupted");
							}
							System.out.println("Retrying to execute request.");
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
					RequestHelper rh = lq.take();
					//try to send non stop
					for (; ; ) {
						try {
							if (rh.getMethod().equals("POST")) {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
							}
							else {
								rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
							}
						}catch ( ProcessingException pe ) {
							System.out.println("Timeout occurred.");
							try {
								Thread.sleep( GetMessageClient.RETRY_PERIOD );
							} catch (InterruptedException e) {
								System.out.println("interrupted");
							}
							System.out.println("Retrying to execute request.");
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
