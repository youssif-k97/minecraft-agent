package com.mcap.minecraftagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class MinecraftLogHandler extends TextWebSocketHandler {
    private final Map<String, List<WebSocketSession>> worldSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String worldName = extractWorldName(session);
        worldSessions.computeIfAbsent(worldName, k -> new CopyOnWriteArrayList<>()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String worldName = extractWorldName(session);
        worldSessions.getOrDefault(worldName, new CopyOnWriteArrayList<>()).remove(session);
    }

    public void broadcastLog(String worldName, String logMessage) {
        List<WebSocketSession> sessions = worldSessions.getOrDefault(worldName, new CopyOnWriteArrayList<>());
        sessions.removeIf(session -> !session.isOpen());

        TextMessage message = new TextMessage(logMessage);
        sessions.forEach(session -> {
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.error("Failed to send log message to session: {}", session.getId(), e);
                // Handle exception
            }
        });
    }

    private String extractWorldName(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
