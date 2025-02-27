package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcap.minecraftagent.dto.PlayerDto;
import com.mcap.minecraftagent.dto.PlayerDtoResponse;
import com.mcap.minecraftagent.pojo.Player;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.pojo.WorldPlayer;
import com.mcap.minecraftagent.repository.IPlayerRepository;
import com.mcap.minecraftagent.repository.IWorldPlayerRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    @Cacheable(value = "playerInfo", key = "#worldName")
    public PlayerDtoResponse getPlayers(String worldName) {
        log.info("Fetching player information for world: {}", worldName);
        List<PlayerDto> players = fetchPlayers(worldName);
        return new PlayerDtoResponse(players);
    }

    public List<PlayerDto> fetchPlayers(String worldName) {
        List<WorldPlayer> worldPlayers = worldPlayerRepository.findAllByWorldName(worldName);
        List<String> onlinePlayers = rconClientService.getOnlinePlayers(worldName);
        if (worldPlayers.isEmpty()) {
            return new ArrayList<>();
        }else {
            return worldPlayers.stream()
                    .map(wp -> new PlayerDto(wp.getPlayer().getUsername(), wp.getLastLogin().toString(),
                            wp.isBanned(), wp.isOp(), wp.isBypassesPlayerLimit(), wp.getOpLevel(), wp.isWhitelisted(),
                            onlinePlayers.contains(wp.getPlayer().getUsername())))
                    .toList();
        }
    }

    public void kickPlayer(String worldName, String playerName) {
        try {
            rconClientService.sendCommand("kick " + playerName);
        } catch (Exception e) {
            log.error("Failed to kick player: " + playerName, e);
        }
    }

    @Transactional
    public void banPlayer(String worldName, String playerName) {
        try {
            rconClientService.sendCommand("ban " + playerName);
            WorldConfig config = configService.getConfig(worldName);

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
                        worldPlayerRepository.save(worldPlayer.get());
                    } else {
                        createWorldPlayer(worldName, player, true);
                    }
                } else {
                    // Player doesn't exist in repository, create it first
                    player = new Player();
                    player.setUuid(uuid);
                    player.setUsername(playerName);
                    player.setPrevUsernames(new ArrayList<>());

                    // Save to repository
                    player = playerRepository.save(player);

                    createWorldPlayer(worldName, player, true);
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
                        worldPlayerRepository.save(worldPlayer.get());
                    } else {
                        // Player exists but isn't in this world yet, create relationship
                        createWorldPlayer(worldName, player, true);
                    }
                } else {
                    log.warn("Cannot ban player {}: UUID not found in usercache and username not found in repository", playerName);
                }
            }
        } catch (Exception e) {
            log.error("Failed to ban player: " + playerName, e);
        }
    }

    @Transactional
    public void setPlayerOnline(String worldName, String line) {
        String username = null;
        if (line != null && line.contains("joined the game")) {
            int infoIndex = line.lastIndexOf("]: ");
            if (infoIndex >= 0) {
                String message = line.substring(infoIndex + 3);
                int joinedIndex = message.indexOf(" joined the game");
                if (joinedIndex > 0) {
                    username = message.substring(0, joinedIndex);
                }
            }
        }
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

    @Transactional
    protected void updatePlayerInfo(String worldName, String uuid, String username) throws IOException {
        Optional<WorldPlayer> existingRelationship = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, uuid);

        if (existingRelationship.isPresent()) {
            WorldPlayer worldPlayer = existingRelationship.get();
            Player player = worldPlayer.getPlayer();

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
            Player player = playerRepository.findById(uuid).orElse(null);

            if (player != null) {
                if (!player.getUsername().equals(username)) {
                    List<String> prevUsernames = player.getPrevUsernames();
                    if (prevUsernames == null) {
                        player.setPrevUsernames(new ArrayList<>());
                    }
                    if (!prevUsernames.contains(player.getUsername())) {
                        prevUsernames.add(player.getUsername());
                        player.setPrevUsernames(prevUsernames);
                    }
                    player.setUsername(username);
                    player = playerRepository.save(player);
                }
            } else {
                player = new Player();
                player.setUuid(uuid);
                player.setUsername(username);
                player.setPrevUsernames(new ArrayList<>());
                player = playerRepository.save(player);
            }

            createWorldPlayer(worldName, player, false);

            log.info("Created new world-player relationship for {} ({}) in world {}",
                    username, uuid, worldName);

        }
    }

    private void createWorldPlayer(String worldName, Player player, Boolean isBanned) {
        WorldConfig config = configService.getConfig(worldName);

        WorldPlayer worldPlayer = new WorldPlayer();
        worldPlayer.setPlayer(player);
        worldPlayer.setWorld(config);
        worldPlayer.setBanned(isBanned);
        worldPlayer.setLastLogin(LocalDateTime.now());

        worldPlayerRepository.save(worldPlayer);
    }

    @CacheEvict(value = "playerInfo", key = "#worldName")
    public void evictPlayerCache(String worldName) {
        log.info("Evicting player cache for world: {}", worldName);
    }

    private static class UsercacheEntry {
        public String name;
        public String uuid;
        public String expiresOn;
    }
}