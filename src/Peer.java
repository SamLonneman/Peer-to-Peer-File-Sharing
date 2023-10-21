import java.net.*;
import java.util.HashMap;
import java.io.*;

public class Peer
{
	private static Socket trackerSocket;
	private static ObjectOutputStream trackerOut;
 	private static ObjectInputStream trackerIn;
    private static int peerID;
	private static String peerIP;
    private static HashMap<Integer, String> neighbors = new HashMap<>();

	public static void main(String args[])
	{
		try{
			// Setup
			trackerSocket = new Socket("localhost", 8000);
			trackerOut = new ObjectOutputStream(trackerSocket.getOutputStream());
			trackerOut.flush();
			trackerIn = new ObjectInputStream(trackerSocket.getInputStream());
			// Get your peerID from the tracker, store your own IP
            peerID = Integer.parseInt((String)trackerIn.readObject());
			peerIP = InetAddress.getLocalHost().getHostAddress();
            System.out.println("Peer " + peerID + " started.");
			// Get the list of peers from the tracker
			int numNeighbors = Integer.parseInt((String)trackerIn.readObject());
			for (int i = 0; i < numNeighbors; i++) {
				int neighborID = Integer.parseInt((String)trackerIn.readObject());
				String neighborIP = (String)trackerIn.readObject();
				neighbors.put(neighborID, neighborIP);
			}
			System.out.println("Peer " + peerID + " is aware of: " + neighbors);
			

			// Stay alive
			System.out.println("Tracker says: " + (String)trackerIn.readObject());
		} catch (ConnectException e) {
    		System.err.println("Connection refused. You need to initiate a tracker first.");
		} catch (ClassNotFoundException e) {
            System.err.println("Class not found");
        } catch(UnknownHostException e) {
			System.err.println("You are trying to connect to an unknown host!");
		} catch(IOException e) {
			e.printStackTrace();
		} finally {
			try {
				trackerIn.close();
				trackerOut.close();
				trackerSocket.close();
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
	}

	void sendMessage(Object msg)
	{
		try {
			trackerOut.writeObject(msg);
			trackerOut.flush();
		} catch(IOException ioException) {
			ioException.printStackTrace();
		}
	}
}
