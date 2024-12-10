package com.mcap.minecraftagent.dto;

public record Player(
        String username,
        boolean isOnline,
        boolean isWhitelisted,
        boolean isBlacklisted
) {}
