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
            if (user.getDomain() == null || user.getDomain().equals("") || user.getDomain().equals(" ")) throw new WebApplicationException(Status.CONFLICT);
            if(!user.getDomain().equals(domain)) throw new WebApplicationException(Status.FORBIDDEN);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        if (user.getName()==null||user.getName().equals("")||user.getName().equals(" ")) throw new WebApplicationException(Status.CONFLICT);
        if(exists(user.getName(),false)) throw new WebApplicationException(Status.CONFLICT);

        if (user.getPwd()==null || user.getPwd().equals("")||user.getPwd().equals(" ")) throw new WebApplicationException(Status.CONFLICT);

        synchronized (this) {
            userMap.put(user.getName(), user);
        }

        return user.getName()+"@"+user.getDomain();
    }

    @Override
    public User getUser(String name, String pwd) {

        if(!exists(name,false)){
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        User u;

        synchronized (this){
            u = userMap.get(name);
        }

        if (!u.getPwd().equals(pwd)) throw new WebApplicationException(Status.FORBIDDEN);

        return u;
    }

    @Override
    public User updateUser(String name, String pwd, User user) {
        User u = getUser(name,pwd);

        if (user.getPwd() != null) u.setPwd(user.getPwd());
        if (user.getDisplayName() != null) u.setDisplayName(user.getDisplayName());

        synchronized (this){
            userMap.replace(name,u);
        }
        return  u;
    }

    @Override
    public User deleteUser(String name, String pwd) {
        User u = getUser(name,pwd);
        userMap.remove(name,u);
        return u;
    }

    private boolean exists (String name, boolean flag){
        boolean exists;
        synchronized (this){
            exists = userMap.containsKey(name);
        }
        if (flag){
            if (exists) throw new WebApplicationException(Status.FORBIDDEN);
        }
        return exists;
    }
}
