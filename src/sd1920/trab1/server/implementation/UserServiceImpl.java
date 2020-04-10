package sd1920.trab1.server.implementation;

import sd1920.trab1.api.User;
import sd1920.trab1.api.soap.MessagesException;
import sd1920.trab1.api.soap.UserServiceSoap;

import javax.inject.Singleton;
import javax.jws.WebService;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@WebService(serviceName=UserServiceSoap.NAME,
        targetNamespace=UserServiceSoap.NAMESPACE,
        endpointInterface=UserServiceSoap.INTERFACE)
@Singleton
public class UserServiceImpl implements UserServiceSoap {

    private final Map<String, User> userMap = new HashMap<>();
    private String my_domain;

    public UserServiceImpl(String domain){
        this.my_domain = domain;
    }

    @Override
    public String postUser(User user) throws MessagesException {
        if (user.getDomain() == null || user.getDomain().equals("") || user.getDomain().equals(" ") || !user.getDomain().equals(my_domain))throw new MessagesException("Invalid domain");
        if (user.getName()==null||user.getName().equals("")||user.getName().equals(" ")) throw new MessagesException("Invalid User Name");

        if(exists(user.getName())) throw new MessagesException("User Exists");
        if (user.getPwd()==null || user.getPwd().equals("")||user.getPwd().equals(" ")) throw new MessagesException("Invalid Password");

        synchronized (this) {
            userMap.put(user.getName(), user);
        }

        return user.getName()+"@"+user.getDomain();
    }

    @Override
    public User getUser(String name, String pwd) throws MessagesException {
        if(!exists(name)){
            throw new MessagesException("User does not exist");
        }

        User u;

        synchronized (this){
            u = userMap.get(name);
        }

        if (!u.getPwd().equals(pwd)) throw new MessagesException("Invalid Password");

        return u;
    }

    @Override
    public User updateUser(String name, String pwd, User user) throws MessagesException {
        User u = getUser(name,pwd);

        if (user.getPwd() != null) u.setPwd(user.getPwd());
        if (user.getDisplayName() != null) u.setDisplayName(user.getDisplayName());

        synchronized (this){
            userMap.replace(name,u);
        }
        return  u;
    }

    @Override
    public User deleteUser(String name, String pwd) throws MessagesException {
        User u = getUser(name,pwd);
        userMap.remove(name,u);
        return u;
    }

    private boolean exists (String name){
        synchronized (this){
            return userMap.containsKey(name);
        }
    }
}
