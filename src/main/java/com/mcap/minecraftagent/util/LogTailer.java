package com.mcap.minecraftagent.util;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

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

    public void start() {
        // Reset the future before starting
        serverReadyFuture = new CompletableFuture<>();
        executor.submit(this::tailLog);
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void tailLog() {
        File file = logFile.toFile();

        try {
            if (!file.exists()) {
                log.warn("Log file does not exist yet for world {}: {}", worldName, logFile);
                // Create parent directories if needed
                file.getParentFile().mkdirs();
                // Wait for the file to be created
                waitForFile(file);
            }

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                // Start by sending the entire file content
                long fileLength = file.length();
                if (fileLength > 0) {
                    log.info("Streaming entire log file for world {}", worldName);
                    streamEntireFile(raf);
                }

                // Set file pointer to the end after streaming the entire file
                long filePointer = file.length();

                // Start watching for changes
                try (WatchService watchService = logFile.getParent().getFileSystem().newWatchService()) {
                    logFile.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                    while (running) {
                        // Check for file changes
                        WatchKey key = watchService.poll(100, TimeUnit.MILLISECONDS);
                        if (key != null) {
                            for (WatchEvent<?> event : key.pollEvents()) {
                                if (event.context().toString().equals(logFile.getFileName().toString())) {
                                    // File changed, read new content
                                    long length = file.length();
                                    if (length > filePointer) {
                                        raf.seek(filePointer);
                                        String line;
                                        while ((filePointer < length) && (line = raf.readLine()) != null) {
                                            String logLine = new String(line.getBytes("ISO-8859-1"), StandardCharsets.UTF_8);
                                            processLogLine(logLine);
                                            filePointer = raf.getFilePointer();
                                        }
                                    } else if (length < filePointer) {
                                        // File was truncated or rotated, start from the beginning
                                        log.info("Log file for world {} was rotated or truncated, restarting from beginning", worldName);
                                        filePointer = 0;
                                        streamEntireFile(raf);
                                        filePointer = raf.getFilePointer();
                                    }
                                }
                            }
                            key.reset();
                        }

                        // Check if file still exists (in case it was deleted)
                        if (!file.exists()) {
                            log.warn("Log file for world {} was deleted, waiting for recreation", worldName);
                            waitForFile(file);
                            // If file was recreated, reopen it
                            raf.close();
                            RandomAccessFile newRaf = new RandomAccessFile(file, "r");
                            filePointer = 0;
                            streamEntireFile(newRaf);
                            filePointer = newRaf.getFilePointer();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error tailing log file for world {}: {}", worldName, e.getMessage(), e);
            if (running) {
                // Attempt to restart the log tailer after a brief delay
                try {
                    Thread.sleep(5000);
                    tailLog(); // Recursive call to restart monitoring
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void waitForFile(File file) throws InterruptedException {
        int attempts = 0;
        while (!file.exists() && running && attempts < 60) { // Wait up to 1 minute
            Thread.sleep(1000);
            attempts++;
        }
        if (!file.exists()) {
            throw new RuntimeException("Log file was not created after waiting: " + file.getAbsolutePath());
        }
    }

    private void streamEntireFile(RandomAccessFile raf) throws Exception {
        raf.seek(0);
        String line;
        while ((line = raf.readLine()) != null) {
            String logLine = new String(line.getBytes("ISO-8859-1"), StandardCharsets.UTF_8);
            // Send to log handler but don't process events for historical logs
            logHandler.broadcastLog(worldName, logLine);
        }
    }

    private void processLogLine(String logLine) {
        // Broadcast the log line first
        logHandler.broadcastLog(worldName, logLine);

        // Then process for specific events
        try {
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