package com.mcap.minecraftagent.pojo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "world_players")
public class WorldPlayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "world_id")
    private WorldConfig world;

    @ManyToOne
    @JoinColumn(name = "player_id")
    private Player player;

    private boolean isBanned;
    private LocalDateTime lastLogin;
    private boolean isWhitelisted;
    private boolean isOp;
    private boolean bypassesPlayerLimit;
    private int opLevel;
}