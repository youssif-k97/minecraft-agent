package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.pojo.WorldConfig;
import io.graversen.minecraft.rcon.MinecraftRcon;
import io.graversen.minecraft.rcon.RconResponse;
import io.graversen.minecraft.rcon.commands.PlayerListCommand;
import io.graversen.minecraft.rcon.commands.SayCommand;
import io.graversen.minecraft.rcon.commands.StopCommand;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCommand;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCommandBuilder;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCompositeCommand;
import io.graversen.minecraft.rcon.query.playerlist.PlayerNamesMapper;
import io.graversen.minecraft.rcon.service.ConnectOptions;
import io.graversen.minecraft.rcon.service.MinecraftRconService;
import io.graversen.minecraft.rcon.service.RconDetails;
import io.graversen.minecraft.rcon.util.Colors;
import io.graversen.minecraft.rcon.util.Selectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Service
@Slf4j
public class RconClientService {

    private final ConfigurationService configurationService;

    public RconClientService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public void sendCommand(String command) throws ExecutionException, InterruptedException {

//        switch (command) {
//            case "say":
//                sayCommand();
//                break;
//            case "tellraw":
//                tellRawCommand();
//                break;
//            case "list":
//                listCommand();
//                break;
//            default:
//                throw new IllegalArgumentException("Invalid command: " + command);
//        }

        final MinecraftRconService minecraftRconService = new MinecraftRconService(RconDetails.localhost( "password"),
                ConnectOptions.defaults());
        minecraftRconService.connectBlocking(Duration.ofSeconds(5));
        final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
// Build a TellRaw command - first half of the desired message
        final TellRawCommand tellRawCommand1 = new TellRawCommandBuilder()
                .targeting(Selectors.ALL_PLAYERS)
                .withText("It's dangerous to go alone - ")
                .withColor(Colors.GRAY)
                .italic()
                .build();


// Build another TellRaw command - other half of the message
        final TellRawCommand tellRawCommand2 = new TellRawCommandBuilder()
                .targeting(Selectors.ALL_PLAYERS)
                .withText("Take this!")
                .withColor(Colors.DARK_AQUA)
                .italic()
                .build();

        final TellRawCompositeCommand tellRawCompositeCommand = new TellRawCompositeCommand(List.of(tellRawCommand1, tellRawCommand2));
        minecraftRcon.sendAsync(tellRawCompositeCommand);
        PlayerNamesMapper list = new PlayerNamesMapper();
        List<String> names = list.apply(minecraftRcon.sendAsync(PlayerListCommand.names()).get()).getPlayerNames();
    }

    public List<String> getOnlinePlayers(String worldName){
        WorldConfig world = this.configurationService.getConfig(worldName);
        List<String> playerNames = new ArrayList<>();
        MinecraftRconService minecraftRconService = null;
        try {
            minecraftRconService = new MinecraftRconService(
                    new RconDetails("localhost", world.getPort()+10, world.getRconPassword()),
                    ConnectOptions.defaults());
            
            // Increase connection timeout and verify connection success
            boolean connected = minecraftRconService.connectBlocking(Duration.ofSeconds(10));
            if (!connected) {
                log.error("Failed to connect to RCON server");
                return playerNames;
            }

            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            PlayerNamesMapper list = new PlayerNamesMapper();
            playerNames = list.apply(minecraftRcon.sendAsync(PlayerListCommand.names()).get()).getPlayerNames();
        } catch (ExecutionException e) {
            log.error("Error while getting online players", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while getting online players", e);
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } finally {
            if (minecraftRconService != null) {
                minecraftRconService.disconnect(); // Ensure cleanup
            }
        }
        return playerNames;
    }

    public boolean stopServer(String worldName){
        WorldConfig world = this.configurationService.getConfig(worldName);
        MinecraftRconService minecraftRconService = null;
        try {
            minecraftRconService = new MinecraftRconService(
                    new RconDetails("localhost", world.getPort()+10, world.getRconPassword()),
                    ConnectOptions.defaults());

            boolean connected = minecraftRconService.connectBlocking(Duration.ofSeconds(10));
            if (!connected) {
                log.error("Failed to connect to RCON server");
                return false;
            }

            final MinecraftRcon minecraftRcon = minecraftRconService.minecraftRcon().orElseThrow(IllegalStateException::new);
            Future<RconResponse> response = minecraftRcon.sendAsync(new StopCommand());
            return response.get().getResponseString().contains("Stopping server");
        } catch (RuntimeException e) {
            log.error("Unexpected error during RCON operation", e);
        } catch (ExecutionException e) {
            log.error("Error while stopping server", e);
        } catch (InterruptedException e) {
            log.error("Interrupted while stopping server", e);
        } finally {
            if (minecraftRconService != null)
                minecraftRconService.disconnect();
        }
        return false;
    }
}
