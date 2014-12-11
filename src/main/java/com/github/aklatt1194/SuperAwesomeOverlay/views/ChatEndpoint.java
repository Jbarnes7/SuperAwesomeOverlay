package com.github.aklatt1194.SuperAwesomeOverlay.views;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import com.github.aklatt1194.SuperAwesomeOverlay.network.OverlaySocket;
import com.github.aklatt1194.SuperAwesomeOverlay.network.SimpleDatagramPacket;

@ServerEndpoint(value = "/chat")
public class ChatEndpoint {
    private static final int PORT = 54321;
    private static Charset charset = Charset.forName("UTF-8");
    
    private static List<Session> sessions;
    private static OverlaySocket socket;
    private static ReceiverThread receiver;
    
    public static void init() {
        sessions = new ArrayList<Session>();
        
        socket = new OverlaySocket();
        socket.bind(PORT);
        
        receiver = new ReceiverThread(socket);
        receiver.start();
    }
    
    @OnOpen
    public void open(Session session) {
         sessions.add(session);
    }
    

    @OnClose
    public void closedConnection(Session session) {
        sessions.remove(session);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        SimpleDatagramPacket packet = new SimpleDatagramPacket(null, null, PORT, 
                PORT, message.getBytes(charset));
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("Error sending message to socket");
            e.printStackTrace();
        }
    }
    
    /**
     * Write the message to the websocket
     */
    private static void send(String msg) {
        for (Session session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
                
            try {
                session.getBasicRemote().sendText(msg);
            } catch (IOException e) {
                System.err.println("Failed to deliver message to chat frontend");
                e.printStackTrace();
            }
            send(message);
        }
    }
    
    /**
     * A helper thread that will receive packets and write their contents to the
     * websocket
     */
    private static class ReceiverThread extends Thread {
        private OverlaySocket socket;
        
        public ReceiverThread(OverlaySocket socket) {
            this.socket = socket;
        }
        
        @Override
        public void run() {
            while (true) {
                SimpleDatagramPacket packet = socket.receive();
                String message =  new String(packet.getPayload(), charset);
                send(message);
            }
        }
    }
}
