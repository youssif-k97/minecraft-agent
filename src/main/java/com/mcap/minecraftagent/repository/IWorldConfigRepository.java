package com.mcap.minecraftagent.repository;

import com.mcap.minecraftagent.pojo.WorldConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IWorldConfigRepository extends JpaRepository<WorldConfig, Long> {
    Optional<WorldConfig> findByWorldName(String worldName);
    void deleteByWorldName(String worldName);
}
