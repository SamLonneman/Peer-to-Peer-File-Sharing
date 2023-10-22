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
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// PeerProcess class
public class PeerProcess
{
    // Global debug constants
    public static final boolean WRITE_LOGS = false;
    public static final boolean PRINT_LOGS = true;
    public static final boolean PRINT_ERRS = true;

    // Global message type constants
    public static final int CHOKE = 0;
    public static final int UNCHOKE = 1;
    public static final int INTERESTED = 2;
    public static final int NOT_INTERESTED = 3;
    public static final int HAVE = 4;
    public static final int BITFIELD = 5;
    public static final int REQUEST = 6;
    public static final int PIECE = 7;

    // Common.cfg values
    private static int numberOfPreferredNeighbors;
    private static int unchokingInterval;
    private static int optimisticUnchokingInterval;
    private static String fileName;
    private static int fileSize;
    private static int pieceSize;
    private static int numPieces;

    // PeerInfo.cfg values
    private static int id;
    private static String address;
    private static int port;
    private static boolean hasFile;

    // Singletons
    private static FileWriter logWriter;

    // Thread-Safe Data Structures
    private static ConcurrentHashMap<Integer, Receiver> receivers = new ConcurrentHashMap<Integer, Receiver>();
    private static ConcurrentHashMap<Integer, BitSet> peerBitFields = new ConcurrentHashMap<Integer, BitSet>();
    private static Set<Integer> interestedPeers = ConcurrentHashMap.newKeySet();
    private static Set<Integer> preferredPeers = ConcurrentHashMap.newKeySet();
    private static Set<Integer> unchokedPeers = ConcurrentHashMap.newKeySet();
    private static AtomicInteger optimisticallyUnchokedPeer = new AtomicInteger();

    // Attributes
    private static BitSet bitfield;

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
            numPieces = (int)Math.ceil((double)fileSize / pieceSize);
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
                    new Handshaker(peerId, peerAddress, peerPort).start();
                } else {
                    address = peerAddress;
                    port = peerPort;
                    hasFile = peerHasFile;
                    initializeBitfield();
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

    // Initialize bitfield based on whether the peer has the file
    private static void initializeBitfield()
    {
        bitfield = new BitSet(numPieces);
        if (hasFile)
            bitfield.set(0, numPieces);
        else
            bitfield.clear();
    }

    // Begin handshake with a peer
    private static class Handshaker extends Thread
    {
        private int peerId;
        private String peerAddress;
        private int peerPort;

        public Handshaker(int peerId, String peerAddress, int peerPort)
        {
            this.peerId = peerId;
            this.peerAddress = peerAddress;
            this.peerPort = peerPort;
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
                    sendHandshake(peerOut);
                    // Receive and verify handshake message
                    int receivedPeerId = receiveHandshake(peerIn);
                    if (receivedPeerId != peerId) {
                        // Handshake failed
                        error("Handshake failed. Expected peer ID " + peerId + " but received " + receivedPeerId + ".");
                        peerSocket.close();
                    } else {
                        // Handshake successful
                        peerAddress = peerSocket.getInetAddress().toString();
                        peerPort = peerSocket.getPort();
                        log("Peer " + id + " makes a connection to Peer " + peerId + ".");
                        // Start sender and receiver threads
                        receivers.put(peerId, new Receiver(peerId, peerSocket, peerIn, peerOut));
                        receivers.get(peerId).start();
                    }
                    break;
                } catch (IOException e) {
                    error("Could not connect to peer " + peerId + " at " + peerAddress + ":" + peerPort + ". Retrying...");
                }
            }
        }
    }

    // Expect handshakes from peers
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
                        int peerId = receiveHandshake(peerIn);
                        sendHandshake(peerOut);
                        if (peerId == -1) {
                            // Handshake failed
                            error("Handshake failed.");
                            peerSocket.close();
                        } else {
                            // Handshake successful
                            log("Peer " + id + " is connected from Peer " + peerId + ".");
                            // Start sender and receiver threads
                            receivers.put(peerId, new Receiver(peerId, peerSocket, peerIn, peerOut));
                            receivers.get(peerId).start();
                        }
                    }
                } finally {
                    listener.close();
                }
            } catch (IOException e) {
                error("Error listening on " + address + ":" + port);
            }
        }
    }

    // Send a handshake message
    private static void sendHandshake(ObjectOutputStream out)
    {
        byte[] handshake = new byte[32];
        byte[] protocol = "P2PFILESHARINGPROJ".getBytes();
        byte[] zeroBits = new byte[10];
        byte[] idBytes = ByteBuffer.allocate(4).putInt(id).array();
        System.arraycopy(protocol, 0, handshake, 0, 18);
        System.arraycopy(zeroBits, 0, handshake, 18, 10);
        System.arraycopy(idBytes, 0, handshake, 28, 4);
        try {
            out.write(handshake);
            out.flush();
        } catch (IOException e) {
            error("Error sending handshake");
        }
    }

    // Receive a handshake message, returns peer ID or -1 if failed
    private static int receiveHandshake(ObjectInputStream in)
    {
        try {
            byte[] handshake = new byte[32];
            in.read(handshake, 0, 32);
            byte[] handshakeHeader = Arrays.copyOfRange(handshake, 0, 18);
            byte[] peerId = Arrays.copyOfRange(handshake, 28, 32);
            if (!new String(handshakeHeader).equals("P2PFILESHARINGPROJ"))
                return -1;
            return ByteBuffer.wrap(peerId).getInt();
        } catch (IOException e) {
            error("Error receiving handshake");
            return -1;
        }
    }

    // Send a message
    private static void sendMessage(ObjectOutputStream out, int type, byte[] payload)
    {
        if (payload == null)
            payload = new byte[0];
        byte[] message = new byte[5 + payload.length];
        byte[] messageLength = ByteBuffer.allocate(4).putInt(payload.length).array();
        byte[] messageType = ByteBuffer.allocate(1).put((byte)type).array();
        System.arraycopy(messageLength, 0, message, 0, 4);
        System.arraycopy(messageType, 0, message, 4, 1);
        System.arraycopy(payload, 0, message, 5, payload.length);
        try {
            out.write(message);
            out.flush();
        } catch (IOException e) {
            error("Error sending message");
        }
    }

    // Receive and handle a message
    private static void receiveMessage(ObjectInputStream in, int peerId)
    {
        // Set up variables
        int length = 0;
        int type = 0;
        byte[] payload = null;
        // Receive message
        try {
            byte[] messageLength = new byte[4];
            in.read(messageLength, 0, 4);
            length = ByteBuffer.wrap(messageLength).getInt();
            byte[] messageType = new byte[4];
            in.read(messageType, 3, 1);
            type = ByteBuffer.wrap(messageType).getInt();
            payload = new byte[length];
            in.read(payload, 0, length);
        } catch (IOException e) {
            error("Error receiving message");
            return;
        }
        // Handle message
        switch (type) {
            case CHOKE:
                System.out.println("CHOKE received from " + peerId + ".");
                break;
            case UNCHOKE:
                System.out.println("UNCHOKE received from " + peerId + ".");
                break;
            case INTERESTED:
                System.out.println("INTERESTED received from " + peerId + ".");
                handleInterestedMessage(peerId);
                break;
            case NOT_INTERESTED:
                System.out.println("NOT_INTERESTED received from " + peerId + ".");
                handleNotInterestedMessage(peerId);
                break;
            case HAVE:
                System.out.println("HAVE received from " + peerId + ".");
                handleHaveMessage(peerId, payload);
                break;
            case BITFIELD:
                System.out.println("BITFIELD received from " + peerId + ".");
                handleBitfieldMessage(peerId, payload);
                break;
            case REQUEST:
                System.out.println("REQUEST received from " + peerId + ".");
                break;
            case PIECE:
                System.out.println("PIECE received from " + peerId + ".");
                break;
        }
    }

    // Handle an INTERESTED message
    private static void handleInterestedMessage(int peerId)
    {
        interestedPeers.add(peerId);
    }

    // Handle a NOTINTERESTED message
    private static void handleNotInterestedMessage(int peerId)
    {
        interestedPeers.remove(peerId);
    }

    // Handle a HAVE message
    private static void handleHaveMessage(int peerId, byte[] payload)
    {
        int pieceIndex = ByteBuffer.wrap(payload).getInt();
        peerBitFields.get(peerId).set(pieceIndex);
        if (!bitfield.get(pieceIndex))
            sendMessage(receivers.get(peerId).out, INTERESTED, null);
        else
            sendMessage(receivers.get(peerId).out, NOT_INTERESTED, null);
    }

    // Handle a BITFIELD message
    private static void handleBitfieldMessage(int peerId, byte[] payload)
    {
        BitSet peerBitField = BitSet.valueOf(payload);
        peerBitFields.put(peerId, peerBitField);
        peerBitField.andNot(bitfield);
        if (!peerBitField.isEmpty())
            sendMessage(receivers.get(peerId).out, INTERESTED, null);
        else
            sendMessage(receivers.get(peerId).out, NOT_INTERESTED, null);
    }

    // Thread for receiving messages from a specific peer
    public static class Receiver extends Thread
    {
        private int peerId;
        private Socket peerSocket;
        private ObjectInputStream in;
        private ObjectOutputStream out;

        public Receiver(int peerId, Socket peerSocket, ObjectInputStream in, ObjectOutputStream out)
        {
            this.peerId = peerId;
            this.peerSocket = peerSocket;
            this.in = in;
            this.out = out;
        }

        public void run()
        {
            if (hasFile)
                sendMessage(out, BITFIELD, bitfield.toByteArray());
            while (true)
                receiveMessage(in, peerId);
        }
    }
}
