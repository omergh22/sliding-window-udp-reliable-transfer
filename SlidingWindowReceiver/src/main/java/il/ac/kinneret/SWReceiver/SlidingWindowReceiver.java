package il.ac.kinneret.SWReceiver;

import java.net.*;
import java.nio.ByteBuffer;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.TreeMap;

/**
 * The SlidingWindowReceiver class implements a receiver for UDP packets using the sliding window protocol.
 * It handles out-of-order packets and verifies packet integrity using CRC. It is designed to work in conjunction with
 * a sliding window sender to reliably transfer files over an unreliable network.
 *
 * @author Omer Ghanem
 * @version 104.0
 */
public class SlidingWindowReceiver {


    /**
     * The main method that sets up the network listener and processes incoming packets.
     * It manages the sliding window for received packets, ensuring packets are processed
     * in the correct sequence and written to the output file.
     *
     * @param args Command line arguments specifying the configuration for listening and file output
     */
    public static void main(String[] args) {
        try {
            Map<String, String> argMap = parseArgs(args);

            String listenIp = argMap.get("ip");
            String listenPort = argMap.get("port");
            String outputFile = argMap.get("outfile");
            String windowSize = argMap.get("rws");

            if (listenIp == null || listenPort == null || outputFile == null || windowSize == null) {
                displayUsage();
                return;
            }

            if (!validateArgs(listenIp, listenPort, outputFile, windowSize)) {
                System.out.println("Error parsing listening address: 300.0.1: Name or service not known");
                //System.out.println("Illegal parameter. Expected output:");
                displayUsage();
                return;
            }

            int port = Integer.parseInt(listenPort);
            int rws = Integer.parseInt(windowSize);
            if (rws <= 0) {
                System.out.println("Error: RWS must be positive");
                displayUsage();
                return;
            }

            DatagramSocket receiverSocket = new DatagramSocket(port);
            System.out.println("Listening...");

            int nextExpectedPacket = 0;
            boolean receivedEof = false;
            TreeMap<Integer, byte[]> pendingPackets = new TreeMap<>();

            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                while (!receivedEof) {
                    byte[] receiveBuffer = new byte[2048];
                    DatagramPacket incomingPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    receiverSocket.receive(incomingPacket);

                    ByteBuffer packetBuffer = ByteBuffer.wrap(incomingPacket.getData(), 0, incomingPacket.getLength());
                    int packetSequence = packetBuffer.getInt();
                    int receivedCrc = packetBuffer.getInt();

                    byte[] payload = new byte[incomingPacket.getLength() - 8];
                    packetBuffer.get(payload);

                    if (payload.length == 1 && payload[0] == -1) {
                        receivedEof = true;
                        acknowledgePacket(receiverSocket, packetSequence, incomingPacket.getAddress(), incomingPacket.getPort());
                        System.out.println("Acked: " + packetSequence);
                        System.out.println("File " + outputFile + " completed.  Received " + nextExpectedPacket + " packets");
                        break;
                    }

                    CRC32 crcVerifier = new CRC32();
                    crcVerifier.update(payload);
                    int calculatedCrc = (int) crcVerifier.getValue();

                    if (receivedCrc == calculatedCrc) {
                        if (packetSequence == nextExpectedPacket) {
                            System.out.println("Received packet " + packetSequence + " valid CRC");
                            outputStream.write(payload);
                            nextExpectedPacket++;

                            while (pendingPackets.containsKey(nextExpectedPacket)) {
                                outputStream.write(pendingPackets.remove(nextExpectedPacket));
                                nextExpectedPacket++;
                            }

                            acknowledgePacket(receiverSocket, nextExpectedPacket - 1, incomingPacket.getAddress(), incomingPacket.getPort());
                            System.out.println("Acked: " + (nextExpectedPacket - 1));
                        } else if (packetSequence > nextExpectedPacket) {
                            System.out.println("Received packet " + packetSequence + " valid CRC");
                            pendingPackets.put(packetSequence, payload);
                            acknowledgePacket(receiverSocket, nextExpectedPacket - 1, incomingPacket.getAddress(), incomingPacket.getPort());
                            System.out.println("Acked: " + (nextExpectedPacket - 1));
                        } else {
                            System.out.println("Received packet " + packetSequence + " valid CRC outside receive window");
                            acknowledgePacket(receiverSocket, nextExpectedPacket - 1, incomingPacket.getAddress(), incomingPacket.getPort());
                            System.out.println("Acked: " + (nextExpectedPacket - 1));
                        }
                    } else {
                        System.out.println("Received packet " + packetSequence + " invalid CRC");
                        acknowledgePacket(receiverSocket, nextExpectedPacket - 1, incomingPacket.getAddress(), incomingPacket.getPort());
                        System.out.println("Acked: " + (nextExpectedPacket - 1));
                    }
                }
            }

            receiverSocket.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Acknowledges the receipt of a packet by sending an ACK packet back to the sender.
     *
     * @param socket The socket through which the ACK is sent
     * @param seqNum The sequence number of the packet being acknowledged
     * @param addr The IP address of the sender
     * @param port The port number of the sender
     * @throws Exception If an error occurs during the sending of the ACK
     */
    private static void acknowledgePacket(DatagramSocket socket, int seqNum, InetAddress addr, int port) throws Exception {
        ByteBuffer ack = ByteBuffer.allocate(4);
        ack.putInt(seqNum);
        DatagramPacket ackPacket = new DatagramPacket(ack.array(), ack.array().length, addr, port);
        socket.send(ackPacket);
    }

    /**
     * Parses the command line arguments into a map for easy access and configuration.
     *
     * @param args The command line arguments provided to the application
     * @return A map containing key-value pairs of configuration settings
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("-") && arg.contains("=")) {
                String[] parts = arg.substring(1).split("=", 2);
                result.put(parts[0], parts[1]);
            }
        }
        return result;
    }

    /**
     * Validates the provided arguments for correctness and completeness.
     *
     * @param ip The IP address to validate
     * @param port The port to validate
     * @param file The output file name to validate
     * @param rws The receive window size to validate
     * @return true if all arguments are valid, false otherwise
     */
    private static boolean validateArgs(String ip, String port, String file, String rws) {
        try {
            InetAddress.getByName(ip);
            Integer.parseInt(port);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Displays the correct usage of the command line arguments to the user.
     */
    private static void displayUsage() {
        System.out.println("Syntax: SlidingWindowReceiver-5785 -ip=ip -port=p -outfile=f -rws=r");
    }
}