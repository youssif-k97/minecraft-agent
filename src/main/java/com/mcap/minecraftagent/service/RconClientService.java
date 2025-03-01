package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.pojo.WorldConfig;
import io.graversen.minecraft.rcon.MinecraftRcon;
import io.graversen.minecraft.rcon.RconResponse;
import io.graversen.minecraft.rcon.commands.*;
import io.graversen.minecraft.rcon.query.playerlist.PlayerNamesMapper;
import io.graversen.minecraft.rcon.query.playerlist.PlayerUuidsMapper;
import io.graversen.minecraft.rcon.service.ConnectOptions;
import io.graversen.minecraft.rcon.service.MinecraftRconService;
import io.graversen.minecraft.rcon.service.RconDetails;
import io.graversen.minecraft.rcon.util.Target;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Service
@Slf4j
public class RconClientService {

    private final ConfigurationService configurationService;
    private final ConcurrentHashMap<String, MinecraftRconService> rconServiceMap = new ConcurrentHashMap<>();

    public RconClientService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public void sendCommand(String command) {

        MinecraftRconService minecraftRconService = rconServiceMap.get("worldName");
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService("worldName"));
        }
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            minecraftRcon.sendAsync(new SayCommand(command)).get();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while sending command", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while sending command", e);
        }
    }

    public MinecraftRconService addRconService(String worldName) {
        WorldConfig world = this.configurationService.getConfig(worldName);
        MinecraftRconService minecraftRconService;
        try {
            minecraftRconService = new MinecraftRconService(
                    new RconDetails("localhost", world.getPort()+10, world.getRconPassword()),
                    ConnectOptions.defaults());

            boolean connected = minecraftRconService.connectBlocking(Duration.ofSeconds(10));
            if (!connected) {
                log.error("Failed to connect to RCON server");
                return null;
            }
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
            return null;
        }
        rconServiceMap.put(worldName, minecraftRconService);
        return minecraftRconService;
    }

    public void removeRconService(String worldName) {
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        if (minecraftRconService != null && minecraftRconService.isConnected()) {
            minecraftRconService.disconnect();
            rconServiceMap.remove(worldName);
        }
    }

    public Map<String, String> getOnlinePlayers(String worldName){
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        List<String> playerNames = new ArrayList<>();
        List<String> playerUuids = new ArrayList<>();
        Map<String, String> playerMap = new HashMap<>();
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService(worldName));
        }
        try {

            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            PlayerNamesMapper namesMapper = new PlayerNamesMapper();
            PlayerUuidsMapper uuidsMapper = new PlayerUuidsMapper();
            RconResponse rconResponse = minecraftRcon.sendAsync(PlayerListCommand.uuids()).get();
            playerNames = namesMapper.apply(rconResponse).getPlayerNames();
            playerUuids = uuidsMapper.apply(rconResponse).getPlayerUuids();
            for (int i = 0; i < playerNames.size(); i++) {
                playerMap.put(playerNames.get(i), playerUuids.get(i));
            }
        } catch (ExecutionException e) {
            log.error("Error while getting online players", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while getting online players", e);
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        }
        return playerMap;
    }

    public boolean banPlayer(String worldName, String playerName, String reason){
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService(worldName));
        }
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new BanCommand(Target.player(playerName), reason));
            return response.get().getResponseString().contains("Banned "+playerName);
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while banning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while banning player", e);
        }
        return false;
    }

    public boolean kickPlayer(String worldName, String playerName, String reason){
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService(worldName));
        }
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new KickCommand(Target.player(playerName), reason));
            return response.get().getResponseString().contains("Kicked "+playerName);
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while banning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while banning player", e);
        }
        return false;
    }

    public boolean stopServer(String worldName){
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService(worldName));
        }
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new StopCommand());
            return response.get().getResponseString().contains("Stopping the server");
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while stopping server", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while stopping server", e);
        }
        return false;
    }

    @PreDestroy
    public void destroy() {
        rconServiceMap.forEach((worldName, minecraftRconService) -> {
            if (minecraftRconService.isConnected()) {
                minecraftRconService.disconnect();
            }
        });
    }
}
