import java.net.*;
import java.util.ArrayList;
import java.io.*;

public class Tracker
{
	private static final int port = 8000;
    private static ArrayList<Integer> peers = new ArrayList<Integer>();
    private static int counter = 0;

	public static void main(String[] args) throws Exception
    {
        ServerSocket listener = new ServerSocket(port);
        try {
            while(true) {
                new Registrar(listener.accept()).start();
            }
        } finally {
            listener.close();
        }
    }

    private static class Registrar extends Thread
    {
        private Socket socket;
        private int peerID;
        private ObjectInputStream in;
        private ObjectOutputStream out;

        public Registrar(Socket socket)
        {
            this.socket = socket;
        }

        public void run()
        {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());
                peerID = counter++;
                sendMessage(Integer.toString(peerID));
                peers.add(peerID);
                System.out.println("Peer " + peerID + " accepted: " + peers);





                System.out.println("Peer " + peerID + " says: " + (String)in.readObject());
            } catch (ClassNotFoundException e) {
                System.err.println("Class not found");
            } catch(IOException ioException) {
                System.out.println("Disconnect with peer " + peerID);
            } finally {
                try {
                    in.close();
                    out.close();
                    socket.close();
                } catch(IOException ioException) {
                    System.out.println("Disconnect with peer " + peerID);
                }
                peers.remove((Object)peerID);
                System.out.println("Peer " + peerID + " disconnected: " + peers);
            }
        }

        //send a message to the output stream
        public void sendMessage(String msg)
        {
            try {
                out.writeObject(msg);
                out.flush();
            } catch(IOException ioException) {
                ioException.printStackTrace();
            }
        }
    }
}
