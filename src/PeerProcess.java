import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class PeerProcess
{
    // Global debug constants
    public static final boolean WRITE_LOGS = true;
    public static final boolean PRINT_LOGS = false;
    public static final boolean PRINT_ERRS = true;

    // Global message type constants
    public static final byte CHOKE = 0;
    public static final byte UNCHOKE = 1;
    public static final byte INTERESTED = 2;
    public static final byte NOT_INTERESTED = 3;
    public static final byte HAVE = 4;
    public static final byte BITFIELD = 5;
    public static final byte REQUEST = 6;
    public static final byte PIECE = 7;

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
    private static Thread unchokingTimerThread;
    private static Thread optimisticUnchokingTimerThread;

    // Timers
    private static boolean timeToUpdatePreferredNeighbors = false;
    private static boolean timeToUpdateOptimisticallyUnchokedNeighbor = false;

    // Neighbor info
    private static Set<Socket> peerSockets = ConcurrentHashMap.newKeySet();
    private static HashMap<Socket, Integer> peerIds = new HashMap<Socket, Integer>();
    private static HashMap<Socket, ObjectOutputStream> peerOutStreams = new HashMap<Socket, ObjectOutputStream>();
    private static HashMap<Socket, ObjectInputStream> peerInStreams = new HashMap<Socket, ObjectInputStream>();
    private static HashMap<Socket, BitSet> peerBitfields = new HashMap<Socket, BitSet>();
    private static HashMap<Socket, Integer> peerDownloadRates = new HashMap<Socket, Integer>();
    private static HashMap<Socket, Integer> pendingPieces = new HashMap<Socket, Integer>();

    // Misc
    private static FileWriter logWriter;
    private static int numConnectionsToListenFor = 0;

    // Info about this peer
    private static HashSet<Socket> interestingPeers = new HashSet<Socket>();
    private static HashSet<Socket> interestedPeers = new HashSet<Socket>();
    private static HashSet<Socket> unchokedBy = new HashSet<Socket>();
    private static HashSet<Socket> preferredNeighbors = new HashSet<Socket>();
    private static Socket optimisticallyUnchokedNeighbor = null;
    private static BitSet bitfield;
    private static byte[][] fileData;
    
    // Entry point
    public static void main(String[] args)
    {
        // Set up threads
        listenerThread = new Thread(() -> listenForConnections());
        unchokingTimerThread = new Thread(() -> unchokingTimer());
        optimisticUnchokingTimerThread = new Thread(() -> optimisticUnchokingTimer());
        workerThread = new Thread(() -> worker());
        // Get ID from command line
        id = Integer.parseInt(args[0]);
        // Set up logger
        prepareLogger();
        // Load common config file (includes setting up attributes)
        loadCommonConfig();
        // Read peer info file (includes connecting to previous peers and starting listener thread)
        loadPeerInfoAndStartConnecting();
        // Initialize attributes
        initialize();
        // Start the remaining threads
        workerThread.start();
        unchokingTimerThread.start();
        optimisticUnchokingTimerThread.start();
    }

    // Initialize attributes
    private static void initialize()
    {
        // Initialize file data (the last piece may be smaller than the rest)
        fileData = new byte[numPieces][pieceSize];
        fileData[numPieces - 1] = new byte[fileSize % pieceSize];
        // Initialize bitfield and pieces requested
        bitfield = new BitSet(numPieces);
        // If we have the file
        if (hasFile) {
            // Read in the file
            try {
                FileInputStream fileInputStream = new FileInputStream(fileName);
                for (int i = 0; i < numPieces; i++)
                    fileInputStream.read(fileData[i]);
                fileInputStream.close();
            } catch (IOException e) {
                error("Error reading file '" + fileName + "'");
            }
            // Set bitfield to all 1s
            bitfield.set(0, numPieces);
        }
    }

    private static void writeFile()
    {
        try {
            // Create the directory if it doesn't exist
            File directory = new File("peer_" + id);
            if (!directory.exists())
                directory.mkdir();
            // Write the file
            FileOutputStream fileOutputStream = new FileOutputStream(fileName);
            for (int i = 0; i < numPieces; i++)
                fileOutputStream.write(fileData[i]);
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        peerIds.put(peerSocket, peerId);
        initializePeer(peerSocket);
        log("Peer " + id + " makes a connection to Peer " + peerId + ".");
    }

    // Expect handshakes from peers
    public static void listenForConnections()
    {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            for (int i = 0; i < numConnectionsToListenFor; i++) {
                Socket peerSocket = serverSocket.accept();
                initializePeer(peerSocket);
            }
            serverSocket.close();
        } catch (IOException e) {
            error("Error listening for connections.");
        }
    }

    public static void initializePeer(Socket peerSocket)
    {
        try {
            ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
            ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
            peerOut.flush();
            peerOutStreams.put(peerSocket, peerOut);
            peerInStreams.put(peerSocket, peerIn);
            peerBitfields.put(peerSocket, new BitSet(numPieces));
            peerDownloadRates.put(peerSocket, 0);
            pendingPieces.put(peerSocket, -1);
            peerSockets.add(peerSocket);
            sendMessage(peerOut, new HandshakeMessage(id));
        } catch (IOException e) {
            error("Error initializing peer.");
        }
    }

    // Send a generic message with indicator byte
    public static void sendMessage(ObjectOutputStream out, Object message)
    {
        try {
            // Write 1 byte to keep in.available() from returning 0 when there is a message
            out.writeByte(0xff);
            out.writeObject(message);
            out.flush();
        } catch (IOException e) {
            error("Error sending message.");
        }
    }

    // Check if there is a message ready, discard indicator byte
    public static boolean hasMessage(ObjectInputStream in)
    {
        try {
            if (in.available() > 0) {
                in.readByte();
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    // Read in the generic message
    public static Object receiveMessage(ObjectInputStream in)
    {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    // This thread does the real work
    public static void worker()
    {
        // Main loop
        while (true) {
            // Check if all peers are finished
            if (hasFile) {
                for (Socket peerSocket : peerSockets) {
                    if (peerBitfields.get(peerSocket).cardinality() < numPieces)
                        break;
                    System.out.println("DONE");
                    System.exit(0);
                }
            }       
            // Check if it is time to update preferred neighbors
            if (timeToUpdatePreferredNeighbors) {
                timeToUpdatePreferredNeighbors = false;
                updatePreferredNeighbors();
            }
            // Check if it is time to update optimistically unchoked neighbor
            if (timeToUpdateOptimisticallyUnchokedNeighbor) {
                timeToUpdateOptimisticallyUnchokedNeighbor = false;
                updateOptimisticallyUnchokedNeighbor();
            }
            // Check for messages from each peer
            for (Socket peerSocket : peerSockets) {
                ObjectInputStream in = peerInStreams.get(peerSocket);
                // Only proceed if there is a message ready, otherwise, skip to next peer
                if (hasMessage(in)) {
                    // Read the message
                    Object messageObject = receiveMessage(in);
                    // Handshake messages are handled differently
                    if (messageObject instanceof HandshakeMessage) {
                        HandshakeMessage handshakeMessage = (HandshakeMessage)messageObject;
                        handleHandshakeMessage(handshakeMessage, peerSocket);
                    // All other messages are handled the same way
                    } else {
                        Message message = (Message)messageObject;
                        if (message.type == CHOKE) {
                            handleChokeMessage(message, peerSocket);
                        } else if (message.type == UNCHOKE) {
                            handleUnchokeMessage(message, peerSocket);
                        } else if (message.type == INTERESTED) {
                            handleInterestedMessage(message, peerSocket);
                        } else if (message.type == NOT_INTERESTED) {
                            handleNotInterestedMessage(message, peerSocket);
                        } else if (message.type == HAVE) {
                            handleHaveMessage(message, peerSocket);
                        } else if (message.type == BITFIELD) {
                            handleBitfieldMessage(message, peerSocket);
                        } else if (message.type == REQUEST) {
                            handleRequestMessage(message, peerSocket);
                        } else if (message.type == PIECE) {
                            handlePieceMessage(message, peerSocket);
                        }
                    }
                }
            }
        }
    }

    public static void handleHandshakeMessage(HandshakeMessage handshakeMessage, Socket peerSocket)
    {
        // If we are missing the peer's ID, store it and log the connection
        if (!peerIds.containsKey(peerSocket)) {
            peerIds.put(peerSocket, handshakeMessage.peerId);
            log("Peer " + id + " is connected from Peer " + handshakeMessage.peerId + ".");
        }
        // Send bitfield message
        sendMessage(peerOutStreams.get(peerSocket), new Message(BITFIELD, bitfield.toByteArray()));
    }

    public static void handleChokeMessage(Message message, Socket peerSocket)
    {
        // Remove the peer from the list of peers we are unchoked by
        unchokedBy.remove(peerSocket);
        log("Peer " + id + " is choked by " + peerIds.get(peerSocket) + ".");
    }

    public static void handleUnchokeMessage(Message message, Socket peerSocket)
    {
        // Add the peer to the list of peers we are unchoked by
        unchokedBy.add(peerSocket);
        log("Peer " + id + " is unchoked by " + peerIds.get(peerSocket) + ".");
        // Try requesting a piece from the peer
        TryRequestingPiece(peerSocket);
    }

    public static void handleInterestedMessage(Message message, Socket peerSocket)
    {
        // Add the peer to the list of interested peers
        interestedPeers.add(peerSocket);
        log("Peer " + id + " received the 'interested' message from " + peerIds.get(peerSocket) + ".");
    }

    public static void handleNotInterestedMessage(Message message, Socket peerSocket)
    {
        // Remove the peer from the list of interested peers
        interestedPeers.remove(peerSocket);
        log("Peer " + id + " received the 'not interested' message from " + peerIds.get(peerSocket) + ".");
    }

    private static void handleHaveMessage(Message message, Socket peerSocket)
    {
        // Get the piece index from the message
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        // Set the bit in the peer's bitfield
        peerBitfields.get(peerSocket).set(pieceIndex);
        // If we don't have the piece, send interested message
        if (!bitfield.get(pieceIndex)) {
            if (interestingPeers.add(peerSocket))
                sendMessage(peerOutStreams.get(peerSocket), new Message(INTERESTED));
        }
        // Log the message
        log("Peer " + id + " received the 'have' message from " + peerIds.get(peerSocket) + " for the piece " + pieceIndex + ".");
    }

    public static void handleBitfieldMessage(Message message, Socket peerSocket)
    {
        // Store the peer's bitfield
        BitSet peerBitfield = BitSet.valueOf(message.payload);
        peerBitfields.put(peerSocket, peerBitfield);
        // Make a copy of the peer's bitfield and remove all bits we already have
        BitSet peerBitfieldCopy = (BitSet)peerBitfield.clone();
        peerBitfieldCopy.andNot(bitfield);
        // If anything is left, send interested message
        if (!peerBitfieldCopy.isEmpty())
            if (interestingPeers.add(peerSocket))
                sendMessage(peerOutStreams.get(peerSocket), new Message(INTERESTED));
        // Otherwise, send not interested message
        else {
            if (interestingPeers.remove(peerSocket))
                sendMessage(peerOutStreams.get(peerSocket), new Message(NOT_INTERESTED));
        }
    }

    public static void handleRequestMessage(Message message, Socket peerSocket)
    {
        // Get the piece index from the message
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        // Send the piece
        byte[] payload = new byte[4 + fileData[pieceIndex].length];
        System.arraycopy(ByteBuffer.allocate(4).putInt(pieceIndex).array(), 0, payload, 0, 4);
        System.arraycopy(fileData[pieceIndex], 0, payload, 4, fileData[pieceIndex].length);
        sendMessage(peerOutStreams.get(peerSocket), new Message(PIECE, payload));
        // Increment the peer's download rate
        peerDownloadRates.put(peerSocket, peerDownloadRates.get(peerSocket) + 1);
    }

    public static void handlePieceMessage(Message message, Socket peerSocket)
    {
        // Get the piece index from the message
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        // Get the piece data from the message
        byte[] pieceData = new byte[message.payload.length - 4];
        System.arraycopy(message.payload, 4, pieceData, 0, pieceData.length);
        // Store the piece data
        fileData[pieceIndex] = pieceData;
        // Set the bit in the bitfield
        bitfield.set(pieceIndex);
        // Unmark the piece as pending
        pendingPieces.put(peerSocket, -1);
        // Log the message
        log("Peer " + id + " has downloaded the piece " + pieceIndex + " from " + peerIds.get(peerSocket) + ". Now the number of pieces it has is " + bitfield.cardinality() + ".");
        // If we are still missing pieces, try requesting another piece from the peer
        if (bitfield.cardinality() < numPieces)
            TryRequestingPiece(peerSocket);
        // Otherwise, we have the whole file!
        else {
            writeFile();
            hasFile = true;
            log("Peer " + id + " has downloaded the complete file.");
        }
        // Send have message to all peers
        for (Socket otherPeerSocket : peerSockets)
            sendMessage(peerOutStreams.get(otherPeerSocket), new Message(HAVE, ByteBuffer.allocate(4).putInt(pieceIndex).array()));
    }

    // Try requesting a piece from a peer, returns whether or not a piece was requested
    public static boolean TryRequestingPiece(Socket peerSocket)
    {
        // If unchoked by the peer and we don't already have a piece pending, request a piece
        if (unchokedBy.contains(peerSocket) && pendingPieces.get(peerSocket) == -1) {
            // retrieve list of interesting pieces (pieces the peer has that we don't have)
            BitSet peerBitfieldCopy = (BitSet)peerBitfields.get(peerSocket).clone();
            peerBitfieldCopy.andNot(bitfield);
            // remove pieces that are already pending with other peers
            for (Socket peer : peerSockets)
                if (pendingPieces.get(peer) != -1)
                    peerBitfieldCopy.clear(pendingPieces.get(peer));
            List<Integer> interestingPieces = new ArrayList<Integer>();
            for (int i = peerBitfieldCopy.nextSetBit(0); i != -1; i = peerBitfieldCopy.nextSetBit(i+1))
                interestingPieces.add(i);
            // if the peer has any such interesting pieces, request one at random
            if (interestingPieces.size() > 0) {
                int randomIndex = (int)(Math.random() * interestingPieces.size());
                int randomPiece = interestingPieces.get(randomIndex);
                sendMessage(peerOutStreams.get(peerSocket), new Message(REQUEST, ByteBuffer.allocate(4).putInt(randomPiece).array()));
                pendingPieces.put(peerSocket, randomPiece);
                return true;
            }
            // otherwise, send a not interested message
            else if (interestingPeers.remove(peerSocket))
                    sendMessage(peerOutStreams.get(peerSocket), new Message(NOT_INTERESTED));
        }
        // Piece was not requested
        return false;
    }

    // Raise a flag when it is time to update preferred neighbors
    public static void unchokingTimer()
    {
        while (true) {
            try {
                Thread.sleep(unchokingInterval * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            timeToUpdatePreferredNeighbors = true;
        }
    }

    // Raise a flag when it is time to update optimistically unchoked neighbor
    public static void optimisticUnchokingTimer()
    {
        while (true) {
            try {
                Thread.sleep(optimisticUnchokingInterval * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            timeToUpdateOptimisticallyUnchokedNeighbor = true;
        }
    }

    // Update the list of preferred neighbors
    public static void updatePreferredNeighbors()
    {
        // Choose the (up to) k interested peers with the highest download rates from last interval, breaking ties randomly
        List<Socket> interestedPeersList = new ArrayList<Socket>(interestedPeers);
        interestedPeersList.sort((Socket a, Socket b) -> {
            if (!hasFile) {
                int aRate = peerDownloadRates.get(a);
                int bRate = peerDownloadRates.get(b);
                if (aRate > bRate)
                    return -1;
                else if (aRate < bRate)
                    return 1;
            }
            return (int)(Math.random() * 2) * 2 - 1;
        });
        // Reset the download rates
        for (Socket peerSocket : peerSockets)
            peerDownloadRates.put(peerSocket, 0);
        // Set the new preferred neighbors
        @SuppressWarnings("unchecked")
        HashSet<Socket> previousPreferredNeighbors = (HashSet<Socket>)preferredNeighbors.clone();
        preferredNeighbors.clear();
        for (int i = 0; i < interestedPeersList.size() && i < numberOfPreferredNeighbors; i++)
            preferredNeighbors.add(interestedPeersList.get(i));
        // Unchoke the peers which have been added to the list of preferred neighbors (unless they are the optimistically unchoked neighbor)
        for (Socket peerSocket : preferredNeighbors) {
            if (!previousPreferredNeighbors.contains(peerSocket) && peerSocket != optimisticallyUnchokedNeighbor)
                sendMessage(peerOutStreams.get(peerSocket), new Message(UNCHOKE));
        }
        // Choke the peers that have left the list of preferred neighbors (unless they are the optimistically unchoked neighbor)
        for (Socket peerSocket : previousPreferredNeighbors) {
            if (!preferredNeighbors.contains(peerSocket) && peerSocket != optimisticallyUnchokedNeighbor)
                sendMessage(peerOutStreams.get(peerSocket), new Message(CHOKE));
        }
        // Log the new preferred neighbors if they have changed
        if (!previousPreferredNeighbors.equals(preferredNeighbors)) {
            // Generate string of preferred neighbors for logging
            String preferredNeighborsString = "";
            for (Socket peerSocket : preferredNeighbors)
                preferredNeighborsString += peerIds.get(peerSocket) + ", ";
            // Remove trailing comma and space
            if (preferredNeighborsString.length() > 0)
                preferredNeighborsString = preferredNeighborsString.substring(0, preferredNeighborsString.length() - 2);
            // Log the new preferred neighbors
            log("Peer " + id + " has the preferred neighbors " + preferredNeighborsString + ".");
        }
    }

    // Update the optimistically unchoked neighbor
    public static void updateOptimisticallyUnchokedNeighbor()
    {
        // Choose a random interested peer that is not already a preferred neighbor or the optimistically unchoked neighbor
        List<Socket> interestedPeersList = new ArrayList<Socket>(interestedPeers);
        interestedPeersList.removeAll(preferredNeighbors);
        interestedPeersList.remove(optimisticallyUnchokedNeighbor);
        // If there are any such peers, choose one at random to be the new optimistically unchoked neighbor
        if (interestedPeersList.size() > 0) {
            int randomIndex = (int)(Math.random() * interestedPeersList.size());
            Socket newOptimisticallyUnchokedNeighbor = interestedPeersList.get(randomIndex);
            // If the previous optimistically unchoked neighbor is not a preferred neighbor, choke it
            if (optimisticallyUnchokedNeighbor != null && !preferredNeighbors.contains(optimisticallyUnchokedNeighbor))
                sendMessage(peerOutStreams.get(optimisticallyUnchokedNeighbor), new Message(CHOKE));
            // Set the new optimistically unchoked neighbor and unchoke it
            optimisticallyUnchokedNeighbor = newOptimisticallyUnchokedNeighbor;
            sendMessage(peerOutStreams.get(optimisticallyUnchokedNeighbor), new Message(UNCHOKE));
            log("Peer " + id + " has the optimistically unchoked neighbor " + peerIds.get(optimisticallyUnchokedNeighbor) + ".");
        }
    }
}
