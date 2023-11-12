import java.io.ObjectInputStream;
import java.net.Socket;

public class TestReceiver {
    public static void main(String[] args) {
        try {
            Socket socket = new Socket("localhost", 1001);
            ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());
            HandshakeMessage handshakeMessage = (HandshakeMessage) objectInputStream.readObject();
            System.out.println("Peer ID: " + handshakeMessage.peerId);
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
