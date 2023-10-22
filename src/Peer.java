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
	private static HashMap<Integer, String> friends = new HashMap<>();

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

	void sendMessageToTracker(Object msg)
	{
		try {
			trackerOut.writeObject(msg);
			trackerOut.flush();
		} catch(IOException ioException) {
			ioException.printStackTrace();
		}
	}

	private static class Listener extends Thread
	{
		public void run()
		{
			try {
				ServerSocket listener = new ServerSocket(8001);
				Socket socket = listener.accept();
				ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
				System.out.println("Peer " + peerID + " says: " + (String)in.readObject());
				in.close();
				socket.close();
				listener.close();
			} catch (ClassNotFoundException e) {
				System.err.println("Class not found");
			} catch(IOException ioException) {
				ioException.printStackTrace();
			}
		}
	}

	// private static class Friend extends Thread
	// {
	// 	private Socket socket;
	// 	private ObjectInputStream in;
	// 	private ObjectOutputStream out;
	// 	private int friendID;
	// 	private String friendIP;

	// 	public Friend(Socket socket)
	// 	{
	// 		this.socket = socket;
	// 	}

	// 	public void run()
	// 	{
	// 		try {
	// 			// Setup
	// 			out = new ObjectOutputStream(socket.getOutputStream());
	// 			out.flush();
	// 			in = new ObjectInputStream(socket.getInputStream());
	// 			// Prepare a new entry for this peer
	// 			friendID = Integer.parseInt((String)in.readObject());
	// 			friendIP = socket.getInetAddress().getHostAddress();
	// 			// Tell the peer their ID
	// 			sendMessage(Integer.toString(friendID));
	// 			// Tell the peer how many peers are about to be sent
	// 			sendMessage(Integer.toString(neighbors.size()));
	// 			// Send the peer list
	// 			for (Integer key : neighbors.keySet()) {
	// 				sendMessage(Integer.toString(key));
	// 				sendMessage(neighbors.get(key).toString());
	// 			}
	// 			// Add the peer to the map
	// 			neighbors.put(friendID, friendIP);
	// 			System.out.println("Peer " + friendID + " accepted: " + neighbors);
	// 		} catch (ClassNotFoundException e) {
	// 			System.err.println("Class not found");
	// 		} catch(IOException ioException) {
	// 			;
	// 		} finally {
	// 			try {
	// 				in.close();
	// 				out.close();
	// 				socket.close();
	// 			} catch(IOException ioException) {
	// 				;
	// 			} finally {
	// 				neighbors.remove(friendID);
	// 			}
	// 		}
	// 	}
	// }
}
