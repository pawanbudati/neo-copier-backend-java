package com.neocopier.repository;

import com.neocopier.model.OcoPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OcoPairRepository extends JpaRepository<OcoPair, String> {
    List<OcoPair> findByStatus(String status);
    List<OcoPair> findByMasterOcoIdAndStatus(String masterOcoId, String status);
    List<OcoPair> findByAccountId(String accountId);
}
