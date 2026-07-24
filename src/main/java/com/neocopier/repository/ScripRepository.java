package com.neocopier.repository;

import com.neocopier.model.Scrip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScripRepository extends JpaRepository<Scrip, String> {
    List<Scrip> findByExchange(String exchange);
    long countByExchange(String exchange);
    
    @Modifying
    @Query("DELETE FROM Scrip s WHERE s.exchange = :exchange")
    int deleteByExchange(@Param("exchange") String exchange);

    Optional<Scrip> findByScriptToken(String scriptToken);
    List<Scrip> findByScriptTokenIn(List<String> scriptTokens);
}
