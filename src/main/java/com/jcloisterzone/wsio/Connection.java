package com.jcloisterzone.wsio;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.jcloisterzone.wsio.MessageParser.Command;
import com.jcloisterzone.wsio.message.HelloMessage;
import com.jcloisterzone.wsio.message.WelcomeMessage;

public class Connection {

    protected final transient Logger logger = LoggerFactory.getLogger(getClass());

    private MessageParser parser = new MessageParser();
    private WebSocketClient ws;

    private EventBus messageBus = new EventBus();

    private long clientId = -1; //TODO use Strign, now backward compatible with legacy code
    private String sessionKey;

    public Connection(URI uri, final Object receiver) {
        messageBus.register(receiver);
        ws = new WebSocketClient(uri) {
            @Override
            public void onClose(int code, String reason, boolean remote) {
                // TODO Auto-generated method stub

            }

            @Override
            public void onError(Exception ex) {
                logger.error(ex.getMessage(), ex);
            }

            @Override
            public void onMessage(String message) {
                logger.info(message);
                Command cmd = parser.fromJson(message);
                if ("WELCOME".equals(cmd.command)) {
                    WelcomeMessage welcomeMsg = (WelcomeMessage) cmd.arg;
                    clientId = welcomeMsg.getClientId().hashCode();
                    sessionKey = welcomeMsg.getSessionKey();
                }
                messageBus.post(cmd.arg);
            }

            @Override
            public void onOpen(ServerHandshake arg0) {
                sendMessage("HELLO", new HelloMessage("WsFarin"));
            }
        };
        ws.connect();
    }

    public void sendMessage(String command, Object arg) {
        ws.send(parser.toJson(command, arg));
    }

    public void close() {
        ws.close();
    }

    public long getClientId() {
        return clientId;
    }

    public String getSessionKey() {
        return sessionKey;
    }
}