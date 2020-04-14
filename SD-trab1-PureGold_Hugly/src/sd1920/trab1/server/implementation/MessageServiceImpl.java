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

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.Service;

import com.sun.xml.ws.client.BindingProviderProperties;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import sd1920.trab1.api.Message;
import sd1920.trab1.api.User;
import sd1920.trab1.api.rest.MessageService;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.api.soap.MessageServiceSoap;
import sd1920.trab1.api.soap.UserServiceSoap;
import sd1920.trab1.clients.GetMessageClient;
import sd1920.trab1.clients.utils.MessageUtills;
import sd1920.trab1.discovery.Discovery;
import sd1920.trab1.helpers.RequestHelper;
import sd1920.trab1.helpers.RequestHelperSoap;
import sd1920.trab1.server.resources.MessageResource;


@WebService(serviceName = MessageServiceSoap.NAME,
        targetNamespace = MessageServiceSoap.NAMESPACE,
        endpointInterface = MessageServiceSoap.INTERFACE)
@Singleton
public class MessageServiceImpl implements MessageServiceSoap {

    private Random randomNumberGenerator;
    private final BlockingQueue<RequestHelper> queueRest = new SynchronousQueue<>();
    private final BlockingQueue<RequestHelperSoap> queueSoap = new SynchronousQueue<>();
    private final Map<Long, Message> allMessages;
    private final Map<String, Set<Long>> userInboxs;

    private static Logger Log = Logger.getLogger(MessageServiceImpl.class.getName());

    private Discovery discovery_channel;

    public final static int MAX_RETRIES = 3;
    public final static long RETRY_PERIOD = 1000;
    public final static int CONNECTION_TIMEOUT = 1000;
    public final static int REPLY_TIMEOUT = 600;

    private static final String MESSAGES_WSDL = "/messages/?wsdl";
    private static final String USER_WSDL = "/users/?wsdl";

    private ClientConfig config;

    public MessageServiceImpl(Discovery discovery_channel) {
        this.randomNumberGenerator = new Random(System.currentTimeMillis());
        this.allMessages = new HashMap<>();
        this.userInboxs = new HashMap<>();
        this.discovery_channel = discovery_channel;
        this.spinThreadsRest();
        this.spinThreadsSoap();

        config = new ClientConfig();
        //How much time until timeout on opening the TCP connection to the server
        config.property(ClientProperties.CONNECT_TIMEOUT, GetMessageClient.CONNECTION_TIMEOUT);
        //How much time to wait for the reply of the server after sending the request
        config.property(ClientProperties.READ_TIMEOUT, GetMessageClient.REPLY_TIMEOUT);

    }


    @Override
    public long postMessage(String pwd, Message msg) throws MessagesException {
        Log.info("Received request to register a new message (Sender: " + msg.getSender() + "; Subject: " + msg.getSubject() + ")");
        User u = null;
        try {
            u = getUser(msg.getSender(), pwd);
        } catch (IOException e) {
            e.printStackTrace();
        }


        //Check if message is valid, if not return HTTP CONFLICT (409)
        if (msg.getSender() == null || msg.getDestination() == null || msg.getDestination().size() == 0) {
            System.out.println("Message no Exist");
            throw new MessagesException("Message does not exists.");
        }

        long newID;
        String formattedSender;

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
            e.printStackTrace();
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

    private boolean check_user_exists(String name) throws MessagesException {
        String url = "";
        try {
            String domain = InetAddress.getLocalHost().getCanonicalHostName();
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }


        //Form Connection with server
        UserServiceSoap us;
        try {
            QName QNAME = new QName(UserServiceSoap.NAMESPACE, UserServiceSoap.NAME);
            Service service = Service.create(new URL(url + USER_WSDL), QNAME);
            us = service.getPort(sd1920.trab1.api.soap.UserServiceSoap.class);
        } catch (WebServiceException | MalformedURLException wse) {
            System.err.println("Could not contact the server: " + wse.getMessage());
            throw new MessagesException("Connection Error");
        }

        //Set timeouts
        ((BindingProvider) us).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
        ((BindingProvider) us).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

        //Execute connection

        while (true) {
            try {
                return us.exists(name);
            } catch (WebServiceException wse) { //timeout
                System.out.println("Communication error");
                wse.printStackTrace(); //could be removed.
                try {
                    Thread.sleep(RETRY_PERIOD); //wait until attempting again.
                } catch (InterruptedException e) {
                    //Nothing to be done here, if this happens we will just retry sooner.
                }
                System.out.println("Retrying to execute request.");
            }
        }
    }

    private void requestPost(Message msg) throws InterruptedException {
        String url;

        List<String> domains = new ArrayList<>();

        for (String recipient : msg.getDestination()) {
            if (!domains.contains(recipient.split("@")[1]))
                domains.add(recipient.split("@")[1]);
        }

        for (String domain : domains) {
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();


            String serviceType = url.split("/")[3];
            if (serviceType.equals("rest")) {
                Client client = ClientBuilder.newClient(config);
                WebTarget target = client.target(url).path(MessageService.PATH).path("add").path(domain);
                RequestHelper rh = new RequestHelper(url, client, target, msg);
                queueRest.put(rh);
            } else {
                RequestHelperSoap rh = new RequestHelperSoap(url, msg, domain);
                queueSoap.put(rh);
            }
        }
    }

    private User getUser(String sender, String pwd) throws IOException, MessagesException {
        //Get the server URL
        String url = "";
        String name = sender.split("@")[0];
        try {
            String domain = InetAddress.getLocalHost().getCanonicalHostName();
            URI[] uris = discovery_channel.knownUrisOf(domain);
            url = uris[uris.length - 1].toString();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        //Form Connection with server
        UserServiceSoap us;
        try {
            QName QNAME = new QName(UserServiceSoap.NAMESPACE, UserServiceSoap.NAME);
            Service service = Service.create(new URL(url + USER_WSDL), QNAME);
            us = service.getPort(sd1920.trab1.api.soap.UserServiceSoap.class);
        } catch (WebServiceException wse) {
            System.err.println("Could not contact the server: " + wse.getMessage());
            throw new MessagesException("Connection Error");
        }

        //Set timeouts
        ((BindingProvider) us).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
        ((BindingProvider) us).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

        //Execute connection
        short retries = 0;
        boolean success = false;
        User u = null;
        while (!success && retries < MAX_RETRIES) {
            try {
                u = us.getUser(name, pwd);
                System.out.println("Success:");
                success = true;
            } catch (MessagesException me) { //Error executing the method in the server
                System.out.println("User does not exist");
                throw new MessagesException("User does not exists.");
            } catch (WebServiceException wse) { //timeout
                System.out.println("Communication error");
                wse.printStackTrace(); //could be removed.
                retries++;
                try {
                    Thread.sleep(RETRY_PERIOD); //wait until attempting again.
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
        Log.info("Received request for message with id: " + mid + ".");
        Message m = null;
        User u = null;
        try {
            u = getUser(user, pwd);
        } catch (IOException e) {
            e.printStackTrace();
        }

        synchronized (this) {
            Set<Long> s = userInboxs.get(u.getName());
            for (Long l : s) {
                if (mid == l)
                    m = allMessages.get(l);
            }
        }

        if (m == null) {  //check if message exists
            Log.info("Requested message does not exist.");
            throw new MessagesException("Message Not Found");//if not send HTTP 404 back to client
        }

        return m; //Return message to the client with code HTTP 200
    }

    @Override
    public List<Long> getMessages(String user, String pwd) throws MessagesException {
        Log.info("Received request for messages with optional user parameter set to: '" + user + "'");
        try {
            getUser(user, pwd);
        } catch (IOException e) {
            e.printStackTrace();
        }

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

        System.out.println(messages + " MESSAGES");

        return messages;
    }

    @Override
    public void removeFromUserInbox(String user, String pwd, long mid) throws MessagesException {
        Message m = null;
        User u = null;
        try {
            u = getUser(user, pwd);
        } catch (IOException e) {
            e.printStackTrace();
        }

        synchronized (this) {
            Set<Long> s = userInboxs.get(u.getName());
            for (Long l : s) {
                if (mid == l)
                    m = allMessages.get(l);
            }
        }

        if (m == null) {  //check if message exists
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
        Message m;
        User u = null;
        try {
            u = getUser(user, pwd);
        } catch (IOException e) {
            e.printStackTrace();
        }


        synchronized (this) {
            m = allMessages.get(mid);
        }


        if (m == null) {  //check if message exists
            System.out.println("Requested message does not exists." + mid);
            //throw new MessagesException("Message Not Found"); //if not send HTTP 404 back to client
            return;
        }


        String[] pre = m.getSender().split(" ");
        String name = pre[pre.length - 1].split("@")[0].substring(1);


        if (!(name.equals(u.getName()))) {
            System.out.println("names Do not match");
            return;
        }


        Set<String> s = m.getDestination();
        List<String> doms = new LinkedList<>();
        for (String d : s) {
            String domain = d.split("@")[1];
            if (!doms.contains(domain)) doms.add(domain);
        }

        System.out.println("DOMS " + doms);


        for (String dom : doms) {
            try {
                requestDeletes(dom, mid);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    private void requestDeletes(String dom, long mid) throws InterruptedException {
        String url;

        URI[] uris = discovery_channel.knownUrisOf(dom);
        url = uris[uris.length - 1].toString();


        String serviceType = url.split("/")[3];


        if (serviceType.equals("rest")) {
            Client client = ClientBuilder.newClient(config);
            WebTarget target = client.target(url).path(MessageResource.PATH).path("delete").path(String.valueOf(mid));
            RequestHelper rh = new RequestHelper(url, client, target, mid);
            queueRest.put(rh);
        } else {
            RequestHelperSoap rh = new RequestHelperSoap(mid, dom, url);
            queueSoap.put(rh);
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
            synchronized (this) {
                if (!allMessages.containsKey(newID))
                    allMessages.put(newID, msg);
            }

            for (String name : usrs) {
                //Check if user exists
                boolean ex = false;
                try {
                    ex = check_user_exists(name);
                } catch (MessagesException e) {
                    e.printStackTrace();
                }

                if (!ex) {
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
        }
        return missMatches;
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

    private void spinThreadsRest() {
        BlockingQueue<RequestHelper> lq = new LinkedBlockingQueue<>();

        new Thread(() -> {
            for (; ; ) {
                try {
                    RequestHelper rh = queueRest.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            Response r;
                            if (rh.getMethod().equals("POST")) {
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
                                if (r.getStatus() == Response.Status.OK.getStatusCode()) {
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
                                if (r.getStatus() == Response.Status.NO_CONTENT.getStatusCode()) {
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
            for (; ; ) {
                try {
                    RequestHelper rh = lq.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            Response r;
                            if (rh.getMethod().equals("POST")) {
                                System.out.println("Le Post Soap");
                                r = rh.getTarget().request().accept(MediaType.APPLICATION_JSON).post(Entity.entity(rh.getMsg(), MediaType.APPLICATION_JSON));
                                if (r.getStatus() == Response.Status.OK.getStatusCode()) {
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
                                if (r.getStatus() == Response.Status.NO_CONTENT.getStatusCode()) {
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
            for (; ; ) {
                try {
                    RequestHelperSoap rh = queueSoap.take();
                    //try to send non stop
                    for (; ; ) {
                        try {
                            System.out.println("try 1st queue");

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
