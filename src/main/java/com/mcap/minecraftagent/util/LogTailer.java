package com.mcap.minecraftagent.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mcap.minecraftagent.config.MinecraftLogHandler;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.service.ConfigurationService;
import com.mcap.minecraftagent.service.PlayerManagementService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogTailer {
    private final String worldName;
    private final Path logFile;
    private final MinecraftLogHandler logHandler;
    private final PlayerManagementService playerManagementService;
    private final ConfigurationService configService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;
    private final AtomicLong lastPosition = new AtomicLong(0);
    private final long POLLING_INTERVAL_MS = 500; // Check every 500ms

    @Getter
    private CompletableFuture<Void> serverReadyFuture = new CompletableFuture<>();

    // Pattern for player join detection
    private static final Pattern PLAYER_JOIN_PATTERN = Pattern.compile("(\\S+) joined the game");

    // Pattern for server startup completion
    private static final Pattern SERVER_READY_PATTERN = Pattern.compile("Done \\([^)]*\\)! For help, type \"help\"");

    // Pattern for server stopping
    private static final Pattern SERVER_STOPPING_PATTERN = Pattern.compile("Stopping server");

    // Pattern for server errors
    private static final Pattern SERVER_ERROR_PATTERN = Pattern.compile("ERROR");

    // Pattern for user authenticator
    private static final Pattern USER_AUTHENTICATOR_PATTERN = Pattern.compile("\\[User Authenticator #\\d+/INFO\\]: UUID of player (\\S+) is ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    public LogTailer(
            String worldName,
            String baseDir,
            MinecraftLogHandler logHandler,
            PlayerManagementService playerManagementService,
            ConfigurationService configService) {
        this.worldName = worldName;
        this.logFile = Paths.get(baseDir, worldName, "logs", "latest.log");
        this.logHandler = logHandler;
        this.playerManagementService = playerManagementService;
        this.configService = configService;
    }

    /**
     * Starts the log monitoring process.
     */
    public void start() {
        if (running) {
            log.warn("LogTailer for world {} is already running", worldName);
            return;
        }

        // Reset the future and position
        serverReadyFuture = new CompletableFuture<>();
        lastPosition.set(0);
        running = true;

        log.info("Starting log monitoring for world {}", worldName);

        // Create parent directories if needed
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            log.error("Failed to create log directory for world {}: {}", worldName, e.getMessage(), e);
        }

        // Initial check for existing content
        scheduler.schedule(this::initialCheck, 0, TimeUnit.MILLISECONDS);

        // Schedule regular polling
        scheduler.scheduleAtFixedRate(this::pollForChanges, POLLING_INTERVAL_MS, POLLING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the log monitoring process.
     */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        log.info("Stopped log monitoring for world {}", worldName);
    }

    /**
     * Performs the initial check of the log file.
     */
    private void initialCheck() {
        try {
            if (Files.exists(logFile)) {
                log.info("Processing existing log content for world {}", worldName);
                // Stream existing content to the log handler
                Files.lines(logFile, StandardCharsets.UTF_8)
                        .forEach(line -> logHandler.broadcastLog(worldName, line));

                // Update position to end of file
                lastPosition.set(Files.size(logFile));
            } else {
                log.info("Log file does not exist yet for world {}", worldName);
                lastPosition.set(0);
            }
        } catch (IOException e) {
            log.error("Error during initial log check for world {}: {}", worldName, e.getMessage(), e);
        }
    }

    /**
     * Polls the log file for changes.
     */
    private void pollForChanges() {
        if (!running) return;

        try {
            // Check if file exists
            if (!Files.exists(logFile)) {
                if (lastPosition.get() > 0) {
                    log.info("Log file for world {} was deleted, resetting position", worldName);
                    lastPosition.set(0);
                }
                return;
            }

            long currentSize = Files.size(logFile);

            // File was truncated or rotated
            if (currentSize < lastPosition.get()) {
                log.info("Log file for world {} was truncated or rotated, processing from beginning", worldName);
                lastPosition.set(0);
            }

            // There's new content to read
            if (currentSize > lastPosition.get()) {
                try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
                    lines.skip(lastPosition.get() > 0 ? getLineCount(lastPosition.get()) : 0)
                            .forEach(this::processLogLine);
                }
                lastPosition.set(currentSize);
            }
        } catch (IOException e) {
            // Log but don't throw - we want to keep trying
            log.error("Error polling log file for world {}: {}", worldName, e.getMessage(), e);
        }
    }

    /**
     * Helper method to get the line count up to a certain position.
     * This is an approximate method and assumes line endings are consistent.
     */
    private long getLineCount(long position) throws IOException {
        try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            long count = 0;
            long bytesRead = 0;

            for (var line : (Iterable<String>) lines::iterator) {
                bytesRead += line.getBytes(StandardCharsets.UTF_8).length + System.lineSeparator().length();
                count++;
                if (bytesRead >= position) break;
            }

            return count;
        }
    }

    /**
     * Processes a single log line for events.
     */
    private void processLogLine(String logLine) {
        if (!running) return;

        // Broadcast the log line first
        logHandler.broadcastLog(worldName, logLine);

        // Then process for specific events
        try {
            // Check for user authenticator log line (contains UUID and username)
            Matcher userAuthMatcher = USER_AUTHENTICATOR_PATTERN.matcher(logLine);
            if (userAuthMatcher.find()) {
                String playerName = userAuthMatcher.group(1);
                String playerUuid = userAuthMatcher.group(2);
                log.info("Detected player authentication: {} with UUID {} in world {}", playerName, playerUuid, worldName);
                playerManagementService.updatePlayerWithUuid(worldName, playerName, playerUuid);
            }
            
            // Check for player join
            Matcher playerJoinMatcher = PLAYER_JOIN_PATTERN.matcher(logLine);
            if (playerJoinMatcher.find()) {
                String playerName = playerJoinMatcher.group(1);
                log.info("Player {} joined world {}", playerName, worldName);
                playerManagementService.setPlayerOnline(worldName, playerName);
            }

            // Check for server ready
            if (SERVER_READY_PATTERN.matcher(logLine).find()) {
                log.info("Server for world {} is fully initialized", worldName);
                configService.updateServerStatus(worldName, true);

                // Update last started time
                WorldConfig worldConfig = configService.getConfig(worldName);
                if (worldConfig != null) {
                    worldConfig.setLastStarted(LocalDateTime.now());
                    configService.saveConfig(worldConfig);
                }

                // Complete the future to signal that the server is ready
                if (!serverReadyFuture.isDone()) {
                    serverReadyFuture.complete(null);
                }
            }

            // Check for server stopping
            if (SERVER_STOPPING_PATTERN.matcher(logLine).find()) {
                log.info("Server for world {} is stopping", worldName);
                configService.updateServerStatus(worldName, false);
            }

            // Check for server errors
            if (SERVER_ERROR_PATTERN.matcher(logLine).find()) {
                log.warn("Detected error in server logs for world {}: {}", worldName, logLine);
                if (!serverReadyFuture.isDone()) {
                    serverReadyFuture.completeExceptionally(
                            new RuntimeException("Server error detected: " + logLine));
                }
            }
        } catch (Exception e) {
            log.error("Error processing log line for world {}: {}", worldName, e.getMessage(), e);
        }
    }
}