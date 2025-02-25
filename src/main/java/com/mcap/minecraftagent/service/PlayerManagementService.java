package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcap.minecraftagent.pojo.Player;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.pojo.WorldPlayer;
import com.mcap.minecraftagent.repository.IPlayerRepository;
import com.mcap.minecraftagent.repository.IWorldPlayerRepository;
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
    private final IPlayerRepository playerRepository;
    private final IWorldPlayerRepository worldPlayerRepository;

    public PlayerManagementService(RconClientService rconClientService, ConfigurationService configService,
                                   ObjectMapper objectMapper, IPlayerRepository playerRepository,
                                   IWorldPlayerRepository worldPlayerRepository) {
        this.baseDir = System.getProperty("user.home") + FileSystems.getDefault().getSeparator() + "minecraft-servers" + FileSystems.getDefault().getSeparator();
        this.rconClientService = rconClientService;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.playerRepository = playerRepository;
        this.worldPlayerRepository = worldPlayerRepository;
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

            // Try to find the UUID from username using the usercache.json
            String uuid = null;
            try {
                uuid = findUuidFromUsercache(worldName, playerName);
            } catch (IOException e) {
                log.warn("Could not access usercache.json for world: {}", worldName, e);
            }

            if (uuid != null) {
                // Use UUID to find the player
                Player player = playerRepository.findById(uuid).orElse(null);

                if (player != null) {
                    // Find the WorldPlayer entry for this player
                    String finalUuid = uuid;
                    Optional<WorldPlayer> worldPlayer = config.getWorldPlayers().stream()
                            .filter(wp -> wp.getPlayer().getUuid().equals(finalUuid))
                            .findFirst();

                    if (worldPlayer.isPresent()) {
                        worldPlayer.get().setBanned(true);
                        configService.saveConfig(config);
                    } else {
                        // Player exists but isn't in this world yet, create relationship
                        config.addPlayer(player, true, null);
                        configService.saveConfig(config);
                    }
                } else {
                    // Player doesn't exist in repository, create it first
                    player = new Player();
                    player.setUuid(uuid);
                    player.setUsername(playerName);
                    player.setPrevUsernames(new ArrayList<>());

                    // Save to repository
                    player = playerRepository.save(player);

                    // Add to world with banned status
                    config.addPlayer(player, true, null);
                    configService.saveConfig(config);
                }
            } else {
                // Fallback to username lookup if UUID cannot be found
                Player player = playerRepository.findByUsername(playerName);

                if (player != null) {
                    // Find the WorldPlayer entry for this player
                    Optional<WorldPlayer> worldPlayer = config.getWorldPlayers().stream()
                            .filter(wp -> wp.getPlayer().getUuid().equals(player.getUuid()))
                            .findFirst();

                    if (worldPlayer.isPresent()) {
                        worldPlayer.get().setBanned(true);
                        configService.saveConfig(config);
                    } else {
                        // Player exists but isn't in this world yet, create relationship
                        config.addPlayer(player, true, null);
                        configService.saveConfig(config);
                    }
                } else {
                    log.warn("Cannot ban player {}: UUID not found in usercache and username not found in repository", playerName);
                }
            }
        } catch (Exception e) {
            log.error("Failed to ban player: " + playerName, e);
        }
    }

    public List<Player> getPlayers(String worldName) {
        WorldConfig config = configService.getConfig(worldName);
        return config.getPlayers();
    }

    public boolean isPlayerBanned(String worldName, String playerName) {
        // Try to find UUID from usercache first
        String uuid = null;
        try {
            uuid = findUuidFromUsercache(worldName, playerName);
        } catch (IOException e) {
            log.warn("Could not access usercache.json for world: {}", worldName, e);
        }

        // Use repository for direct database query rather than in-memory filtering
        if (uuid != null) {
            // Check by UUID (more reliable)
            Optional<WorldPlayer> worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, uuid);
            return worldPlayer.map(WorldPlayer::isBanned).orElse(false);
        } else {
            // Fall back to username check
            Optional<WorldPlayer> worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUsername(worldName, playerName);
            return worldPlayer.map(WorldPlayer::isBanned).orElse(false);
        }
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

        // Find or create player
        Player player = null;
        WorldPlayer worldPlayer = null;

        // First, check if this player already exists in this world
        Optional<WorldPlayer> existingWorldPlayer = config.getWorldPlayers().stream()
                .filter(wp -> wp.getPlayer().getUuid().equals(uuid))
                .findFirst();

        if (existingWorldPlayer.isPresent()) {
            worldPlayer = existingWorldPlayer.get();
            player = worldPlayer.getPlayer();

            // Update username if it changed
            if (!player.getUsername().equals(username)) {
                if (player.getPrevUsernames() == null) {
                    player.setPrevUsernames(new ArrayList<>());
                }
                if (!player.getPrevUsernames().contains(player.getUsername())) {
                    player.getPrevUsernames().add(player.getUsername());
                }
                player.setUsername(username);
            }

            // Update last login time
            worldPlayer.setLastLogin(LocalDateTime.now());
        } else {
            // Check if player exists in the database by UUID
            player = playerRepository.findById(uuid).orElse(null);

            if (player != null) {
                // Update username if it changed
                if (!player.getUsername().equals(username)) {
                    if (player.getPrevUsernames() == null) {
                        player.setPrevUsernames(new ArrayList<>());
                    }
                    if (!player.getPrevUsernames().contains(player.getUsername())) {
                        player.getPrevUsernames().add(player.getUsername());
                    }
                    player.setUsername(username);
                }
            } else {
                // Check if player exists with this username
                player = playerRepository.findByUsername(username);

                if (player != null && !player.getUuid().equals(uuid)) {
                    // Username exists but for a different UUID (name change)
                    log.warn("Username {} now associated with a different UUID. Old: {}, New: {}",
                            username, player.getUuid(), uuid);

                    // Create a new player instead
                    player = new Player();
                    player.setUuid(uuid);
                    player.setUsername(username);
                    player.setPrevUsernames(new ArrayList<>());
                } else if (player == null) {
                    // Create a new player
                    player = new Player();
                    player.setUuid(uuid);
                    player.setUsername(username);
                    player.setPrevUsernames(new ArrayList<>());
                }
            }

            // Save player to repository first
            player = playerRepository.save(player);

            // Add player to this world
            config.addPlayer(player, false, LocalDateTime.now());
        }

        configService.saveConfig(config);
    }

    private static class UsercacheEntry {
        public String name;
        public String uuid;
        public long expiresOn;
    }
}