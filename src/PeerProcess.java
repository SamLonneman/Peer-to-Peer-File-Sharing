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

    private static void error(String message)
    {
        if (PRINT_ERRS)
            System.out.println(message);
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
        catch (IOException e) {
            error("Error reading file '" + fileName + "'");
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
                // Setup
                Socket peerSocket = new Socket(peerAddress, peerPort);
                ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream());
                peerOut.flush();
                ObjectInputStream peerIn = new ObjectInputStream(peerSocket.getInputStream());
                // Send handshake message
                peerOut.writeObject(createHandshakeMessage());
                // Receive and verify handshake message
                byte[] handshakeMessage = (byte[])peerIn.readObject();
                if (!getHeaderFromHandshakeMessage(handshakeMessage).equals("P2PFILESHARINGPROJ") || getPeerIdFromHandshakeMessage(handshakeMessage) != peerId) {
                    error("Handshake failed with peer at " + peerAddress + ":" + peerPort + ".");
                    peerSocket.close();
                    return;
                }
                // Handshake successful
                peerAddress = peerSocket.getInetAddress().toString();
                peerPort = peerSocket.getPort();
                log("Peer " + id + " makes a connection to Peer " + peerId + ".");
                // new Reader(socket).start();
                // new Writer(socket).start();
            } catch (IOException e) {
                error("Error connecting to peer " + peerId + " at " + peerAddress + ":" + peerPort);
            } catch (ClassNotFoundException e) {
                error("Class not found");
            }
        }
    }

    private static class Handshakee extends Thread
    {
        public void run()
        {
            try {
                ServerSocket listener = new ServerSocket(port);
                try {
                    while(true) {
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

    private static String getHeaderFromHandshakeMessage(byte[] handshakeMessage)
    {
        return new String(Arrays.copyOfRange(handshakeMessage, 0, 18));
    }

    private static int getPeerIdFromHandshakeMessage(byte[] handshakeMessage)
    {
        return ByteBuffer.wrap(Arrays.copyOfRange(handshakeMessage, 28, 32)).getInt();
    }
}