package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcap.minecraftagent.dto.BanKickPlayerDto;
import com.mcap.minecraftagent.dto.PlayerDto;
import com.mcap.minecraftagent.dto.PlayerDtoResponse;
import com.mcap.minecraftagent.pojo.Player;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.pojo.WorldPlayer;
import com.mcap.minecraftagent.repository.IPlayerRepository;
import com.mcap.minecraftagent.repository.IWorldPlayerRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

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

//    @Cacheable(value = "playerInfo", key = "#worldName")
    public PlayerDtoResponse getPlayers(String worldName) {
        log.info("Fetching player information for world: {}", worldName);
        List<PlayerDto> players = fetchPlayers(worldName);
        return new PlayerDtoResponse(players);
    }

    public List<PlayerDto> fetchPlayers(String worldName) {
        List<WorldPlayer> worldPlayers = worldPlayerRepository.findAllByWorldName(worldName);
        Map<String, String> onlinePlayers = rconClientService.getOnlinePlayers(worldName);
        if (worldPlayers.isEmpty()) {
            return new ArrayList<>();
        }else {
            return worldPlayers.stream()
                    .map(wp -> new PlayerDto(wp.getPlayer().getUuid(),wp.getPlayer().getUsername(),
                            wp.getLastLogin().toString(), wp.isBanned(), wp.isOp(), wp.isBypassesPlayerLimit(),
                            wp.getOpLevel(), wp.isWhitelisted(),
                            onlinePlayers.containsKey(wp.getPlayer().getUsername())))
                    .toList();
        }
    }

    public void kickPlayer(String worldName, BanKickPlayerDto playerDto) {
        try {
            rconClientService.kickPlayer(worldName, playerDto.name(), playerDto.reason());
        } catch (Exception e) {
            log.error("Failed to kick player: {}", playerDto.name(), e);
        }
    }

    @Transactional
    public void banPlayer(String worldName, BanKickPlayerDto playerDto) {
        try {
            rconClientService.banPlayer(worldName, playerDto.name(), playerDto.reason());

            WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, playerDto.uuid())
                    .orElse(null);
            if (worldPlayer != null) {
                worldPlayer.setBanned(true);
                worldPlayerRepository.save(worldPlayer);
            }else {
                throw new RuntimeException("Player not found in world: " + worldName);
            }
        } catch (Exception e) {
            log.error("Failed to ban player: " + playerDto.name(), e);
        }
    }

    @Transactional
    public void setPlayerOnline(String worldName, String username) {
        if (username != null) {
            try {
                Player player = findPlayerByUsername(username);
                if (player == null){
                    Player createdPlayer = createPlayer(username, findUuidFromUsercache(worldName, username));
                    createWorldPlayer(worldName, createdPlayer, false, false, false,
                            false, 0, LocalDateTime.now());
                } else {
                    // Check if player changed username and update
                    player = updatePlayerUsernameHistory(username, player);
                    String uuid = player.getUuid();
                    // Update last login time, create relationship if it doesn't exist
                    WorldPlayer existingRelationship = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, uuid).orElse(null);
                    if (existingRelationship == null) {
                        createWorldPlayer(worldName, player, false, false, false,
                                false, 0, LocalDateTime.now());
                    } else {
                        existingRelationship.setLastLogin(LocalDateTime.now());
                    }
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

        String uuid = usercache.stream()
                .filter(entry -> entry.name.equals(username))
                .map(entry -> entry.uuid)
                .findFirst()
                .orElse(null);
        if (uuid == null) {
            log.warn("UUID not found for player: {}", username);
        }
        return uuid;
    }

    private Player updatePlayerUsernameHistory(String username, Player player) {
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
        return player;
    }

    private Player createPlayer(String uuid, String username) {
        Player player = new Player();
        player.setUuid(uuid);
        player.setUsername(username);
        player.setPrevUsernames(new ArrayList<>());
        player = playerRepository.save(player);
        return player;
    }

    private void createWorldPlayer(String worldName, Player player, Boolean isBanned,
                                   Boolean isOp, Boolean isWhitelisted, Boolean bypassesPlayerLimit,
                                   Integer opLevel, LocalDateTime lastLogin) {
        WorldConfig config = configService.getConfig(worldName);

        WorldPlayer worldPlayer = new WorldPlayer();
        worldPlayer.setPlayer(player);
        worldPlayer.setWorld(config);
        worldPlayer.setBanned(isBanned);
        worldPlayer.setLastLogin(lastLogin);
        worldPlayer.setOp(isOp);
        worldPlayer.setWhitelisted(isWhitelisted);
        worldPlayer.setBypassesPlayerLimit(bypassesPlayerLimit);
        worldPlayer.setOpLevel(opLevel);

        worldPlayerRepository.save(worldPlayer);
    }

    @CacheEvict(value = "playerInfo", key = "#worldName")
    public void evictPlayerCache(String worldName) {
        log.info("Evicting player cache for world: {}", worldName);
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.DAYS)
    @PostConstruct
    public void syncUserCache(){
        log.info("Syncing usercache.json files");
        File baseDir = new File(this.baseDir);
        if (baseDir.exists() && baseDir.isDirectory()) {
            File[] worldDirs = baseDir.listFiles(File::isDirectory);
            if (worldDirs != null) {
                for (File worldDir : worldDirs) {
                    File usercacheFile = new File(worldDir.getAbsolutePath() + FileSystems.getDefault().getSeparator() + "usercache.json");
                    if (usercacheFile.exists()) {
                        try {
                            List<UsercacheEntry> usercache = objectMapper.readValue(usercacheFile,
                                    new TypeReference<List<UsercacheEntry>>() {});

                            for (UsercacheEntry entry : usercache) {
                                updatePlayerInfoFromUserCache(worldDir.getName(), entry.uuid, entry.name);
                            }
                        } catch (IOException e) {
                            log.error("Failed to sync usercache for world: {}", worldDir.getName(), e);
                        }
                    }
                }
            }
        }
    }

    @Transactional
    protected void updatePlayerInfoFromUserCache(String worldName, String uuid, String username) throws IOException {
        WorldPlayer existingRelationship = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, uuid).orElse(null);
        Player existingPlayer = playerRepository.findById(uuid).orElse(null);
        if (existingPlayer == null) {
            Player player = createPlayer(uuid, username);
            createWorldPlayer(worldName, player, false, false, false,
                    false, 0, null);
        } else if (existingRelationship == null) {
            createWorldPlayer(worldName, existingPlayer, false, false, false,
                    false, 0, null);
        }
    }

    public Player findPlayerByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }

        Player player = playerRepository.findByUsername(username);
        if (player != null) {
            return player;
        }

        player = playerRepository.findByPrevUsername(username);
        if (player != null) {
            return player;
        }

        return null;
    }

    private static class UsercacheEntry {
        public String name;
        public String uuid;
        public String expiresOn;
    }
}