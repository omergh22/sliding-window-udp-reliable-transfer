# Sliding-window reliable UDP file transfer

Java implementation of a **sliding-window transport** on top of **UDP**, adding reliability and ordering for file transfer over an unreliable channel. The work focuses on protocol mechanics—sequencing, cumulative acknowledgments, timeouts and retransmission, receive-side buffering, and **CRC32** payload integrity—similar in spirit to ideas used in real link-layer and transport designs.

## Why this project

Understanding how reliability is built on top of datagram delivery applies directly to **networking stacks, NIC/smartNIC offload, validation of packet handling, and distributed systems** where latency, loss, and ordering matter.

## Features

- **Sender:** configurable window (`sws`), packet size, RTT-based timeout, cumulative ACK-driven advancement, selective retransmit after timeout, optional **drop list** to stress-test loss and recovery.
- **Receiver:** receive window (`rws`), **out-of-order** buffering (`TreeMap`), CRC verification, duplicate/invalid handling with correct ACK semantics, EOF handling for clean session end.
- **Build:** Gradle multi-module project (JDK 21), JARs per application.
- **CI:** GitHub Actions workflow builds artifacts, runs the bundled test script, and publishes Javadoc (see `.github/workflows/`).

## Repository layout

| Module | Role |
|--------|------|
| `SlidingWindowSender` | Sends a file with sliding-window flow control and ACK processing |
| `SlidingWindowReceiver` | Listens on UDP, verifies CRC, reassembles in order, writes output file |

## Build

From the repository root:

```bash
./gradlew jar
```

On Windows:

```bat
gradlew.bat jar
```

JARs are produced under `SlidingWindowSender/build/libs/` and `SlidingWindowReceiver/build/libs/`.

## Run (example)

**Receiver** (listen):

```text
java -jar SlidingWindowReceiver/build/libs/SlidingWindowReceiver-5785.jar -ip=<listen-ip> -port=<port> -outfile=<output> -rws=<receive-window>
```

**Sender**:

```text
java -jar SlidingWindowSender/build/libs/SlidingWindowSender-5785.jar -dest=<ip> -port=<port> -f=<file> -packetsize=<bytes> -sws=<window> -rtt=<ms> [-droplist=1,2,3]
```

Optional `-droplist` simulates corrupted or dropped sends on first attempt for testing.

## Testing

Automated checks are driven by `sliding-window-tests.bat` (used in CI). Local runs should use the same script after a successful `jar` build.

## Author

- Omer Ghanem  

*Academic context: Introduction to Computer Networks — implementation and validation completed December 2024.*

## License

If you publish this repository publicly, add a `LICENSE` file (for example MIT or Apache-2.0) consistent with your institution’s rules and any joint work agreements.
