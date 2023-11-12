import java.io.Serializable;

public class Message implements Serializable {

    public int length;
    public byte type;
    public byte[] payload;

    public Message(int length, byte type, byte[] payload) {
        this.length = length;
        this.type = type;
        this.payload = payload;
    }

}
