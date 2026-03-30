public class NodeInfo {
    public String nodeName;
    public String serviceName;
    public String ip;
    public int port;
    public long lastSeen;
/**
 * 
 * @author KFrancis05, help from claude.ai
 *
 *  
 * 
 */
    public NodeInfo(String nodeName, String serviceName, String ip, int port) {
        this.nodeName = nodeName;
        this.serviceName = serviceName;
        this.ip = ip;
        this.port = port;
        this.lastSeen = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return nodeName + "|" + serviceName + "|" + ip + ":" + port;
    }
}