package com.mcap.minecraftagent.pojo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class WorldConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Basic settings
    private String worldName;
    private String serverVersion;
    private int port = 25565;
    private int minMemory = 1024;
    private int maxMemory = 2048;

    // Runtime state
    private boolean isRunning;

    // Metadata
    private LocalDateTime createdAt;
    private LocalDateTime lastStarted;
    private String lastBackup;

    // Players - using the join entity now
    @OneToMany(mappedBy = "world", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorldPlayer> worldPlayers = new ArrayList<>();

    // Helper method to add a player with world-specific attributes
    public void addPlayer(Player player, boolean isBanned, LocalDateTime lastLogin) {
        WorldPlayer worldPlayer = new WorldPlayer();
        worldPlayer.setWorld(this);
        worldPlayer.setPlayer(player);
        worldPlayer.setBanned(isBanned);
        worldPlayer.setLastLogin(lastLogin);

        worldPlayers.add(worldPlayer);
        player.getWorldPlayers().add(worldPlayer);
    }

    // Helper method to get all player entities
    public List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();
        for (WorldPlayer worldPlayer : worldPlayers) {
            players.add(worldPlayer.getPlayer());
        }
        return players;
    }
}