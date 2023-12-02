import java.io.Serializable;

public class HandshakeMessage implements Serializable {

    public static String header = "P2PFILESHARINGPROJ";
    public static String zeroBits = "0000000000";
    public int peerId;

    public HandshakeMessage(int peerId) {
        this.peerId = peerId;
    }
    
}
