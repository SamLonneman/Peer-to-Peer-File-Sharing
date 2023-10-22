import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class PeerProcess
{
    // Global debug constants
    public static boolean WRITE_LOGS = false;
    public static boolean PRINT_LOGS = true;
    public static boolean PRINT_ERRS = true;

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

    // Singletons
    private static FileWriter logWriter;

    // Entry point
    public static void main(String[] args)
    {
        id = Integer.parseInt(args[0]);
        prepareLogger();
        loadCommonConfig();
        loadPeerInfo();
    }

    // Set up logger
    private static void prepareLogger()
    {
        if (WRITE_LOGS)
        {
            try {
                logWriter = new FileWriter("log_peer_" + id + ".log");
            } catch (IOException e) {
                error("Failed to create log file for peer " + id + ".");
            }
        }
    }

    // Log a message
    private static void log(String message)
    {
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new java.util.Date());
        if (WRITE_LOGS) {
            try {
                logWriter.write(time + message + "\n");
                logWriter.flush();
            } catch (IOException e) {
                error("Failed to write to log_peer_" + id + ".log");
            }
        }
        if (PRINT_LOGS)
            System.out.print(time + message + "\n");
    }

    // Print an error message
    private static void error(String message)
    {
        if (PRINT_ERRS)
            System.out.println(message);
    }

    // Load common config file
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
        catch (IOException e) {
            error("Error reading file '" + fileName + "'");
        }
    }

    // Load peer info file
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
                    new Handshakee().start();
                    break;
                }
            }
            bufferedReader.close();
        }
        catch (IOException e) {
            error("Error reading file '" + fileName + "'");
        }
    }

    // Begin handshake with a peer
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
            // Try to connect until success or failed handshake
            while (true) {
                try {
                    // Connect and set up streams
                    Socket peerSocket = new Socket(peerAddress, peerPort);
                    ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                    peerOut.flush();
                    ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                    // Send handshake message
                    peerOut.writeObject(createHandshakeMessage());
                    // Receive and verify handshake message
                    byte[] handshakeMessage = (byte[])peerIn.readObject();
                    if (!getHeaderFromHandshakeMessage(handshakeMessage).equals("P2PFILESHARINGPROJ") || getPeerIdFromHandshakeMessage(handshakeMessage) != peerId) {
                        // Handshake failed
                        error("Handshake failed with peer at " + peerAddress + ":" + peerPort + ".");
                        peerSocket.close();
                    } else {
                        // Handshake successful
                        peerAddress = peerSocket.getInetAddress().toString();
                        peerPort = peerSocket.getPort();
                        log("Peer " + id + " makes a connection to Peer " + peerId + ".");
                        // new Reader(socket).start();
                        // new Writer(socket).start();
                    }
                    break;
                } catch (IOException e) {
                    error("Could not connect to peer " + peerId + " at " + peerAddress + ":" + peerPort + ". Retrying...");
                } catch (ClassNotFoundException e) {
                    error("Class not found");
                }
            }
        }
    }

    // Wait for handshake from peers
    private static class Handshakee extends Thread
    {
        public void run()
        {
            try {
                ServerSocket listener = new ServerSocket(port);
                try {
                    while (true) {
                        // Setup
                        Socket peerSocket = listener.accept();
                        ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                        peerOut.flush();
                        ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                        // Receive and verify handshake message
                        byte[] handshakeMessage = (byte[])peerIn.readObject();
                        if (!getHeaderFromHandshakeMessage(handshakeMessage).equals("P2PFILESHARINGPROJ")) {
                            error("Handshake failed with peer at " + peerSocket.getInetAddress().toString() + ":" + peerSocket.getPort() + ".");
                            peerSocket.close();
                            continue;
                        }
                        // Send handshake message
                        peerOut.writeObject(createHandshakeMessage());
                        // Handshake successful
                        int peerId = getPeerIdFromHandshakeMessage(handshakeMessage);
                        String peerAddress = peerSocket.getInetAddress().toString();
                        int peerPort = peerSocket.getPort();
                        log("Peer " + id + " is connected from Peer " + peerId + ".");
                        // new Reader(socket).start();
                        // new Writer(socket).start();
                    }
                } catch (ClassNotFoundException e) {
                    error("Class not found");
                } finally {
                    listener.close();
                }
            } catch (IOException e) {
                error("Error listening on " + address + ":" + port);
            }
        }
    }

    // Construct handshake message
    private static byte[] createHandshakeMessage()
    {
        byte[] protocol = "P2PFILESHARINGPROJ".getBytes();
        byte[] zeroBits = new byte[10];
        byte[] peerId = ByteBuffer.allocate(4).putInt(id).array();
        byte[] handshakeMessage = new byte[32];
        System.arraycopy(protocol, 0, handshakeMessage, 0, 18);
        System.arraycopy(zeroBits, 0, handshakeMessage, 18, 10);
        System.arraycopy(peerId, 0, handshakeMessage, 28, 4);
        return handshakeMessage;
    }

    // Extract header from handshake message
    private static String getHeaderFromHandshakeMessage(byte[] handshakeMessage)
    {
        try {
            return new String(Arrays.copyOfRange(handshakeMessage, 0, 18), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            error("UTF-8 encoding not supported");
            return null;
        }
    }

    // Extract peer ID from handshake message
    private static int getPeerIdFromHandshakeMessage(byte[] handshakeMessage)
    {
        try {
            return ByteBuffer.wrap(Arrays.copyOfRange(handshakeMessage, 28, 32)).getInt();
        } catch (java.nio.BufferUnderflowException e) {
            error("Handshake message too short");
            return -1;
        }
    }
}