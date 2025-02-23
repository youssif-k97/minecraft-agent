package com.mcap.minecraftagent.service;

import io.graversen.minecraft.rcon.MinecraftRcon;
import io.graversen.minecraft.rcon.RconResponse;
import io.graversen.minecraft.rcon.commands.PlayerListCommand;
import io.graversen.minecraft.rcon.commands.SayCommand;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCommand;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCommandBuilder;
import io.graversen.minecraft.rcon.commands.tellraw.TellRawCompositeCommand;
import io.graversen.minecraft.rcon.query.playerlist.PlayerNamesMapper;
import io.graversen.minecraft.rcon.service.ConnectOptions;
import io.graversen.minecraft.rcon.service.MinecraftRconService;
import io.graversen.minecraft.rcon.service.RconDetails;
import io.graversen.minecraft.rcon.util.Colors;
import io.graversen.minecraft.rcon.util.Selectors;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class RconClientService {

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
}
