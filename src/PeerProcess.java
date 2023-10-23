import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
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

    // Thread-Safe Data Structures
    private static ConcurrentHashMap<Integer, Liaison> liaisons = new ConcurrentHashMap<Integer, Liaison>();
    private static ConcurrentHashMap<Integer, BitSet> peerBitFields = new ConcurrentHashMap<Integer, BitSet>();
    private static ConcurrentHashMap<Integer, Integer> peerNumPiecesDownloadedRecently = new ConcurrentHashMap<Integer, Integer>();
    private static Set<Integer> interestedNeighbors = ConcurrentHashMap.newKeySet();
    private static Set<Integer> preferredNeighbors = ConcurrentHashMap.newKeySet();
    private static Set<Integer> unchokedNeighbors = ConcurrentHashMap.newKeySet();
    private static Set<Integer> unchokedBy = ConcurrentHashMap.newKeySet();
    private static AtomicInteger optimisticallyUnchokedNeighbor = new AtomicInteger(-1);

    // Attributes
    private static BitSet bitfield;
    private static BitSet piecesRequested;
    private static FileWriter logWriter;
    private static RandomAccessFile file;
    
    // Entry point
    public static void main(String[] args)
    {
        id = Integer.parseInt(args[0]);
        prepareLogger();
        loadCommonConfig();
        loadPeerInfoAndStartConnecting();
        new PreferredNeighborsManager().start();
        new OptimisticallyUnchokedNeighborManager().start();
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
            file = new RandomAccessFile(fileName, "rw");
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
                if (peerId != id) {
                    new Handshaker(peerId, peerAddress, peerPort).start();
                } else {
                    id = peerId;
                    address = peerAddress;
                    port = peerPort;
                    hasFile = peerHasFile;
                    initializeBitfields();
                    new Handshakee().start();
                    break;
                }
            }
            bufferedReader.close();
        }
        catch (IOException e) {
            error("Error reading file '" + peerInfoConfigFileName + "'");
        }
    }

    // Initialize bitfield based on whether the peer has the file
    private static void initializeBitfields()
    {
        bitfield = new BitSet(numPieces);
        if (hasFile)
            bitfield.set(0, numPieces);
        piecesRequested = new BitSet(numPieces);
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
                        // Start receiver thread
                        liaisons.put(peerId, new Liaison(peerId, peerSocket, peerIn, peerOut));
                        liaisons.get(peerId).start();
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
                            // Start receiver threads
                            liaisons.put(peerId, new Liaison(peerId, peerSocket, peerIn, peerOut));
                            liaisons.get(peerId).start();
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

    // Thread for receiving messages from a specific peer
    public static class Liaison extends Thread
    {
        private int peerId;
        private Socket peerSocket;
        private ObjectInputStream in;
        private ObjectOutputStream out;

        public Liaison(int peerId, Socket peerSocket, ObjectInputStream in, ObjectOutputStream out)
        {
            this.peerId = peerId;
            this.peerSocket = peerSocket;
            this.in = in;
            this.out = out;
        }

        public void run()
        {
            // Initialize number of pieces downloaded by this peer recently to be 0
            peerNumPiecesDownloadedRecently.put(peerId, 0);
            // Initialize peer bitfield as empty
            peerBitFields.put(peerId, new BitSet(numPieces));
            // Send bitfield message
            sendBitfieldMessage(peerId);
            // Receive messages
            while (true)
                receiveMessage(in, peerId);
        }
    }

    // Send a generic message
    private static void sendMessage(int peerId, int type, byte[] payload)
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
            ObjectOutputStream out = liaisons.get(peerId).out;
            out.write(message);
            out.flush();
        } catch (IOException e) {
            error("Error sending message");
        }
    }

    // Send a CHOKE message
    private static void sendChokeMessage(int peerId)
    {
        System.out.println(id + " is sending CHOKE to " + peerId);
        if (unchokedNeighbors.contains(peerId)) {
            unchokedNeighbors.remove(peerId);
            sendMessage(peerId, CHOKE, null);
        } else {
            error("ERROR:" + id + "tried to send choke message to " + peerId + ", but it was not unchoked.");
        }
    }

    // Send an UNCHOKE message
    private static void sendUnchokeMessage(int peerId)
    {
        if (!unchokedNeighbors.contains(peerId)) {
            unchokedNeighbors.add(peerId);
            sendMessage(peerId, UNCHOKE, null);
        } else {
            error("ERROR:" + id + "tried to send unchoke message to " + peerId + ", but it was already unchoked.");
        }
    }

    // Send an INTERESTED message
    private static void sendInterestedMessage(int peerId)
    {
        sendMessage(peerId, INTERESTED, null);
    }

    // Send a NOTINTERESTED message
    private static void sendNotInterestedMessage(int peerId)
    {
        sendMessage(peerId, NOT_INTERESTED, null);
    }

    // Send a HAVE message
    private static void sendHaveMessage(int peerId, int pieceIndex)
    {
        sendMessage(peerId, HAVE, ByteBuffer.allocate(4).putInt(pieceIndex).array());
    }

    // Send a BITFIELD message
    private static void sendBitfieldMessage(int peerId)
    {
        sendMessage(peerId, BITFIELD, bitfield.toByteArray());
    }

    // Send a REQUEST message
    private static void sendRequestMessage(int peerId, int pieceIndex)
    {
        piecesRequested.set(pieceIndex);
        sendMessage(peerId, REQUEST, ByteBuffer.allocate(4).putInt(pieceIndex).array());
    }

    // Send a PIECE message
    private static void sendPieceMessage(int peerId, int pieceIndex)
    {
        try {
            byte[] message = new byte[4 + pieceSize];
            byte[] piece = new byte[pieceSize];
            file.seek(pieceIndex * pieceSize);
            file.read(piece, 0, pieceSize);
            System.arraycopy(ByteBuffer.allocate(4).putInt(pieceIndex).array(), 0, message, 0, 4);
            System.arraycopy(piece, 0, message, 4, pieceSize);
            sendMessage(peerId, PIECE, message);
        } catch (IOException e) {
            error("Error reading file");
        }
        // Update numPiecesDownloadedRecently for the peer
        peerNumPiecesDownloadedRecently.put(peerId, peerNumPiecesDownloadedRecently.get(peerId) + 1);
    }

    // Receive and handle a message
    private static void receiveMessage(ObjectInputStream in, int peerId)
    {
        // Read message length
        int length = 0;
        try {
            length = in.readInt();
        } catch (IOException e) {
            error("Error reading message length");
            return;
        }
        // Read message type
        int type = 0;
        try {
            type = in.read();
        } catch (IOException e) {
            error("Error reading message type");
        }
        // Read message payload
        byte[] payload = new byte[length];
        try {
            in.read(payload, 0, length);
        } catch (IOException e) {
            error("Error reading message payload");
        }
        // Handle message
        switch (type) {
            case CHOKE:
                handleChokeMessage(peerId);
                break;
            case UNCHOKE:
                handleUnchokeMessage(peerId);
                break;
            case INTERESTED:
                handleInterestedMessage(peerId);
                break;
            case NOT_INTERESTED:
                handleNotInterestedMessage(peerId);
                break;
            case HAVE:
                handleHaveMessage(peerId, payload);
                break;
            case BITFIELD:
                handleBitfieldMessage(peerId, payload);
                break;
            case REQUEST:
                handleRequestMessage(peerId, payload);
                break;
            case PIECE:
                handlePieceMessage(peerId, payload);
                break;
        }
    }

    // Handle a CHOKE message
    private static void handleChokeMessage(int peerId)
    {
        log("Peer " + id + " is choked by " + peerId + ".");
        unchokedBy.remove(peerId);
    }

    // Handle an UNCHOKE message
    private static void handleUnchokeMessage(int peerId)
    {
        log("Peer " + id + " is unchoked by " + peerId + ".");
        unchokedBy.add(peerId);
        TryRequestingPiece(peerId);
    }

    // Handle an INTERESTED message
    private static void handleInterestedMessage(int peerId)
    {
        log("Peer " + id + " received the 'interested' message from " + peerId + ".");
        interestedNeighbors.add(peerId);
    }

    // Handle a NOTINTERESTED message
    private static void handleNotInterestedMessage(int peerId)
    {
        log("Peer " + id + " received the 'not interested' message from " + peerId + ".");
        interestedNeighbors.remove(peerId);
    }

    // Handle a HAVE message
    private static void handleHaveMessage(int peerId, byte[] payload)
    {
        int pieceIndex = ByteBuffer.wrap(payload).getInt();
        peerBitFields.get(peerId).set(pieceIndex);
        if (!bitfield.get(pieceIndex))
            sendInterestedMessage(peerId);
        log("Peer " + id + " received the 'have' message from " + peerId + " for the piece " + pieceIndex + ".");
    }

    // Handle a BITFIELD message
    private static void handleBitfieldMessage(int peerId, byte[] payload)
    {
        BitSet peerBitField = BitSet.valueOf(payload);
        peerBitFields.put(peerId, peerBitField);
        peerBitField.andNot(bitfield);
        if (!peerBitField.isEmpty())
            sendInterestedMessage(peerId);
        else
            sendNotInterestedMessage(peerId);
    }

    // Handle a REQUEST message
    private static void handleRequestMessage(int peerId, byte[] payload)
    {
        // Send the requested piece
        sendPieceMessage(peerId, ByteBuffer.wrap(payload).getInt());
    }

    // Handle a PIECE message
    private static void handlePieceMessage(int peerId, byte[] payload)
    {
        int pieceIndex = ByteBuffer.wrap(payload).getInt();
        try {
            file.seek(pieceIndex * pieceSize);
            file.write(Arrays.copyOfRange(payload, 4, payload.length));
        } catch (IOException e) {
            error("Error writing file");
        }
        // update bitfield to reflect that we now have the piece
        bitfield.set(pieceIndex);
        // Send HAVE message to all peers
        for (int otherPeerId : liaisons.keySet())
            sendHaveMessage(otherPeerId, pieceIndex);
        // Log that the piece has been downloaded
        log("Peer " + id + " has downloaded the piece " + pieceIndex + " from " + peerId + ". Now the number of pieces it has is " + bitfield.cardinality() + ".");
        // Try requesting another piece from the peer (will only work if still unchoked and peer has interesting pieces)
        TryRequestingPiece(peerId);
    }

    // Try requesting a piece from a peer
    public static void TryRequestingPiece(int peerId)
    {
        // If unchoked by the peer, and the peer has any interesting pieces, request one at random
        if (unchokedBy.contains(peerId)) {
            // retrieve list of interesting pieces
            BitSet peerBitField = peerBitFields.get(peerId);
            peerBitField.andNot(bitfield);
            peerBitField.andNot(piecesRequested);
            List<Integer> interestingPieces = new ArrayList<Integer>();
            for (int i = peerBitField.nextSetBit(0); i >= 0; i = peerBitField.nextSetBit(i+1))
                interestingPieces.add(i);
            // if the peer has any interesting pieces, request one at random
            if (interestingPieces.size() > 0) {
                int randomIndex = (int)(Math.random() * interestingPieces.size());
                int randomPiece = interestingPieces.get(randomIndex);
                sendRequestMessage(peerId, randomPiece);
            } else {
                // otherwise, send a not interested message
                sendNotInterestedMessage(peerId);
            }
        }
    }

    // Thread for managing preferred neighbors
    public static class PreferredNeighborsManager extends Thread
    {
        public void run()
        {
            while (true) {
                // Replace the current preferred neighbors with the k best interested neighbors by download rate
                updatePreferredNeighbors();
                // Choke all unchoked neighbors not in the preferred neighbors or the optimistically unchoked neighbor
                List<Integer> unchokedNeighborsCopy = new ArrayList<Integer>(unchokedNeighbors);
                for (int peerId : unchokedNeighborsCopy)
                    if (!preferredNeighbors.contains(peerId) && peerId != optimisticallyUnchokedNeighbor.get())
                        sendChokeMessage(peerId);
                // Unchoke all preferred neighbors not already unchoked
                for (int peerId : preferredNeighbors)
                    if (!unchokedNeighbors.contains(peerId))
                        sendUnchokeMessage(peerId);
                // Log preferred neighbors
                log("Peer " + id + " has the preferred neighbors " + preferredNeighbors.toString() + ".");
                // Wait for unchoking interval
                try {
                    Thread.sleep(unchokingInterval * 1000);
                } catch (InterruptedException e) {
                    error("Preferred neighbors manager thread interrupted.");
                }
            }
        }

        // Get the (up to) k best interested neighbors by download rate
        private void updatePreferredNeighbors()
        {
            // Get a sorted list of all interested neighbors based on download rate, descending, break ties randomly
            List<Integer> interestedNeighborsCopy = new ArrayList<Integer>(interestedNeighbors);
            if (!hasFile) {
                Collections.sort(interestedNeighborsCopy, (peerId1, peerId2) -> {
                    int downloadRate1 = peerNumPiecesDownloadedRecently.get(peerId1);
                    int downloadRate2 = peerNumPiecesDownloadedRecently.get(peerId2);
                    if (downloadRate1 == downloadRate2)
                        return (int)(Math.random() * 2) * 2 - 1;
                    else
                        return downloadRate2 - downloadRate1;
                });
            } else {
                // If we have the file, just choose neighbors randomly from interested neighbors
                Collections.shuffle(interestedNeighborsCopy);
            }
            // Get (up to) k best interested neighbors
            List<Integer> newPreferredNeighbors = interestedNeighborsCopy.subList(0, Math.min(interestedNeighborsCopy.size(), numberOfPreferredNeighbors));
            // Update preferred neighbors
            preferredNeighbors.clear();
            preferredNeighbors.addAll(newPreferredNeighbors);
            // Reset numPiecesDownloadedRecently for all peers
            for (int peerId : peerNumPiecesDownloadedRecently.keySet())
                peerNumPiecesDownloadedRecently.put(peerId, 0);
        }
    }

    // Thread for managing the optimistically unchoked neighbor
    public static class OptimisticallyUnchokedNeighborManager extends Thread
    {
        public void run()
        {
            while (true) {
                // Choose a random choked interested neighbor to be the optimistically unchoked neighbor
                List<Integer> chokedInterestedNeighbors = new ArrayList<Integer>();
                for (int peerId : interestedNeighbors)
                    if (!unchokedNeighbors.contains(peerId))
                        chokedInterestedNeighbors.add(peerId);
                // If there are some choked and interested peers, choose one at random to be the optimistically unchoked neighbor
                if (chokedInterestedNeighbors.size() > 0) {
                    int randomIndex = (int)(Math.random() * chokedInterestedNeighbors.size());
                    int randomChokedInterestedNeighbor = chokedInterestedNeighbors.get(randomIndex);
                    // Choke the previous optimistically unchoked neighbor if it exists and is not in the preferred neighbors
                    if (optimisticallyUnchokedNeighbor.get() != -1 && !preferredNeighbors.contains(optimisticallyUnchokedNeighbor.get()))
                        sendChokeMessage(optimisticallyUnchokedNeighbor.get());
                    // Unchoke the new optimistically unchoked neighbor
                    optimisticallyUnchokedNeighbor.set(randomChokedInterestedNeighbor);
                    sendUnchokeMessage(randomChokedInterestedNeighbor);
                } else {
                    // Otherwise, choke the previous optimistically unchoked neighbor it exists and has become uninterested
                    if (optimisticallyUnchokedNeighbor.get() != -1 && !interestedNeighbors.contains(optimisticallyUnchokedNeighbor.get())) {
                        sendChokeMessage(optimisticallyUnchokedNeighbor.get());
                        optimisticallyUnchokedNeighbor.set(-1);
                    }
                }
                // Log optimistically unchoked neighbor if it has been set
                if (optimisticallyUnchokedNeighbor.get() != -1)
                    log("Peer " + id + " has the optimistically unchoked neighbor " + optimisticallyUnchokedNeighbor.get() + ".");
                else {
                    log("Peer " + id + " has no optimistically unchoked neighbor.");
                }
                try {
                    Thread.sleep(optimisticUnchokingInterval * 1000);
                } catch (InterruptedException e) {
                    error("Optimistically unchoked neighbor manager thread interrupted.");
                }
            }
        }
    }
}
