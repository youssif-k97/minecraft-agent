package com.mcap.minecraftagent.pojo;

import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class Player {
    @Id
    private String uuid;
    private String username;

    @ElementCollection
    @CollectionTable(name = "player_prev_usernames", joinColumns = @JoinColumn(name = "player_id"))
    @Column(name = "prev_username")
    private List<String> prevUsernames;

    @OneToMany(mappedBy = "player", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorldPlayer> worldPlayers = new ArrayList<>();
}