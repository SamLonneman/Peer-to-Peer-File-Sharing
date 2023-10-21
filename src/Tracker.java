import java.net.*;
import java.io.*;
import java.util.HashMap;

public class Tracker
{
	private static final int port = 8000;
    private static HashMap<Integer, InetAddress> peers = new HashMap<>();
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
        private ObjectInputStream in;
        private ObjectOutputStream out;
        private int peerID;
        private InetAddress peerIP;

        public Registrar(Socket socket)
        {
            this.socket = socket;
        }

        public void run()
        {
            try {
                // Setup
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());
                // Prepare a new entry for this peer
                peerID = counter++;
                peerIP = socket.getInetAddress();
                // Tell the peer their ID
                sendMessage(Integer.toString(peerID));
                // Tell the peer how many peers are about to be sent
                sendMessage(Integer.toString(peers.size()));
                // Send the peer list
                for (Integer key : peers.keySet()) {
                    sendMessage(Integer.toString(key));
                    sendMessage(peers.get(key).toString());
                }
                // Add the peer to the map
                peers.put(peerID, peerIP);
                System.out.println("Peer " + peerID + " accepted: " + peers);




                // Stay alive
                System.out.println("Peer " + peerID + " says: " + (String)in.readObject());
            } catch (ClassNotFoundException e) {
                System.err.println("Class not found");
            } catch(IOException ioException) {
                ;
            } finally {
                try {
                    in.close();
                    out.close();
                    socket.close();
                } catch(IOException ioException) {
                    ;
                } finally {
                    peers.remove(peerID);
                    System.out.println("Peer " + peerID + " left: " + peers);
                }
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
