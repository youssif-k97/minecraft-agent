package com.mcap.minecraftagent.dto;

public record PlayerDto(
        String uuid,
        String name,
        String lastLogin,
        boolean isBanned,
        boolean isOp,
        boolean bypassesPlayerLimit,
        int opLevel,
        boolean isWhitelisted,
        boolean isOnline
) {}
