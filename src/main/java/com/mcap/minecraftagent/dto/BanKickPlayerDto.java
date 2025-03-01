package com.mcap.minecraftagent.dto;

public record BanKickPlayerDto(
        String uuid,
        String name,
        String lastLogin,
        boolean isBanned,
        boolean isOp,
        boolean bypassesPlayerLimit,
        int opLevel,
        boolean isWhitelisted,
        boolean isOnline,
        String reason
) {}
