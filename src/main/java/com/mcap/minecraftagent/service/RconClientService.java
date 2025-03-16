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

    public MinecraftRconService createOrGetRconService(String worldName) {
        MinecraftRconService minecraftRconService = rconServiceMap.get(worldName);
        if (minecraftRconService == null) {
            minecraftRconService = Objects.requireNonNull(addRconService(worldName));
        }
        if (!minecraftRconService.isConnected()) {
            rconServiceMap.remove(worldName);
            throw new IllegalStateException("RCON service is not connected");
        }
        return minecraftRconService;
    }

    private MinecraftRconService addRconService(String worldName) {
        WorldConfig world = this.configurationService.getConfig(worldName);
        if (!world.isRunning())
            return null;
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
        if (minecraftRconService.isConnected())
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

    public String sendRawRconCommand(String worldName, String command) {
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {

            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> rconResponse = minecraftRcon.sendAsync(() -> command);
            return rconResponse.get().getResponseString();
        } catch (ExecutionException e) {
            log.error("Error while sending raw RCON command", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while sending raw RCON command", e);
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        }
        return "Error while sending raw RCON command";
    }

    public Map<String, String> getOnlinePlayers(String worldName){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        List<String> playerNames = new ArrayList<>();
        List<String> playerUuids = new ArrayList<>();
        Map<String, String> playerMap = new HashMap<>();
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

    public String banPlayer(String worldName, String playerName, String reason){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new BanCommand(Target.player(playerName), reason));
            return response.get().getResponseString();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while banning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while banning player", e);
        }
        return null;
    }

    public String pardonPlayer(String worldName, String playerName){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new PardonCommand(Target.player(playerName)));
            return response.get().getResponseString();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while pardoning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while pardoning player", e);
        }
        return null;
    }

    public String kickPlayer(String worldName, String playerName, String reason){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new KickCommand(Target.player(playerName), reason));
            return response.get().getResponseString();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while banning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while banning player", e);
        }
        return null;
    }

    public String opPlayer(String worldName, String playerName){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new OpCommand(Target.player(playerName)));
            return response.get().getResponseString();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while banning player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while banning player", e);
        }
        return null;
    }

    public String deOpPlayer(String worldName, String playerName){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
        try {
            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new DeOpCommand(Target.player(playerName)));
            return response.get().getResponseString();
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while removing op player", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while removing op player", e);
        }
        return null;
    }


    public boolean stopServer(String worldName){
        MinecraftRconService minecraftRconService =createOrGetRconService(worldName);
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
