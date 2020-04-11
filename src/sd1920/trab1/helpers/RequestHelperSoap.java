package sd1920.trab1.helpers;

import sd1920.trab1.api.Message;


public class RequestHelperSoap implements RequestHelper{

    private String url;
    private Message msg;
    private Long mid;
    private String method;
    private String domain;

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public RequestHelperSoap(String url, Message msg, String domain, String utl){
        this.url = url;
        this.msg = msg;
        this.url = url;
        this.method = "POST";
        this.domain = domain;
    }

    public RequestHelperSoap(Long mid, String domain, String url){
        this.mid = mid;
        this.url = url;
        this.method = "DELETE";
        this.domain = domain;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }


    public Message getMsg() {
        return msg;
    }

    public void setMsg(Message msg) {
        this.msg = msg;
    }

    public Long getMid() {
        return mid;
    }

    public void setMid(Long mid) {
        this.mid = mid;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }
}
