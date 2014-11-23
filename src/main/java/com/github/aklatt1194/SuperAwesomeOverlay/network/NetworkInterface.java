package com.github.aklatt1194.SuperAwesomeOverlay.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.github.aklatt1194.SuperAwesomeOverlay.models.OverlayRoutingModel;
import com.github.aklatt1194.SuperAwesomeOverlay.utils.IPUtils;

public class NetworkInterface implements Runnable {
    public static final String[] NODES_BOOTSTRAP = {
            "c-174-61-223-52.hsd1.wa.comcast.net", // for testing purposes
            "ec2-54-72-49-50.eu-west-1.compute.amazonaws.com",
            "ec2-54-64-177-145.ap-northeast-1.compute.amazonaws.com",
            "ec2-54-172-69-181.compute-1.amazonaws.com" };
    private static final int LINK_PORT = 3333;

    private static NetworkInterface instance = null;

    private OverlayRoutingModel model;
    private Map<String, SocketChannel> tcpLinkTable;
    private Map<Integer, SimpleSocket> portMap;
    private Map<InetAddress, SocketChannel> newSocketChannels; // TODO make
                                                               // synchronized
                                                               // and add to it
                                                               // from
                                                               // OverlayModel

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private ByteBuffer readBuffer;
    private BlockingQueue<ServerDataEvent> readPackets;
    private BlockingQueue<ChangeRequest> pendingRequests;
    private Map<SocketChannel, BlockingQueue<ByteBuffer>> pendingWrites;
    private Map<SocketChannel, ByteBuffer> pendingReads;

    public static NetworkInterface getInstance() {
        if (instance == null)
            instance = new NetworkInterface();

        return instance;
    }

    private NetworkInterface() {
    }

    public void initialize(OverlayRoutingModel model) throws IOException {
        this.model = model;
        tcpLinkTable = new ConcurrentHashMap<>();
        portMap = new ConcurrentHashMap<>();
        newSocketChannels = new ConcurrentHashMap<>();

        readBuffer = ByteBuffer.allocateDirect(8192);
        readPackets = new LinkedBlockingQueue<>();
        pendingRequests = new LinkedBlockingQueue<>();
        pendingWrites = new ConcurrentHashMap<>();
        pendingReads = new ConcurrentHashMap<>();

        // create the serverChannel and selector
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        selector = SelectorProvider.provider().openSelector();
        InetSocketAddress isa = new InetSocketAddress(LINK_PORT);

        serverChannel.socket().bind(isa);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        // try to connect to each of the bootstrap nodes
        Selector connectSelector = SelectorProvider.provider().openSelector();
        for (String node : NODES_BOOTSTRAP) {
            InetAddress addr = InetAddress.getByName(node);

            if (IPUtils.compareIPs(addr, model.getSelfAddress()) != 0) {
                SocketChannel socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);

                socketChannel.connect(new InetSocketAddress(addr, LINK_PORT));
                socketChannel
                        .register(connectSelector, SelectionKey.OP_CONNECT);
            }
        }

        // finish connecting to connectable nodes, giveup after 1000 ms
        for (int i = 0; i < NODES_BOOTSTRAP.length
                && connectSelector.select(1000) > 0; i++) {
            for (SelectionKey key : connectSelector.selectedKeys()) {
                SocketChannel channel = (SocketChannel) key.channel();

                if (key.isConnectable()) {
                    if (channel.isConnectionPending()) {
                        channel.finishConnect();

                        // add this address to the model
                        model.addNode(channel.socket().getInetAddress());
                    }
                }
            }
        }
        connectSelector.close();

        // now that we have added all of the connectable addresses, update model
        model.rebuildMatrix();
        model.updateModel();

        // start the main thread
        Thread thread = new Thread(this);
        thread.start();

        // start up the packet router
        thread = new Thread(new PacketRouter());
        thread.start();
    }

    @Override
    public void run() {
        while (true) {
            try {
                if (!pendingRequests.isEmpty()) {
                    ChangeRequest request = pendingRequests.take();
                    SelectionKey key = request.socket.keyFor(selector);
                    if (key != null) {
                        key.interestOps(request.ops);
                    }
                }

                // wait for an event
                this.selector.select();

                Iterator<SelectionKey> selectedKeys = this.selector
                        .selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid())
                        continue;

                    if (key.isAcceptable()) {
                        this.accept(key);
                    } else if (key.isReadable()) {
                        this.read(key);
                    } else if (key.isWritable()) {
                        this.write(key);
                    }
                }

                // add any new connections to the selector
                for (InetAddress addr : newSocketChannels.keySet()) {
                    newSocketChannels.remove(addr).register(selector,
                            SelectionKey.OP_READ);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Accept a new connection. Save this socket in the tcpLinkTable and then
    // add it to the selector.
    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key
                .channel();

        SocketChannel socketChannel = serverSocketChannel.accept();
        // Socket socket = socketChannel.socket(); // add something to table?
        socketChannel.configureBlocking(false);

        // figure out the remote address
        InetAddress addr = socketChannel.socket().getInetAddress();

        // if a node that we are already connected to is connecting to us, we
        // should remove the old socketchannel and use the new one
        SocketChannel prevChannel = tcpLinkTable.get(addr);
        if (prevChannel != null) {
            prevChannel.keyFor(selector).cancel();
            prevChannel.close();
        }

        // add the new socketChannel to the table
        tcpLinkTable.put(addr.getHostAddress(), socketChannel);

        // add this newly connected node to model
        model.addNode(addr);
        model.updateModel();

        // set selector to notify when data is to be read
        socketChannel.register(this.selector, SelectionKey.OP_READ);
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        // clear out the read buffer then do the read
        this.readBuffer.clear();
        int numRead;
        try {
            numRead = socketChannel.read(this.readBuffer);
        } catch (IOException e) {
            // remove the key and close the socket channel
            key.cancel();
            socketChannel.close();
            return;
        }

        if (numRead == -1) {
            // Remote closed socket cleanly
            key.cancel();
            socketChannel.close();
            return;
        }

        readBuffer.flip();
        ByteBuffer pendingBytes = pendingReads.get(socketChannel);
        ByteBuffer totalBytes;

        if (pendingBytes != null) {
            pendingReads.put(socketChannel, null);
            totalBytes = ByteBuffer.allocate(pendingBytes.remaining()
                    + readBuffer.remaining());
            totalBytes.put(pendingBytes);
            totalBytes.put(readBuffer);
        } else {
            totalBytes = readBuffer;
        }

        totalBytes.rewind();

        // stick the packet on the readPackets queue for the PacketHandler to
        // deal with
        while (true) {
            SimpleDatagramPacket packet = SimpleDatagramPacket
                    .createFromBuffer(totalBytes, socketChannel.socket()
                            .getInetAddress(), model.getSelfAddress());

            if (packet == null) {
                if (totalBytes.remaining() > 0) {
                    // if there are any bytes left, stick them on the pending
                    // buffer. we will append them to the front of the read
                    // buffer
                    // the next time this method is called
                    ByteBuffer remaining = ByteBuffer.allocate(totalBytes
                            .remaining());
                    remaining.put(totalBytes);
                    pendingReads.put(socketChannel, remaining);
                }
                break;
            }

            while (true) {
                try {
                    readPackets.put(new ServerDataEvent(socketChannel, packet));
                    break;
                } catch (InterruptedException e) {
                    continue;
                }
            }

        }
    }

    // Queues up a packet so that it can be sent by the selector
    protected void send(SimpleDatagramPacket packet) {
        // place a pending request on the queue for this socketchannel to be
        // changed to write
        SocketChannel socket = tcpLinkTable.get(packet.getDestination()
                .getHostAddress());

        if (socket == null) {
            // we aren't actually connected to the destination
            // TODO:
            return;
        }

        while (true) {
            try {
                pendingRequests.put(new ChangeRequest(socket,
                        SelectionKey.OP_WRITE));
                break;
            } catch (InterruptedException e) {
                continue;
            }
        }

        // place the data onto the pending writes queue for the proper socket
        BlockingQueue<ByteBuffer> queue = pendingWrites.get(socket);
        if (queue == null) {
            queue = new LinkedBlockingQueue<ByteBuffer>();
            pendingWrites.put(socket, queue);
        }

        while (true) {
            try {
                queue.put(packet.getRawPacket());
                break;
            } catch (InterruptedException e) {
                continue;
            }
        }

        // Wakeup the selector thread
        selector.wakeup();
    }

    // pull any pending writes of a socket's queue and write them to the socket
    private void write(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        BlockingQueue<ByteBuffer> queue = pendingWrites.get(socketChannel);

        while (!queue.isEmpty()) {
            ByteBuffer buf = queue.peek();

            socketChannel.write(buf);
            if (buf.remaining() > 0) {
                // the socket buffer is full
                break;
            }
            queue.remove();
        }

        // we are done, set the key back to read
        if (queue.isEmpty())
            key.interestOps(SelectionKey.OP_READ);
    }

    // bind a SimpleSocket to a specific port
    protected void bindSocket(SimpleSocket simpleSocket, int port)
            throws SocketException {

        if (portMap.get(port) != null)
            throw new SocketException();

        portMap.put(port, simpleSocket);
    }

    // close a SimpleSocket
    protected void closeSocket(SimpleSocket simpleSocket) {
        portMap.remove(simpleSocket);
    }

    /**
     * A thread that multiplexes incoming packets to the correct ports
     */
    private class PacketRouter implements Runnable {
        @Override
        public void run() {
            ServerDataEvent dataEvent = null;

            while (true) {
                try {
                    dataEvent = readPackets.take();
                } catch (InterruptedException e) {
                    continue;
                }

                if (portMap.get(dataEvent.packet.getDestinationPort()) == null) {
                    // this port isn't open, drop packet
                    continue;
                }

                BlockingQueue<SimpleDatagramPacket> readQueue = portMap
                        .get(dataEvent.packet.getDestinationPort()).readQueue;

                try {
                    readQueue.put(dataEvent.packet);
                } catch (InterruptedException e) {
                    continue;
                }
            }
        }
    }

    // helper class with the fields needed to queue up a key change request
    private class ChangeRequest {
        private SocketChannel socket;
        private int ops;

        private ChangeRequest(SocketChannel socket, int ops) {
            this.socket = socket;
            this.ops = ops;
        }
    }

    // helper class with the fields needed to queue up a server read event
    @SuppressWarnings("unused")
    private class ServerDataEvent {
        private SocketChannel socket;
        private SimpleDatagramPacket packet;

        private ServerDataEvent(SocketChannel socket,
                SimpleDatagramPacket packet) {
            this.socket = socket;
            this.packet = packet;
        }
    }

}
