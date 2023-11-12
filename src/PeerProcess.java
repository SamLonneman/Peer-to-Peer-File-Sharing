import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // Threads
    private static Thread workerThread;
    private static Thread listenerThread;

    // Neighbor info
    private static Set<Socket> peerSockets = ConcurrentHashMap.newKeySet();
    private static ConcurrentHashMap<Socket, Integer> peerIds = new ConcurrentHashMap<Socket, Integer>();
    private static ConcurrentHashMap<Socket, ObjectOutputStream> peerOutStreams = new ConcurrentHashMap<Socket, ObjectOutputStream>();
    private static ConcurrentHashMap<Socket, ObjectInputStream> peerInStreams = new ConcurrentHashMap<Socket, ObjectInputStream>();

    // Misc
    private static FileWriter logWriter;
    private static int numConnectionsToListenFor = 0;
    
    // Entry point
    public static void main(String[] args)
    {
        // Set up threads
        listenerThread = new Thread(() -> listenForConnections());
        workerThread = new Thread(() -> worker());
        // Get ID from command line
        id = Integer.parseInt(args[0]);
        // Set up logger
        prepareLogger();
        // Load config files
        loadCommonConfig();
        // Read peer info file (includes connecting to previous peers and starting listener thread)
        loadPeerInfoAndStartConnecting();
        // Start the other threads
        workerThread.start();
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
        String time;
        if (WRITE_LOGS || PRINT_LOGS) {
            time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new java.util.Date());
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
        String commonConfigFileName = "Common.cfg";
        String line = null;
        try {
            FileReader fileReader = new FileReader(commonConfigFileName);
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
                    fileName = "peer_" + id + "/" + tokens[1];
                else if (tokens[0].equals("FileSize"))
                    fileSize = Integer.parseInt(tokens[1]);
                else if (tokens[0].equals("PieceSize"))
                    pieceSize = Integer.parseInt(tokens[1]);
            }
            bufferedReader.close();
            numPieces = (int)Math.ceil((double)fileSize / pieceSize);
        }
        catch (IOException e) {
            error("Error reading file '" + commonConfigFileName + "'");
        }
    }

    // Load peer info file
    private static void loadPeerInfoAndStartConnecting()
    {
        String peerInfoConfigFileName = "PeerInfo.cfg";
        String line = null;
        try {
            FileReader fileReader = new FileReader(peerInfoConfigFileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            while ((line = bufferedReader.readLine()) != null) {
                String[] tokens = line.split(" ");
                int peerId = Integer.parseInt(tokens[0]);
                String peerAddress = tokens[1];
                int peerPort = Integer.parseInt(tokens[2]);
                boolean peerHasFile = Integer.parseInt(tokens[3]) == 1;
                if (peerId < id) {
                    startConnection(peerId, peerAddress, peerPort);
                } else if (peerId == id) {
                    id = peerId;
                    address = peerAddress;
                    port = peerPort;
                    hasFile = peerHasFile;
                } else {
                    numConnectionsToListenFor++;
                }
            }
            listenerThread.start();
            bufferedReader.close();
        }
        catch (IOException e) {
            error("Error reading file '" + peerInfoConfigFileName + "'");
        }
    }

    // Begin handshake with a peer
    public static void startConnection(int peerId, String peerAddress, int peerPort)
    {
        // Try to connect until successful
        Socket peerSocket = null;
        while (true) {
            try {
                peerSocket = new Socket(peerAddress, peerPort);
                break;
            } catch (IOException e) {
                error("Could not connect to peer " + peerId + " at " + peerAddress + ":" + peerPort + ". Retrying...");
            }
        }
        try {
            // Add peer to list of neighbors
            ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
            ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
            peerOut.flush();
            peerIds.put(peerSocket, peerId);
            peerOutStreams.put(peerSocket, peerOut);
            peerInStreams.put(peerSocket, peerIn);
            peerSockets.add(peerSocket);
            // Send handshake message
            sendMessage(peerOut, new HandshakeMessage(id));
            log("Peer " + id + " makes a connection to Peer at port " + peerPort + ".");
        } catch (IOException e) {
            error("Error connecting to peer " + peerId + " at " + peerAddress + ":" + peerPort);
        }
    }

    // Expect handshakes from peers
    public static void listenForConnections()
    {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            for (int i = 0; i < numConnectionsToListenFor; i++) {
                Socket peerSocket = serverSocket.accept();
                ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                peerOut.flush();
                peerOutStreams.put(peerSocket, peerOut);
                peerInStreams.put(peerSocket, peerIn);
                peerSockets.add(peerSocket);
            }
            serverSocket.close();
        } catch (IOException e) {
            error("Error listening for connections.");
        }
    }

    public static void sendMessage(ObjectOutputStream out, Object message)
    {
        try {
            out.writeObject(message);
            out.flush();
        } catch (IOException e) {
            error("Error sending message.");
        }
    }

    public static void worker()
    {
        // Main loop
        while (true) {
            // Check for messages from neighbors
            for (Socket peerSocket : peerSockets) {
                try {
                    // If there is no message, skip this neighbor
                    ObjectInputStream in = peerInStreams.get(peerSocket);
                    if (in.available() == 0)
                        continue;
                    // Otherwise, read the message and handle it
                    Object messageObject = in.readObject();
                    // Handshake messages are handled differently
                    if (messageObject instanceof HandshakeMessage) {
                        HandshakeMessage handshakeMessage = (HandshakeMessage)messageObject;
                        handleHandshakeMessage(handshakeMessage, peerSocket);
                    } else if (messageObject instanceof Message) {
                        Message message = (Message)messageObject;
                        if (message.type == CHOKE) {
                            System.out.println("Peer " + id + " received" + " CHOKE from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == UNCHOKE) {
                            System.out.println("Peer " + id + " received" + " UNCHOKE from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == INTERESTED) {
                            System.out.println("Peer " + id + " received" + " INTERESTED from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == NOT_INTERESTED) {
                            System.out.println("Peer " + id + " received" + " NOT INTERESTED from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == HAVE) {
                            System.out.println("Peer " + id + " received" + " HAVE from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == BITFIELD) {
                            System.out.println("Peer " + id + " received" + " BITFIELD from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == REQUEST) {
                            System.out.println("Peer " + id + " received" + " REQUEST from Peer " + peerIds.get(peerSocket));
                        } else if (message.type == PIECE) {
                            System.out.println("Peer " + id + " received" + " PIECE from Peer " + peerIds.get(peerSocket));
                        }
                    } else {
                        error("Received unknown message type.");
                    }
                } catch (IOException e) {
                    error("Error reading message1.");
                } catch (ClassNotFoundException e) {
                    error("Error reading message2.");
                }
            }
        }
    }

    public static void handleHandshakeMessage(HandshakeMessage handshakeMessage, Socket peerSocket)
    {
        if (!peerIds.containsKey(peerSocket)) {
            peerIds.put(peerSocket, handshakeMessage.peerId);
            log("Peer " + id + " is connected from Peer " + handshakeMessage.peerId + ".");
        }
    }
}
