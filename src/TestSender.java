import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class TestSender {
    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(1001);
            Socket socket = serverSocket.accept();
            serverSocket.close();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            objectOutputStream.writeObject(new HandshakeMessage(56));
            objectOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Sleep for 5 seconds to allow the receiver to receive the message
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
