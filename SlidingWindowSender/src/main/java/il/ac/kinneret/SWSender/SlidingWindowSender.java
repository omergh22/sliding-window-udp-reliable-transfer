package il.ac.kinneret.SWSender;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Implements a Sliding Window Sender that sends packets over UDP and handles acknowledgments
 * with sliding window protocol. It utilizes CRC for data integrity and supports packet dropping simulation.
 *
 * @author [Your Name]
 * @version 1.0
 */
public class SlidingWindowSender {


    /**
     * Main method to execute the Sliding Window Sender. It processes command line arguments,
     * sets up the file and network resources, and initiates the sending process.
     *
     * @param args Command line arguments specifying operation parameters
     */
    public static void main(String[] args) {
        try {
            Map<String, String> argMap = parseArgs(args);

            String targetIp = argMap.get("dest");
            String targetPort = argMap.get("port");
            String filePath = argMap.get("f");
            String packetBytes = argMap.get("packetsize");
            String windowCapacity = argMap.get("sws");
            String timeoutMs = argMap.get("rtt");
            String drops = argMap.get("droplist");

            if (targetIp == null || targetPort == null || filePath == null || packetBytes == null ||
                    windowCapacity == null || timeoutMs == null) {
                System.out.println("Missing parameter. Expected output:");
                showUsage();
                return;
            }

            int port;
            try {
                port = Integer.parseInt(targetPort);
            } catch (NumberFormatException e) {
                System.out.println("Error parsing port: For input string: \"" + targetPort + "\"");
                showUsage();
                return;
            }

            int packetSize = Integer.parseInt(packetBytes);
            int slidingWindowSize ;
            try {
                slidingWindowSize = Integer.parseInt(windowCapacity);
                if (slidingWindowSize <= 0) {
                    System.out.println("Error: SWS must be positive.");
                    showUsage();
                    return;
                }
            } catch (NumberFormatException e) {
                showUsage();
                return;
            }
            int rtt = Integer.parseInt(timeoutMs);

            Set<Integer> dropPackets = parseDropList(drops);

            byte[] fileContent = Files.readAllBytes(new File(filePath).toPath());
            DatagramSocket senderSocket = new DatagramSocket();

            int baseIndex = 0;
            int nextSeq = 0;
            int totalPackets = (int) Math.ceil((double) fileContent.length / packetSize);
            Map<Integer, Long> sendTimestamps = new HashMap<>();
            int acknowledgedPacket = 0;
            while (baseIndex < totalPackets) {
                while (nextSeq < baseIndex + slidingWindowSize && nextSeq < totalPackets) {
                    sendFilePacket(senderSocket, InetAddress.getByName(targetIp), port, nextSeq, fileContent, packetSize, dropPackets, false);
                    sendTimestamps.put(nextSeq, System.currentTimeMillis());
                    nextSeq++;
                }

                try {
                    senderSocket.setSoTimeout(rtt * 2);
                    byte[] ackPayload = new byte[4];
                    DatagramPacket ackPacket = new DatagramPacket(ackPayload, ackPayload.length);
                    senderSocket.receive(ackPacket);

                    acknowledgedPacket = ByteBuffer.wrap(ackPacket.getData()).getInt();
                    System.out.println("Received ACK on " + acknowledgedPacket);

                    if (acknowledgedPacket >= baseIndex) {
                        baseIndex = acknowledgedPacket + 1;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timed out waiting for ACK");

                    for (int i = baseIndex; i < nextSeq; i++) {
                        if (System.currentTimeMillis() - sendTimestamps.get(i) >= rtt * 2) {
                            sendFilePacket(senderSocket, InetAddress.getByName(targetIp), port, i, fileContent, packetSize, new HashSet<>(), true);
                            sendTimestamps.put(i, System.currentTimeMillis());
                        }
                    }
                }
            }

            sendFinalPacket(senderSocket, InetAddress.getByName(targetIp), port, nextSeq, filePath);

            try {
                senderSocket.setSoTimeout(rtt * 2);
                byte[] ackPayload = new byte[4];
                DatagramPacket ackPacket = new DatagramPacket(ackPayload, ackPayload.length);
                senderSocket.receive(ackPacket);
                acknowledgedPacket = ByteBuffer.wrap(ackPacket.getData()).getInt();
                System.out.println("Received ACK " + acknowledgedPacket);
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout waiting for final ACK");
            }

            senderSocket.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    /**
     * Sends a data packet to the specified network address and port.
     * Handles packet creation including header, CRC, and actual file data.
     *
     * @param socket The DatagramSocket used for sending
     * @param addr The network address to send the packet to
     * @param port The network port to send the packet to
     * @param seqNum The sequence number of the packet
     * @param fileData The complete file data
     * @param packetSize The size of each packet
     * @param drops A set of packet numbers that should be "dropped" on first attempt
     * @param resend A flag indicating if this is a resend attempt
     * @throws IOException If an I/O error occurs
     */
    private static void sendFilePacket(DatagramSocket socket, InetAddress addr, int port, int seqNum, byte[] fileData, int packetSize, Set<Integer> drops, boolean resend) throws IOException {
        int start = seqNum * packetSize;
        int end = Math.min(start + packetSize, fileData.length);
        byte[] payload = Arrays.copyOfRange(fileData, start, end);

        CRC32 crcCalculator = new CRC32();
        crcCalculator.update(payload);
        int calculatedCrc = (drops.contains(seqNum) && !resend) ? 0 : (int) crcCalculator.getValue();

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + payload.length);
        buffer.putInt(seqNum);
        buffer.putInt(calculatedCrc);
        buffer.put(payload);

        DatagramPacket filePacket = new DatagramPacket(buffer.array(), buffer.array().length, addr, port);
        socket.send(filePacket);

        if (resend) {
            System.out.println("Resent packet " + seqNum);
        } else {
            System.out.println("Sent packet " + seqNum);
        }
    }


    /**
     * Sends a final packet to signal the end of the data transfer.
     *
     * @param socket The DatagramSocket used for sending
     * @param addr The network address to send the packet to
     * @param port The network port to send the packet to
     * @param seqNum The sequence number of the final packet
     * @param fileName The name of the file being sent
     * @throws IOException If an I/O error occurs
     */
    private static void sendFinalPacket(DatagramSocket socket, InetAddress addr, int port, int seqNum, String fileName) throws IOException {
        ByteBuffer eofPacket = ByteBuffer.allocate(9);
        eofPacket.putInt(seqNum);
        eofPacket.putInt(0);
        eofPacket.put((byte) -1);
        socket.send(new DatagramPacket(eofPacket.array(), eofPacket.array().length, addr, port));
        System.out.println("Sent final packet for " + fileName);
    }


    /**
     * Parses the command line arguments into a map for easy access.
     *
     * @param args The command line arguments
     * @return A map of argument keys to their values
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> argMap = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("-") && arg.contains("=")) {
                String[] parts = arg.substring(1).split("=", 2);
                argMap.put(parts[0], parts[1]);
            }
        }
        return argMap;
    }


    /**
     * Validates the input arguments for the sender configuration to ensure they are correctly formatted
     * and represent valid network and file settings.
     *
     * @param dest The destination IP address to validate.
     * @param port The destination port number to validate as an integer.
     * @param file The file path to validate.
     * @param packetSize The size of each packet to validate as an integer.
     * @param sws The sliding window size to validate as an integer.
     * @param rtt The round-trip time to validate as an integer.
     * @return true if all arguments are valid and correctly formatted, false if any validation fails due to
     *         incorrect formatting or invalid numerical or IP address values.
     * @throws NumberFormatException if port, packetSize, sws, or rtt cannot be parsed as integers.
     * @throws UnknownHostException if the dest IP address is not a valid IP address.
     */
    private static boolean validateArgs(String dest, String port, String file, String packetSize, String sws, String rtt) {
        try {
            InetAddress.getByName(dest);
            Integer.parseInt(port);
            Integer.parseInt(packetSize);
            Integer.parseInt(sws);
            Integer.parseInt(rtt);
            return true;
        } catch (NumberFormatException | UnknownHostException e) {
            return false;
        }
    }



    /**
     * Parses a list of packet numbers from a string, intended to simulate dropped packets.
     *
     * @param dropList A comma-separated string of packet numbers
     * @return A set of integers representing packet numbers to be dropped
     */
    private static Set<Integer> parseDropList(String dropList) {
        Set<Integer> drops = new HashSet<>();
        if (dropList != null) {
            for (String drop : dropList.split(",")) {
                try {
                    drops.add(Integer.parseInt(drop.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return drops;
    }


    /**
     * Prints the usage information for the command line arguments.
     */
    private static void showUsage() {
        System.out.println("Syntax: SlidingWindowClient-5785 -dest=ip -port=p -f=filename -packetsize=bytes -sws=size -rtt=ms [-droplist=1,2]");
        System.out.println("-droplist is optional.  Refers to packets that will be sent incorrectly the first time.  The list should be comma separated without spaces.  Example: 1,3,4");

    }
}