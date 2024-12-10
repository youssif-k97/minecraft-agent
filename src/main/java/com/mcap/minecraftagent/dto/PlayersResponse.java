package com.mcap.minecraftagent.dto;

import java.util.List;

public record PlayersResponse(
        List<Player> players
) {}
