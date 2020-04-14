package sd1920.trab1.helpers;


import sd1920.trab1.api.Message;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

public class RequestHelper {

    private String url;
    private Client client;
    private WebTarget target;
    private Message msg;
    private Long mid;
    private String method;

    public RequestHelper(String url, Client client, WebTarget target, Message msg){
        this.client = client;
        this.msg = msg;
        this.target = target;
        this.url = url;
        this.method = "POST";
    }

    public RequestHelper(String url, Client client, WebTarget target, Long mid){
        this.client = client;
        this.mid = mid;
        this.target = target;
        this.url = url;
        this.method = "DELETE";
    }

    public WebTarget getTarget() {
        return target;
    }

    public Message getMsg() {
        return msg;
    }

    public String getMethod() {
        return method;
    }

}
