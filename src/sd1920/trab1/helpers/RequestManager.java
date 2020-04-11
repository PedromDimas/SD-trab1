package sd1920.trab1.helpers;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RequestManager {
    private Map<String, BlockingQueue<RequestHelper>> map;

    public RequestManager(){
        map = new HashMap<>();
    }

    public void addRequest(String domain, RequestHelper rh) throws InterruptedException {
        synchronized (this) {
            if (!map.containsKey(domain)) {
                map.put(domain, new LinkedBlockingQueue<>());
            }
            map.get(domain).put(rh);
        }
    }

    public void putBack(String domain, BlockingQueue<RequestHelper> bq){
        synchronized (this) {
            map.put(domain, bq);
        }
    }

    public void delete(String domain){
        map.remove(domain);
    }

    public List<BlockingQueue<RequestHelper>> getRequests(List<String> l){
        List<BlockingQueue<RequestHelper>> lbq = new LinkedList<>();
        synchronized (this) {
            for (String s : l) {
                if (map.containsKey(s)) {
                    BlockingQueue<RequestHelper> bq = new LinkedBlockingQueue<>();
                    map.get(s).drainTo(bq);
                }
            }
        }
        return lbq;
    }

}
