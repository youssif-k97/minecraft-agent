package com.mcap.minecraftagent.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    // Players
    @ManyToMany
    @JoinTable(
            name = "world_players",
            joinColumns = @JoinColumn(name = "world_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id")
    )
    private List<Player> players = new ArrayList<>();

    // Helper method to add a player
    public void addPlayer(Player player) {
        if (players == null) {
            players = new ArrayList<>();
        }
        if (!players.contains(player)) {
            players.add(player);
        }
    }
}
