package sd1920.trab1.helpers;

import sd1920.trab1.api.Message;


public class RequestHelperSoap {

    private String url;
    private Message msg;
    private Long mid;
    private String method;
    private String domain;


    public RequestHelperSoap(String url, Message msg, String domain){
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

    public String getDomain() {
        return domain;
    }

    public Message getMsg() {
        return msg;
    }

    public Long getMid() {
        return mid;
    }

    public String getMethod() {
        return method;
    }
}
