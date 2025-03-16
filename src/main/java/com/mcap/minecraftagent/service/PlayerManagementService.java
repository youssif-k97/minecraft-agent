package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcap.minecraftagent.dto.BanKickPlayerDto;
import com.mcap.minecraftagent.dto.PlayerDto;
import com.mcap.minecraftagent.dto.PlayerDtoResponse;
import com.mcap.minecraftagent.dto.PlayerOpDto;
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
    private final ServerPropertiesService serverPropertiesService;
    private final ConfigurationService configService;
    private final ObjectMapper objectMapper;
    private final IPlayerRepository playerRepository;
    private final IWorldPlayerRepository worldPlayerRepository;

    public PlayerManagementService(RconClientService rconClientService, ConfigurationService configService,
                                   ObjectMapper objectMapper, IPlayerRepository playerRepository,
                                   IWorldPlayerRepository worldPlayerRepository, ServerPropertiesService serverPropertiesService) {
        this.baseDir = System.getProperty("user.home") + FileSystems.getDefault().getSeparator() + "minecraft-servers" + FileSystems.getDefault().getSeparator();
        this.rconClientService = rconClientService;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.playerRepository = playerRepository;
        this.worldPlayerRepository = worldPlayerRepository;
        this.serverPropertiesService = serverPropertiesService;
    }

//    @Cacheable(value = "playerInfo", key = "#worldName")
    public PlayerDtoResponse getPlayers(String worldName) {
        log.info("Fetching player information for world: {}", worldName);
        List<PlayerDto> players = fetchPlayers(worldName);
        return new PlayerDtoResponse(players);
    }

    public List<PlayerDto> fetchPlayers(String worldName) {
        List<WorldPlayer> worldPlayers = worldPlayerRepository.findAllByWorldName(worldName);
        Map<String, String> onlinePlayers = new HashMap<>();
        WorldConfig config = configService.getConfig(worldName);
        if (config.isRunning()) {
            try {
                onlinePlayers = rconClientService.getOnlinePlayers(worldName);
            } catch (Exception e) {
                log.error("Failed to get online players", e);
            }
        }
        if (worldPlayers.isEmpty()) {
            return new ArrayList<>();
        }else {
            Map<String, String> finalOnlinePlayers = onlinePlayers;
            return worldPlayers.stream()
                    .map(wp -> new PlayerDto(wp.getPlayer().getUuid(),wp.getPlayer().getUsername(),
                            wp.getLastLogin() != null ? wp.getLastLogin().toString() : null, wp.isBanned(), wp.isOp(), wp.isBypassesPlayerLimit(),
                            wp.getOpLevel(), wp.isWhitelisted(),
                            finalOnlinePlayers.containsKey(wp.getPlayer().getUsername())))
                    .toList();
        }
    }

    public String kickPlayer(String worldName, BanKickPlayerDto playerDto) {
        try {
            String response = rconClientService.kickPlayer(worldName, playerDto.name(), playerDto.reason());
            if (response != null && response.contains("Kicked "+playerDto.name())) {
                log.info("Player {} kicked from world {}", playerDto.name(), worldName);
                return response;
            } else {
                log.warn("Failed to kick player: {}", playerDto.name());
                return response;
            }
        } catch (Exception e) {
            log.error("Failed to kick player: {}", playerDto.name(), e);
            throw new RuntimeException("Failed to kick player: " + playerDto.name(), e);
        }
    }

    @Transactional
    public String handlePlayerRawCommand(String worldName, String rawCommand) {
        String[] commandParts = rawCommand.split(" ");
        String playerName = commandParts[1];
        WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUsername(worldName, playerName).orElse(null);
        if(worldPlayer == null) {
            return "Player not found in world: " + worldName;
        }
        String response = null;
        rawCommand = rawCommand.toLowerCase();
        if(rawCommand.startsWith("ban")) {
            String reason = String.join(" ", Arrays.copyOfRange(commandParts, 2, commandParts.length));
            BanKickPlayerDto playerDto = new BanKickPlayerDto(worldPlayer.getPlayer().getUuid(), worldPlayer.getPlayer().getUsername(), reason);
            response = banPlayer(worldName, playerDto);
        } else if(rawCommand.startsWith("pardon")) {
            response = pardonPlayer(worldName, playerName);
        } else if(rawCommand.startsWith("kick")) {
            String reason = String.join(" ", Arrays.copyOfRange(commandParts, 2, commandParts.length));
            BanKickPlayerDto playerDto = new BanKickPlayerDto(worldPlayer.getPlayer().getUuid(), worldPlayer.getPlayer().getUsername(), reason);
            response = kickPlayer(worldName, playerDto);
        } else if(rawCommand.startsWith("op")) {
            int defaultOpLevel = this.serverPropertiesService.getProperties(worldName).properties().get("op-permission-level") != null ?
                    Integer.parseInt(this.serverPropertiesService.getProperties(worldName).properties().get("op-permission-level")) : 4;
            PlayerOpDto playerDto = new PlayerOpDto(worldPlayer.getPlayer().getUuid(), worldPlayer.getPlayer().getUsername(),
                    true, defaultOpLevel, false);
            response = opPlayer(worldName, playerDto);
        } else if(rawCommand.startsWith("deop")) {
            PlayerOpDto playerDto = new PlayerOpDto(worldPlayer.getPlayer().getUuid(), worldPlayer.getPlayer().getUsername(),
                    false, 0, false);
            response = removeOpPlayer(worldName, playerDto);
        }
        return response;
    }

    @Transactional
    public String banPlayer(String worldName, BanKickPlayerDto playerDto) {
        try {
            String response = rconClientService.banPlayer(worldName, playerDto.name(), playerDto.reason());
            if (response!=null && response.contains("Banned "+playerDto.name())) {
                log.info("Player {} banned from world {}", playerDto.name(), worldName);
                WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, playerDto.uuid())
                        .orElse(null);
                if (worldPlayer != null) {
                    worldPlayer.setBanned(true);
                    worldPlayerRepository.save(worldPlayer);
                    return response;
                }else {
                    throw new RuntimeException("Player not found in world: " + worldName);
                }
            } else {
                log.warn("Failed to ban player: {}", playerDto.name());
                return response;
            }

        } catch (Exception e) {
            log.error("Failed to ban player: " + playerDto.name(), e);
            throw new RuntimeException("Failed to ban player: " + playerDto.name(), e);
        }
    }

    @Transactional
    public String pardonPlayer(String worldName, String playerName){
        try {
            String response = rconClientService.pardonPlayer(worldName, playerName);
            if (response != null && response.contains("Unbanned "+playerName)) {
                log.info("Player {} pardoned from world {}", playerName, worldName);
                WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUsername(worldName, playerName)
                        .orElse(null);
                if (worldPlayer != null) {
                    worldPlayer.setBanned(false);
                    worldPlayerRepository.save(worldPlayer);
                    return response;
                }else {
                    throw new RuntimeException("Player not found in world: " + worldName);
                }
            } else {
                log.warn("Failed to pardon player: {}", playerName);
                return response;
            }
        } catch (Exception e) {
            log.error("Failed to pardon player: " + playerName, e);
            throw new RuntimeException("Failed to pardon player: " + playerName, e);
        }
    }

    @Transactional
    public String opPlayer(String worldName, PlayerOpDto playerDto) {
        try {
            String response = rconClientService.opPlayer(worldName, playerDto.name());
            if (response == null || (!response.contains("Made "+playerDto.name()+" a server operator") 
            && !response.contains("Player opped")) && !response.contains("Nothing changed. The player already is an operator")) {
                log.warn("Failed to op player: {}", playerDto.name());
                return response;
            }
            String opLevel = serverPropertiesService.getProperties(worldName).properties().get("op-permission-level");
            opLevel = opLevel != null ? opLevel : "4";
            if (!String.valueOf(playerDto.level()).equals(opLevel) && playerDto.level() <= 4) {
                setOpLevel(worldName, playerDto);
            }
            WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, playerDto.uuid())
                    .orElse(null);
            if (worldPlayer != null) {
                worldPlayer.setOp(true);
                if (playerDto.level() <= 4) {
                    worldPlayer.setOpLevel(playerDto.level());
                    worldPlayer.setBypassesPlayerLimit(playerDto.bypassesPlayerLimit());
                }
                worldPlayerRepository.save(worldPlayer);
                return response;
            }else {
                throw new RuntimeException("Player not found in world: " + worldName);
            }
        } catch (Exception e) {
            log.error("Failed to op player: {}", playerDto.name(), e);
            throw new RuntimeException("Failed to op player: " + playerDto.name(), e);
        }
    }

    private void setOpLevel(String worldName, PlayerOpDto playerDto) {
        String opFilePath = baseDir + FileSystems.getDefault().getSeparator() + worldName + 
        FileSystems.getDefault().getSeparator() + "ops.json";
        File opFile = new File(opFilePath);
        if (!opFile.exists()) {
            log.warn("ops.json not found for world: {}", worldName);
            return;
        }
        try {
            List<UserOpEntry> opList = objectMapper.readValue(opFile, new TypeReference<>() {});
            UserOpEntry opEntry = opList.stream()
            .filter(entry -> entry.name.equals(playerDto.name()))
            .findFirst()
            .orElse(null);
            if (opEntry != null) {
                opEntry.level = playerDto.level();
            }else {
                opList.add(new UserOpEntry(playerDto.name(), playerDto.uuid(), playerDto.level(), playerDto.bypassesPlayerLimit()));
            }
            objectMapper.writeValue(opFile, opList);
        } catch (IOException e) {
            log.error("Failed to set op level for player: {}", playerDto.name(), e);
        }
    }

    @Transactional
    public String removeOpPlayer(String worldName, PlayerOpDto playerDto) {
        try {
            String response = rconClientService.deOpPlayer(worldName, playerDto.name());
            if (response == null || !response.contains("Made "+playerDto.name() + " no longer a server operator")) {
                log.warn("Failed to remove op player: {}", playerDto.name());
                return response;
            }
            WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, playerDto.uuid())
                    .orElse(null);
            if (worldPlayer != null) {
                worldPlayer.setOp(false);
                worldPlayer.setOpLevel(0);
                worldPlayerRepository.save(worldPlayer);
                return response;
            }else {
                throw new RuntimeException("Player not found in world: " + worldName);
            }
        } catch (Exception e) {
            log.error("Failed to remove op player: {}", playerDto.name(), e);
            throw new RuntimeException("Failed to remove op player: " + playerDto.name(), e);
        }
    }

    @Transactional
    public void whitelistPlayer(String worldName, PlayerDto playerDto) {
        try {
            WorldPlayer worldPlayer = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, playerDto.uuid())
                    .orElse(null);
            if (worldPlayer != null) {
                if(playerDto.isWhitelisted()) {
                    addPlayerToWhitelist(worldName, playerDto);
                }else {
                    removePlayerFromWhitelist(worldName, playerDto);
                }
                worldPlayer.setWhitelisted(playerDto.isWhitelisted());
                worldPlayerRepository.save(worldPlayer);
            }else {
                throw new RuntimeException("Player not found in world: " + worldName);
            }
        } catch (Exception e) {
            log.error("Failed to whitelist player: {}", playerDto.name(), e);
        }
    }

    private void addPlayerToWhitelist(String worldName, PlayerDto playerDto) {
        try {
            File whitelistFile = new File(baseDir + worldName + FileSystems.getDefault().getSeparator() + "whitelist.json");
            List<Map<String, String>> whitelist;
            
            if (whitelistFile.exists()) {
                whitelist = objectMapper.readValue(whitelistFile, new TypeReference<List<Map<String, String>>>() {});
            } else {
                whitelist = new ArrayList<>();
            }
            
            boolean playerExists = whitelist.stream()
                    .anyMatch(entry -> entry.get("uuid") != null && entry.get("uuid").equals(playerDto.uuid()));
            
            if (!playerExists) {
                Map<String, String> newEntry = new HashMap<>();
                newEntry.put("uuid", playerDto.uuid());
                newEntry.put("name", playerDto.name());
                whitelist.add(newEntry);
                objectMapper.writeValue(whitelistFile, whitelist);
                log.info("Added player {} to whitelist for world {}", playerDto.name(), worldName);
            } else {
                log.info("Player {} is already in whitelist for world {}", playerDto.name(), worldName);
            }
            
        } catch (IOException e) {
            log.error("Failed to add player to whitelist file: {}", playerDto.name(), e);
            throw new RuntimeException("Failed to add player to whitelist", e);
        }
    }

    private void removePlayerFromWhitelist(String worldName, PlayerDto playerDto) {
        try {
            File whitelistFile = new File(baseDir + worldName + FileSystems.getDefault().getSeparator() + "whitelist.json");
            List<Map<String, String>> whitelist;
            if (whitelistFile.exists()) {
                whitelist = objectMapper.readValue(whitelistFile, new TypeReference<List<Map<String, String>>>() {});
            } else {
                whitelist = new ArrayList<>();
            }
            whitelist.removeIf(entry -> entry.get("uuid") != null && entry.get("uuid").equals(playerDto.uuid()));
            objectMapper.writeValue(whitelistFile, whitelist);
            log.info("Removed player {} from whitelist for world {}", playerDto.name(), worldName);
        } catch (IOException e) {
            log.error("Failed to remove player from whitelist file: {}", playerDto.name(), e);
            throw new RuntimeException("Failed to remove player from whitelist", e);
        }
    }


    @Transactional
    public void setPlayerOnline(String worldName, String username) {
        if (username != null) {
            try {
                Player player = findPlayerByUsername(username);
                if (player == null){
                    Player createdPlayer = createPlayer(findUuidFromUsercache(worldName, username), username);
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

    @Transactional
    public void updatePlayerWithUuid(String worldName, String username, String uuid) {
        if (username == null || uuid == null) {
            log.warn("Cannot update player with null username or UUID");
            return;
        }
        
        try {
            // Check if player exists by UUID
            Player existingPlayer = playerRepository.findById(uuid).orElse(null);
            
            if (existingPlayer != null) {
                // Player exists, update username if needed
                existingPlayer = updatePlayerUsernameHistory(username, existingPlayer);
                
                // Check if player is associated with this world
                WorldPlayer existingRelationship = worldPlayerRepository.findByWorldNameAndPlayerUuid(worldName, uuid).orElse(null);
                if (existingRelationship == null) {
                    // Create relationship if it doesn't exist
                    createWorldPlayer(worldName, existingPlayer, false, false, false,
                            false, 0, LocalDateTime.now());
                }
            } else {
                // Player doesn't exist, create new player with UUID
                Player newPlayer = createPlayer(uuid, username);
                createWorldPlayer(worldName, newPlayer, false, false, false,
                        false, 0, LocalDateTime.now());
            }
            
            // Evict cache to ensure fresh data
            evictPlayerCache(worldName);
            
            log.info("Updated player {} with UUID {} in world {}", username, uuid, worldName);
        } catch (Exception e) {
            log.error("Failed to update player with UUID: {}", username, e);
        }
    }

    private static class UsercacheEntry {
        public String name;
        public String uuid;
        public String expiresOn;
    }

    private static class UserOpEntry {
        public String name;
        public String uuid;
        public int level;
        public boolean bypassesPlayerLimit;

        public UserOpEntry(String name, String uuid, int level, boolean bypassesPlayerLimit) {
            this.name = name;
            this.uuid = uuid;
            this.level = level;
            this.bypassesPlayerLimit = bypassesPlayerLimit;
        }
    }
}