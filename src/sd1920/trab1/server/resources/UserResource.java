package sd1920.trab1.server.resources;

import sd1920.trab1.api.User;
import sd1920.trab1.api.rest.UserService;

import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import javax.ws.rs.core.Response.Status;

@Singleton
public class UserResource implements UserService {

    private final Map<String, User> userMap;

    private static Logger Log = Logger.getLogger(MessageResource.class.getName());

    public UserResource(){
        userMap = new HashMap<>();
    }

    @Override
    public String postUser(User user) {

        try {
            String domain = InetAddress.getLocalHost().getCanonicalHostName();
            if(!user.getDomain().equals(domain))
                throw new WebApplicationException(Status.FORBIDDEN);
            boolean exists = false;
            synchronized (this){
                exists = userMap.containsKey(user.getName());
            }
            if (exists) throw new WebApplicationException(Status.CONFLICT);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        synchronized (this) {
            userMap.put(user.getName(), user);
        }

        return user.getName()+"@"+user.getDomain();
    }

    @Override
    public User getUser(String name, String pwd) {
        boolean exists = false;
        synchronized (this){
            exists = userMap.containsKey(name);
        }
        if (!exists) throw new WebApplicationException(Status.FORBIDDEN);

        User u = null;

        synchronized (this){
            u = userMap.get(name);
        }

        if (!u.getPwd().equals(pwd)) throw new WebApplicationException(Status.FORBIDDEN);

        return u;
    }

    @Override
    public User updateUser(String name, String pwd, User user) {
        return null;
    }

    @Override
    public User deleteUser(String name, String pwd) {
        return null;
    }
}
