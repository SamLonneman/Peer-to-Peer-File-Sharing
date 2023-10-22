import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;

public class PeerProcess
{
    // Common.cfg values
    private static int numberOfPreferredNeighbors;
    private static int unchokingInterval;
    private static int optimisticUnchokingInterval;
    private static String fileName;
    private static int fileSize;
    private static int pieceSize;

    // PeerInfo.cfg values
    private static int id;
    private static String address;
    private static int port;
    private static boolean hasFile;

    public static void main(String[] args)
    {
        id = Integer.parseInt(args[0]);
        loadCommonConfig();
        loadPeerInfo();
    }

    private static void loadCommonConfig()
    {
        String fileName = "Common.cfg";
        String line = null;
        try {
            FileReader fileReader = new FileReader(fileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            while ((line = bufferedReader.readLine()) != null) {
                String[] tokens = line.split(" ");
                if (tokens[0].equals("NumberOfPreferredNeighbors"))
                    numberOfPreferredNeighbors = Integer.parseInt(tokens[1]);
                else if (tokens[0].equals("UnchokingInterval"))
                    unchokingInterval = Integer.parseInt(tokens[1]);
                else if (tokens[0].equals("OptimisticUnchokingInterval"))
                    optimisticUnchokingInterval = Integer.parseInt(tokens[1]);
                else if (tokens[0].equals("FileName"))
                    fileName = tokens[1];
                else if (tokens[0].equals("FileSize"))
                    fileSize = Integer.parseInt(tokens[1]);
                else if (tokens[0].equals("PieceSize"))
                    pieceSize = Integer.parseInt(tokens[1]);
            }
            bufferedReader.close();
        }
        catch (IOException ex) {
            System.out.println("Error reading file '" + fileName + "'");
        }
    }

    private static void loadPeerInfo()
    {
        String fileName = "PeerInfo.cfg";
        String line = null;
        try {
            FileReader fileReader = new FileReader(fileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            while ((line = bufferedReader.readLine()) != null) {
                String[] tokens = line.split(" ");
                int peerId = Integer.parseInt(tokens[0]);
                String peerAddress = tokens[1];
                int peerPort = Integer.parseInt(tokens[2]);
                boolean peerHasFile = Integer.parseInt(tokens[3]) == 1;
                if (peerId != id) {
                    new Handshaker(peerId, peerAddress, peerPort, peerHasFile).start();
                } else {
                    address = peerAddress;
                    port = peerPort;
                    hasFile = peerHasFile;
                    new Listener().start();
                    break;
                }
            }
            bufferedReader.close();
        }
        catch (IOException e) {
            System.out.println("Error reading file '" + fileName + "'");
        }
    }

    private static class Handshaker extends Thread
    {
        private int peerId;
        private String peerAddress;
        private int peerPort;
        private boolean peerHasFile;

        public Handshaker(int peerId, String peerAddress, int peerPort, boolean peerHasFile)
        {
            this.peerId = peerId;
            this.peerAddress = peerAddress;
            this.peerPort = peerPort;
            this.peerHasFile = peerHasFile;
        }

        public void run()
        {
            try {
                Socket peerSocket = new Socket(peerAddress, peerPort);
                ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                peerOut.flush();
                ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                peerOut.writeObject(Integer.toString(id));
                if (Integer.parseInt((String)peerIn.readObject()) != peerId) {
                    System.out.println("Peer " + peerId + " sent unexpected peerId. Handshake failed.");
                    peerSocket.close();
                    return;
                }
                peerAddress = peerSocket.getInetAddress().toString();
                peerPort = peerSocket.getPort();
                System.out.println("Connected to peer " + peerId + " at " + peerAddress + ":" + peerPort);
                // new Reader(socket).start();
                // new Writer(socket).start();
            } catch (IOException e) {
                System.out.println("Error connecting to peer " + peerId + " at " + peerAddress + ":" + peerPort);
            } catch (ClassNotFoundException e) {
                System.out.println("Class not found");
            }
        }
    }

    private static class Listener extends Thread
    {
        public void run()
        {
            try {
                ServerSocket listener = new ServerSocket(port);
                System.out.println("Listening on " + address + ":" + port);
                try {
                    while(true) {
                        Socket peerSocket = listener.accept();
                        ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                        peerOut.flush();
                        ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                        int peerId = Integer.parseInt((String)peerIn.readObject());
                        peerOut.writeObject(Integer.toString(id));
                        String peerAddress = peerSocket.getInetAddress().toString();
                        int peerPort = peerSocket.getPort();
                        System.out.println("Accepted connection from peer " + peerId + " at " + peerAddress + ":" + peerPort);
                        // new Reader(socket).start();
                        // new Writer(socket).start();
                    }
                } catch (ClassNotFoundException e) {
                    System.out.println("Class not found");
                } finally {
                    listener.close();
                }
            } catch (IOException e) {
                System.out.println("Error listening on " + address + ":" + port);
            }
        }
    }
}