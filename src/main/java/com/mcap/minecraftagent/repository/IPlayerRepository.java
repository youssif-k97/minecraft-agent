package com.mcap.minecraftagent.repository;

import com.mcap.minecraftagent.pojo.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IPlayerRepository extends JpaRepository<Player, String> {
    Player findByUsername(String username);
}
