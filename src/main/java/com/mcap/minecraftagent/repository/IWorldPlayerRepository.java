package com.mcap.minecraftagent.repository;

import com.mcap.minecraftagent.pojo.WorldPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IWorldPlayerRepository extends JpaRepository<WorldPlayer, Long> {

    @Query("SELECT wp FROM WorldPlayer wp WHERE wp.world.worldName = :worldName AND wp.player.uuid = :playerUuid")
    Optional<WorldPlayer> findByWorldNameAndPlayerUuid(@Param("worldName") String worldName, @Param("playerUuid") String playerUuid);

    @Query("SELECT wp FROM WorldPlayer wp WHERE wp.world.worldName = :worldName AND wp.player.username = :playerUsername")
    Optional<WorldPlayer> findByWorldNameAndPlayerUsername(@Param("worldName") String worldName, @Param("playerUsername") String playerUsername);

    @Query("SELECT wp FROM WorldPlayer wp WHERE wp.player.uuid = :playerUuid")
    List<WorldPlayer> findAllByPlayerUuid(@Param("playerUuid") String playerUuid);

    @Query("SELECT wp FROM WorldPlayer wp WHERE wp.world.worldName = :worldName")
    List<WorldPlayer> findAllByWorldName(@Param("worldName") String worldName);
}