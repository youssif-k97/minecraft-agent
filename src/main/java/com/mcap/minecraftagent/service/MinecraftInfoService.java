package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.MinecraftWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.mcap.minecraftagent.service.MinecraftServerService.CommandResult;

@Service
public class MinecraftInfoService {
    private static final Logger logger = LoggerFactory.getLogger(MinecraftInfoService.class);
    private final MinecraftServerService serverService;

    public MinecraftInfoService(MinecraftServerService serverService) {
        this.serverService = serverService;
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("version (\\d+\\.\\d+\\.\\d+).*?(\\d+) of (\\d+) users");
    private static final Pattern PLAYERS_PATTERN =
            Pattern.compile("Players: ([^.]+)\\.");

    @Cacheable(value = "minecraftWorlds", unless = "#result.isEmpty()")
    public List<MinecraftWorld> getAllWorlds() {
        try {
            CommandResult statusResult = serverService.executeCommand("", "status");
            return parseStatusOutput(statusResult.output());
        } catch (Exception e) {
            logger.error("Error getting Minecraft worlds", e);
            return Collections.emptyList();
        }
    }

    private List<MinecraftWorld> parseStatusOutput(List<String> statusOutput) {
        List<MinecraftWorld> worlds = new ArrayList<>();
        MinecraftWorld currentWorld = null;

        for (String line : statusOutput) {
            line = line.trim();

            // Skip empty lines and the header
            if (line.isEmpty() || line.equals("Minecraft Server Status:")) {
                continue;
            }

            // New world entry
            if (line.contains("running version") || line.contains("not running")) {
                // Save previous world if exists
                if (currentWorld != null) {
                    worlds.add(currentWorld);
                }

                String[] parts = line.split(":", 2);
                String worldId = parts[0].trim();
                String status = parts[1].trim();

                if (status.startsWith("not running")) {
                    currentWorld = new MinecraftWorld(
                            worldId,
                            worldId,
                            false,
                            Collections.emptyList(),
                            new HashMap<>()
                    );
                } else {
                    // Parse running world info
                    Matcher versionMatcher = VERSION_PATTERN.matcher(status);
                    if (versionMatcher.find()) {
                        Map<String, String> properties = getWorldProperties(worldId);
                        properties.put("version", versionMatcher.group(1));
                        properties.put("maxPlayers", versionMatcher.group(3));

                        currentWorld = new MinecraftWorld(
                                worldId,
                                worldId,
                                true,
                                new ArrayList<>(),
                                properties
                        );
                    }
                }
            }
            // Additional info for current world
            else if (currentWorld != null) {
                String trimmedLine = line.trim();

                Matcher playersMatcher = PLAYERS_PATTERN.matcher(trimmedLine);
                if (playersMatcher.find()) {
                    String playersStr = playersMatcher.group(1);
                    List<String> players = Arrays.stream(playersStr.split(", "))
                            .map(String::trim)
                            .collect(Collectors.toList());
                    currentWorld = new MinecraftWorld(
                            currentWorld.id(),
                            currentWorld.name(),
                            currentWorld.isActive(),
                            players,
                            currentWorld.properties()
                    );
                }
                else if (trimmedLine.startsWith("Port:")) {
                    currentWorld.properties().put("port",
                            trimmedLine.split(":")[1].trim().replace(".", ""));
                }
                else if (trimmedLine.startsWith("Memory used:")) {
                    currentWorld.properties().put("memoryUsed",
                            trimmedLine.split(":")[1].trim().replace(".", ""));
                }
                else if (trimmedLine.startsWith("Process ID:")) {
                    currentWorld.properties().put("pid",
                            trimmedLine.split(":")[1].trim().replace(".", ""));
                }
            }
        }

        // Add the last world
        if (currentWorld != null) {
            worlds.add(currentWorld);
        }

        return worlds;
    }

    private Set<String> getWhitelistedPlayers(String worldId) {
        try {
            Path whitelistPath = Paths.get("/opt/mscs/worlds", worldId,"/whitelist.json");
            return Files.readAllLines(whitelistPath).stream()
                    .filter(line -> line.contains("name"))
                    .map(line -> line.split("\"")[3])
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.error("Error reading whitelist", e);
            return Collections.emptySet();
        }
    }

    private Set<String> getBlacklistedPlayers(String worldId) {
        try {
            Path banlistPath = Paths.get("/opt/mscs/worlds", worldId,"/banned-players.json");
            return Files.readAllLines(banlistPath).stream()
                    .filter(line -> line.contains("name"))
                    .map(line -> line.split("\"")[3])
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.error("Error reading banlist", e);
            return Collections.emptySet();
        }
    }

    private Map<String, String> getWorldProperties(String worldId) {
        try {
            Path propertiesPath = Paths.get("/opt/mscs/worlds", worldId,"/server.properties");
            Properties props = new Properties();
            props.load(Files.newBufferedReader(propertiesPath));

            return props.stringPropertyNames().stream()
                    .collect(Collectors.toMap(
                            key -> key,
                            props::getProperty
                    ));
        } catch (Exception e) {
            logger.error("Error reading properties for world: " + worldId, e);
            return new HashMap<>();
        }
    }

    @Scheduled(fixedRate = 60000)
    @CacheEvict(value = {"minecraftWorlds", "playersList"}, allEntries = true)
    public void evictCache() {
    }
}
