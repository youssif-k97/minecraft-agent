package com.mcap.minecraftagent.repository;

import com.mcap.minecraftagent.pojo.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IPlayerRepository extends JpaRepository<Player, String> {
    Player findByUsername(String username);

    @Query("SELECT p FROM Player p JOIN p.prevUsernames pu WHERE pu = :username")
    Player findByPrevUsername(@Param("username") String username);
}
