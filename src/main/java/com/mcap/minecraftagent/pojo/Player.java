package com.mcap.minecraftagent.pojo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
@Data
public class Player {
    private String uuid;
    private String username;
    private List<String> prevUsernames;
    private boolean isBanned;
    private LocalDateTime lastLogin;
}
