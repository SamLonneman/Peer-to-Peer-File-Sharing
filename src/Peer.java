import java.net.*;
import java.util.ArrayList;
import java.io.*;

public class Peer
{
	private static Socket trackerSocket;
	private static ObjectOutputStream trackerOut;
 	private static ObjectInputStream trackerIn;
    private static int peerID;
	private static ArrayList<Socket> neighbors = new ArrayList<Socket>();

	public static void main(String args[])
	{
		try{
			trackerSocket = new Socket("localhost", 8000);
			trackerOut = new ObjectOutputStream(trackerSocket.getOutputStream());
			trackerOut.flush();
			trackerIn = new ObjectInputStream(trackerSocket.getInputStream());
            peerID = Integer.parseInt((String)trackerIn.readObject());
            System.out.println("Peer " + peerID + " started.");

			

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
