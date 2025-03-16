package com.mcap.minecraftagent.dto;

public record BanKickPlayerDto(
        String uuid,
        String name,
        String reason
) {}
