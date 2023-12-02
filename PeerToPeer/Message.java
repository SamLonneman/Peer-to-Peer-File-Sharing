import java.io.Serializable;

public class Message implements Serializable
{
    public int length;
    public byte type;
    public byte[] payload;

    public Message(byte type, byte[] payload) {
        this.length = payload.length + 1;
        this.type = type;
        this.payload = payload;
    }

    public Message(byte type) {
        this.length = 1;
        this.type = type;
        this.payload = new byte[0];
    }
}
