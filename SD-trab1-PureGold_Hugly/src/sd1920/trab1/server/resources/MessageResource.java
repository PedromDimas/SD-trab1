package sd1920.trab1.server.resources;

import java.io.IOException;
import java.net.*;
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
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.clients.GetMessageClient;
import sd1920.trab1.clients.utils.MessageUtills;
import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.helpers.RequestHelper;
import sd1920.trab1.helpers.RequestHelperSoap;

@Singleton
public class MessageResource implements MessageService {

    private Random randomNumberGenerator;

    private final BlockingQueue<RequestHelper> queueRest = new SynchronousQueue<>();
    private final BlockingQueue<RequestHelperSoap> queueSoap = new SynchronousQueue<>();
    private ClientConfig config;
    private final Map<Long, Message> allMessages = new HashMap<>();
    private final Map<String, Set<Long>> userInboxs = new HashMap<>();

    private static Logger Log = Logger.getLogger(MessageResource.class.getName());

    private Discovery discovery_channel;


    public final static long RETRY_PERIOD = 1000;
    public final static int CONNECTION_TIMEOUT = 1000;
    public final static int REPLY_TIMEOUT = 600;

    private static final String MESSAGES_WSDL = "/messages/?wsdl";


    public MessageResource(Discovery discovery_channel) {
        this.discovery_channel = discovery_channel;
        this.randomNumberGenerator = new Random(System.currentTimeMillis());
        this.spinThreadsRest();
        this.spinThreadsSoap();
        config = new ClientConfig();
        //How much time until timeout on opening the TCP connection to the server
        config.property(ClientProperties.CONNECT_TIMEOUT, GetMessageClient.CONNECTION_TIMEOUT);
        //How much time to wait for the reply of the server after sending the request
        config.property(ClientProperties.READ_TIMEOUT, GetMessageClient.REPLY_TIMEOUT);
    }


    @Override
    public long postMessage(String pwd, Message msg) {

        Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: " + msg.getSubject() + ")");

        User u = getUser(msg.getSender(), pwd);

        //Check if message is valid, if not return HTTP CONFLICT (409)
        if (msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
            Log.info("Message was rejected due to lack of recepients.");
            throw new WebApplicationException(Status.CONFLICT);
        }


        String formattedSender;
        long newID;

        if (msg.getSender().contains("@"))
            formattedSender = u.getDisplayName() + " <" + msg.getSender() + ">";
        else
            formattedSender = u.getDisplayName() + " <" + msg.getSender() + "@" + u.getDomain() + ">";

        msg.setSender(formattedSender);


        synchronized (this) {

            //Generate a new id for the message, that is not in use yet
            newID = Math.abs(randomNumberGenerator.nextLong());
            while (allMessages.containsKey(newID)) {
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
            System.out.println("Interrupted");
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //Return the id of the registered message to the client (in the body of a HTTP Response with 200)
        Log.info("Recorded message with identifier: " + newID);
        return newID;
    }

    private Message create_error_message(Message msg, String name) {
        Message m = new Message();
        m.setContents(msg.getContents());
        m.setCreationTime(msg.getCreationTime());
        m.setDestination(msg.getDestination());
        m.setSender(msg.getSender());
        long newID;
        synchronized (this) {

            //Generate a new id for the message, that is not in use yet
            newID = Math.abs(randomNumberGenerator.nextLong());
            while (allMessages.containsKey(newID)) {
                newID = Math.abs(randomNumberGenerator.nextLong());
            }

        }
        m.setId(newID);
        String address = "";
        for (String u : msg.getDestination()) {
            if (name.equals(u.split("@")[0])) address = u;
        }

        String subject = String.format("FALHA NO ENVIO DE %s PARA %s", msg.getId(), address);
        m.setSubject(subject);
        return m;
    }

    private boolean check_user_exists(String name) {
        String url = "";
        try {
            String domain = InetAddress.getLocalHost().getCanonicalHostName();
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }


        Client client = ClientBuilder.newClient(config);
        WebTarget target = client.target(url).path(UserService.PATH).path("ex").path(name);


        short retries = 0;

        while (retries < GetMessageClient.MAX_RETRIES) {
            try {

                Response r = target.request().accept(MediaType.APPLICATION_JSON).get();
                System.out.println("RESPONSE + " + r);
                if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
                    System.out.println("GotBoolean");
                    boolean e = r.readEntity(Boolean.class);
                    System.out.println("User " + name + " exist? " + e);
                    return e;
                } else
                    throw new WebApplicationException(r.getStatus());

            } catch (ProcessingException pe) { //Error in communication with server
                System.out.println("Timeout occurred.");
                //pe.printStackTrace(); //Could be removed
                retries++;
                try {
                    Thread.sleep(GetMessageClient.RETRY_PERIOD); //wait until attempting again.
                } catch (InterruptedException e) {
                    System.out.println("interrupted");
                    //Nothing to be done here, if this happens we will just retry sooner.
                }
                System.out.println("Retrying to execute request.");
            }
        }
        throw new WebApplicationException(Status.FORBIDDEN);
    }

    @Override
    public Message getMessage(String user, long mid, String pwd) {
        Log.info("Received request for message with id: " + mid + ".");
        Message m = null;

        User u = getUser(user, pwd);

        synchronized (this) {
            Set<Long> s = userInboxs.get(u.getName());
            for (Long l : s) {
                if (mid == l)
                    m = allMessages.get(l);
            }
        }

        if (m == null) {  //check if message exists
            Log.info("Requested message does not exists.");
            throw new WebApplicationException(Status.NOT_FOUND); //if not send HTTP 404 back to client
        }

        Log.info("Returning requested message to user.");
        return m; //Return message to the client with code HTTP 200
    }

    public byte[] getMessageBody(long mid) {
        Log.info("Received request for body of message with id: " + mid + ".");
        byte[] contents = null;
        synchronized (this) {
            Message m = allMessages.get(mid);
            if (m != null)
                contents = m.getContents();
        }


        if (contents != null) { //implicitaly checks if message exists
            Log.info("Requested message does not exists.");
            throw new WebApplicationException(Status.NOT_FOUND); //if not send HTTP 404 back to client
        }

        Log.info("Returning requested message body to user.");
        return contents; //Return message contents to the client with code HTTP 200
    }

    @Override
    public List<Long> getMessages(String user, String pwd) {
        Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
        getUser(user, pwd);
        List<Long> messages = new ArrayList<>();
        if (user == null) {
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

        return messages;
    }

    @Override
    public void removeFromUserInbox(String user, long mid, String pwd) {
        Message m = null;

        User u = getUser(user, pwd);

        synchronized (this) {
            Set<Long> s = userInboxs.get(u.getName());
            for (Long l : s) {
                if (mid == l)
                    m = allMessages.get(l);
            }
        }

        if (m == null) {  //check if message exists
            Log.info("Requested message does not exists.");
            throw new WebApplicationException(Status.NOT_FOUND); //if not send HTTP 404 back to client
        }

        synchronized (this) {
            Set<Long> s = userInboxs.get(u.getName());
            s.remove(mid);
        }

        throw new WebApplicationException(Status.NO_CONTENT);
    }

    @Override
    public void deleteMessage(String user, long mid, String pwd) {
        Message m;
        User u = getUser(user, pwd);

        synchronized (this) {
            m = allMessages.get(mid);
        }

        if (m == null) {  //check if message exists
            System.out.println("Requested message does not exists." + mid);
            throw new WebApplicationException(Status.NO_CONTENT); //if not send HTTP 404 back to client
        }

        String[] pre = m.getSender().split(" ");
        String name = pre[pre.length - 1].split("@")[0].substring(1);


        if (!(name.equals(u.getName()))) {
            System.out.println("names Do not match");
            throw new WebApplicationException(Status.NO_CONTENT); //if not send HTTP 404 back to client
        }


        Set<String> s = m.getDestination();
        List<String> doms = new LinkedList<>();
        for (String d : s) {
            String domain = d.split("@")[1];
            if (!doms.contains(domain)) doms.add(domain);
        }

        for (String dom : doms) {
            try {
                requestDeletes(dom, mid);
            } catch (InterruptedException e) {
                System.out.println("Interrupted");
            }
        }

    }

    private void requestDeletes(String dom, long mid) throws InterruptedException {
        String url;

        URI[] uris = discovery_channel.knownUrisOf(dom);
        url = uris[uris.length - 1].toString();

        Client client = ClientBuilder.newClient(config);
        WebTarget target = client.target(url).path(MessageResource.PATH).path("delete").path(String.valueOf(mid));

        String serviceType = url.split("/")[3];

        if (serviceType.equals("rest")) {
            RequestHelper rh = new RequestHelper(url, client, target, mid);
            queueRest.put(rh);
        } else {
            RequestHelperSoap rh = new RequestHelperSoap(mid, dom, url);
            queueSoap.put(rh);
        }

    }

    @Override
    public void deleteRegardless(long mid) {
        synchronized (this) {
            allMessages.remove(mid);
            for (Map.Entry<String, Set<Long>> e : userInboxs.entrySet()) {
                Set<Long> s = e.getValue();
                s.remove(mid);
            }
        }
    }

    @Override
    public List<String> receive_inboxes(String domain, Message msg) {
        List<String> missMatches = new ArrayList<>();
        Long newID = msg.getId();

        List<String> usrs = new ArrayList<>();

        for (String rec : msg.getDestination()) {
            String[] spl = rec.split("@");
            String name = spl[0];
            String dom = spl[1];

            if (dom.equals(domain)) {
                usrs.add(name);
            }
        }

        if (usrs.size() != 0) {
            for (String name : usrs) {
                //Check if user exists
                if (!check_user_exists(name)) {
                    missMatches.add(name);
                } else {
                    synchronized (this) {
                        //Add the message (identifier) to the inbox of each recipient
                        if (!userInboxs.containsKey(name)) {
                            userInboxs.put(name, new HashSet<>());
                        }
                        userInboxs.get(name).add(newID);
                    }
                }
            }
            synchronized (this) {
                if (!allMessages.containsKey(newID))
                    allMessages.put(newID, msg);
            }
        }
        return missMatches;
    }

    public User getUser(String name_unform, String pwd) {
        String url = "";
        String name = name_unform.split("@")[0];
        try {
            String domain = InetAddress.getLocalHost().getCanonicalHostName();
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        Client client = ClientBuilder.newClient(config);
        WebTarget target = client.target(url).path(UserService.PATH).path(name).queryParam("pwd", pwd);

        short retries = 0;

        while (retries < GetMessageClient.MAX_RETRIES) {
            try {

                Response r = target.request()
                        .accept(MediaType.APPLICATION_JSON)
                        .get();
                if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
                    System.out.println("Success:");
                    return r.readEntity(User.class);
                } else
                    throw new WebApplicationException(r.getStatus());

            } catch (ProcessingException pe) { //Error in communication with server
                System.out.println("Timeout occurred.");
                retries++;
                try {
                    Thread.sleep(GetMessageClient.RETRY_PERIOD); //wait until attempting again.
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
        String url;

        List<String> domains = new ArrayList<>();

        for (String recipient : msg.getDestination()) {
        	String dom = recipient.split("@")[1];
            if (!domains.contains(dom))
                domains.add(dom);
        }

        for (String domain : domains) {
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();

            Client client = ClientBuilder.newClient(config);
            WebTarget target = client.target(url).path(MessageService.PATH).path("add").path(domain);

            String serviceType = url.split("/")[3];
            if (serviceType.equals("rest")) {
                RequestHelper rh = new RequestHelper(url, client, target, msg);
                queueRest.put(rh);
            } else {
                RequestHelperSoap rh = new RequestHelperSoap(url, msg, domain);
                queueSoap.put(rh);
            }
        }
    }

    private void spinThreadsRest() {
        BlockingQueue<RequestHelper> lq = new LinkedBlockingQueue<>();

        new Thread(() -> {
            for (;;) {
                try {
                    RequestHelper rh = queueRest.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            Response r;
                            if (rh.getMethod().equals("POST")) {
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
                                if (r.getStatus() == Status.OK.getStatusCode()) {
                                    List<String> missmatches = r.readEntity(ArrayList.class);

                                    if (missmatches.isEmpty()) break;

                                    for (String u : missmatches) {
                                        Message m = create_error_message(rh.getMsg(), u);
                                        String[] pre = m.getSender().split(" ");
                                        String S_name = pre[pre.length - 1].split("@")[0].substring(1);

                                        synchronized (this) {
                                            if (!allMessages.containsKey(m.getId()))
                                                allMessages.put(m.getId(), m);
                                        }
                                        synchronized (this) {
                                            //Add the message (identifier) to the inbox of each recipient
                                            if (!userInboxs.containsKey(S_name)) {
                                                userInboxs.put(S_name, new HashSet<>());
                                            }
                                            userInboxs.get(S_name).add(m.getId());
                                        }
                                    }
                                    break;
                                } else
                                    throw new WebApplicationException(r.getStatus());
                            } else {
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
                                if (r.getStatus() == Status.NO_CONTENT.getStatusCode()) {
                                    break;
                                } else
                                    throw new WebApplicationException(r.getStatus());
                            }
                        } catch (ProcessingException pe) {
                            System.out.println("Timeout occurred.");
                            try {
                                lq.put(rh);
                                Thread.sleep(GetMessageClient.RETRY_PERIOD);
                                break;
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


        new Thread(() -> {
            for (;;) {
                try {
                    RequestHelper rh = lq.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            Response r;
                            if (rh.getMethod().equals("POST")) {
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
                                if (r.getStatus() == Status.OK.getStatusCode()) {
                                    List<String> missmatches = r.readEntity(ArrayList.class);

                                    if (missmatches.isEmpty()) break;

                                    for (String u : missmatches) {
                                        Message m = create_error_message(rh.getMsg(), u);
                                        String[] pre = m.getSender().split(" ");
                                        String S_name = pre[pre.length - 1].split("@")[0].substring(1);

                                        synchronized (this) {
                                            if (!allMessages.containsKey(m.getId()))
                                                allMessages.put(m.getId(), m);
                                        }
                                        synchronized (this) {
                                            //Add the message (identifier) to the inbox of each recipient
                                            if (!userInboxs.containsKey(S_name)) {
                                                userInboxs.put(S_name, new HashSet<>());
                                            }
                                            userInboxs.get(S_name).add(m.getId());
                                        }
                                    }
                                    break;
                                } else
                                    throw new WebApplicationException(r.getStatus());
                            } else {
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).delete();
                                if (r.getStatus() == Status.NO_CONTENT.getStatusCode()) {
                                    break;
                                } else
                                    throw new WebApplicationException(r.getStatus());
                            }
                        } catch (ProcessingException pe) {
                            System.out.println("Timeout occurred.");
                            lq.put(rh);
                            try {
                                Thread.sleep(GetMessageClient.RETRY_PERIOD);
                                break;
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

    private void spinThreadsSoap() {
        BlockingQueue<RequestHelperSoap> lq = new LinkedBlockingQueue<>();

        new Thread(() -> {
            for (;;) {
                try {
                    RequestHelperSoap rh = queueSoap.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            MessageServiceSoap messages;

                            URL url = new URL(rh.getUrl());

                            URLConnection con = url.openConnection();
                            con.setConnectTimeout(CONNECTION_TIMEOUT);
                            con.connect();

                            QName QNAME = new QName(MessageServiceSoap.NAMESPACE, MessageServiceSoap.NAME);
                            Service service = Service.create(new URL(rh.getUrl() + MESSAGES_WSDL), QNAME);
                            messages = service.getPort(MessageServiceSoap.class);


                            //Set timeouts
                            ((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
                            ((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

                            if (rh.getMethod().equals("POST")) {
                                List<String> missMatches = messages.receive_inboxes(rh.getDomain(), rh.getMsg());

                                if (missMatches.isEmpty()) break;

                                for (String u : missMatches) {
                                    Message m = create_error_message(rh.getMsg(), u);
                                    String[] pre = m.getSender().split(" ");
                                    String S_name = pre[pre.length - 1].split("@")[0].substring(1);

                                    synchronized (this) {
                                        if (!allMessages.containsKey(m.getId()))
                                            allMessages.put(m.getId(), m);
                                    }
                                    synchronized (this) {
                                        //Add the message (identifier) to the inbox of each recipient
                                        if (!userInboxs.containsKey(S_name)) {
                                            userInboxs.put(S_name, new HashSet<>());
                                        }
                                        userInboxs.get(S_name).add(m.getId());
                                    }
                                }
                                break;
                            } else {
                                messages.deleteRegardless(rh.getMid());
                                System.out.println("Success, message posted with id: " + rh.getMid());
                                break;
                            }
                        } catch (IOException | WebServiceException wse) { //timeout
                            System.out.println("Communication error");
                            try {
                                lq.put(rh);
                                Thread.sleep(RETRY_PERIOD);//wait until attempting again.
                                break;
                            } catch (InterruptedException e) {
                                //Nothing to be done here, if this happens we will just retry sooner.
                            }
                            System.out.println("Retrying to execute request.");
                        } catch (MessagesException e) {
                            e.printStackTrace();
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
                    for (;;) {
                        try {
                            MessageServiceSoap messages;

                            URL url = new URL(rh.getUrl());

                            URLConnection con = url.openConnection();
                            con.setConnectTimeout(CONNECTION_TIMEOUT);
                            con.connect();


                            QName QNAME = new QName(MessageServiceSoap.NAMESPACE, MessageServiceSoap.NAME);
                            Service service = Service.create(new URL(rh.getUrl() + MESSAGES_WSDL), QNAME);
                            messages = service.getPort(MessageServiceSoap.class);

                            //Set timeouts
                            ((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
                            ((BindingProvider) messages).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

                            if (rh.getMethod().equals("POST")) {
                                List<String> missMatches = messages.receive_inboxes(rh.getDomain(), rh.getMsg());

                                if (missMatches.isEmpty()) break;

                                for (String u : missMatches) {
                                    Message m = create_error_message(rh.getMsg(), u);
                                    String[] pre = m.getSender().split(" ");
                                    String S_name = pre[pre.length - 1].split("@")[0].substring(1);

                                    synchronized (this) {
                                        if (!allMessages.containsKey(m.getId()))
                                            allMessages.put(m.getId(), m);
                                    }
                                    synchronized (this) {
                                        //Add the message (identifier) to the inbox of each recipient
                                        if (!userInboxs.containsKey(S_name)) {
                                            userInboxs.put(S_name, new HashSet<>());
                                        }
                                        userInboxs.get(S_name).add(m.getId());
                                    }
                                }
                                break;
                            } else {
                                messages.deleteRegardless(rh.getMid());
                                System.out.println("Success, message posted with id: " + rh.getMid());
                                break;
                            }
                        } catch (IOException | WebServiceException wse) { //timeout
                            System.out.println("Communication error");
                            lq.put(rh);
                            try {
                                Thread.sleep(RETRY_PERIOD); //wait until attempting again.
                                break;
                            } catch (InterruptedException e) {
                                //Nothing to be done here, if this happens we will just retry sooner.
                            }
                            System.out.println("Retrying to execute request.");
                        } catch (MessagesException e) {
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
