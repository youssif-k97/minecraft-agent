package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcap.minecraftagent.pojo.Player;
import com.mcap.minecraftagent.pojo.WorldConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class PlayerManagementService {
    private final String baseDir;
    private final RconClientService rconClientService;
    private final ConfigurationService configService;
    private final ObjectMapper objectMapper;

    public PlayerManagementService(RconClientService rconClientService, ConfigurationService configService, ObjectMapper objectMapper) {
        this.baseDir = System.getProperty("user.home") + FileSystems.getDefault().getSeparator() + "minecraft-servers" + FileSystems.getDefault().getSeparator();
        this.rconClientService = rconClientService;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    public void kickPlayer(String worldName, String playerName) {
        try {
            rconClientService.sendCommand("kick " + playerName);
        } catch (Exception e) {
            log.error("Failed to kick player: " + playerName, e);
        }
    }

    public void banPlayer(String worldName, String playerName) {
        try {
            rconClientService.sendCommand("ban " + playerName);
            WorldConfig config = configService.getConfig(worldName);
            Optional<Player> player = config.getPlayers().stream()
                .filter(p -> p.getUsername().equals(playerName))
                .findFirst();
            
            if (player.isPresent()) {
                player.get().setBanned(true);
                configService.saveConfig(config);
            }
        } catch (Exception e) {
            log.error("Failed to ban player: " + playerName, e);
        }
    }

    public List<Player> getPlayers(String worldName) {
        WorldConfig config = configService.getConfig(worldName);
        return config.getPlayers() != null ? config.getPlayers() : new ArrayList<>();
    }

    public void setPlayerOnline(String worldName, String username) {
        if (username != null) {
            try {
                String uuid = findUuidFromUsercache(worldName, username);
                if (uuid != null) {
                    updatePlayerInfo(worldName, uuid, username);
                } else {
                    log.warn("Could not find UUID for player {} in usercache.json", username);
                }
            } catch (IOException e) {
                log.error("Failed to update player info", e);
            }
        }
    }

    private String findUuidFromUsercache(String worldName, String username) throws IOException {
        File usercacheFile = new File(baseDir + worldName + FileSystems.getDefault().getSeparator() + "usercache.json");
        if (!usercacheFile.exists()) {
            log.warn("usercache.json not found for world: {}", worldName);
            return null;
        }

        List<UsercacheEntry> usercache = objectMapper.readValue(usercacheFile, 
            new TypeReference<List<UsercacheEntry>>() {});

        return usercache.stream()
            .filter(entry -> entry.name.equals(username))
            .map(entry -> entry.uuid)
            .findFirst()
            .orElse(null);
    }

    private void updatePlayerInfo(String worldName, String uuid, String username) throws IOException {
        WorldConfig config = configService.getConfig(worldName);
        if (config.getPlayers() == null) {
            config.setPlayers(new ArrayList<>());
        }

        Optional<Player> existingPlayer = config.getPlayers().stream()
            .filter(p -> p.getUuid().equals(uuid))
            .findFirst();

        if (existingPlayer.isPresent()) {
            Player player = existingPlayer.get();
            if (!player.getUsername().equals(username)) {
                if (player.getPrevUsernames() == null) {
                    player.setPrevUsernames(new ArrayList<>());
                }
                if (!player.getPrevUsernames().contains(player.getUsername())) {
                    player.getPrevUsernames().add(player.getUsername());
                }
                player.setUsername(username);
            }
            player.setLastLogin(LocalDateTime.now());
        } else {
            Player newPlayer = new Player();
            newPlayer.setUuid(uuid);
            newPlayer.setUsername(username);
            newPlayer.setLastLogin(LocalDateTime.now());
            newPlayer.setPrevUsernames(new ArrayList<>());
            newPlayer.setBanned(false);
            config.getPlayers().add(newPlayer);
        }

        configService.saveConfig(config);
    }

    private static class UsercacheEntry {
        public String name;
        public String uuid;
        public long expiresOn;
    }
}
