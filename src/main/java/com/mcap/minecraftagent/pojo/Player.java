package com.mcap.minecraftagent.pojo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
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
    private boolean isBanned;
    private LocalDateTime lastLogin;
}
