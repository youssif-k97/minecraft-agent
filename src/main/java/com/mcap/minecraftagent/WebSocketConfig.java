package com.mcap.minecraftagent;

import com.mcap.minecraftagent.config.MinecraftLogHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final MinecraftLogHandler logHandler;

    public WebSocketConfig(MinecraftLogHandler logHandler) {
        this.logHandler = logHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logHandler, "/ws/logs/{worldName}")
                .setAllowedOrigins("*");
    }
}
