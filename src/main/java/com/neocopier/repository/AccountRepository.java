package com.neocopier.repository;

import com.neocopier.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    List<Account> findByRole(String role);
    List<Account> findByStatus(String status);
    List<Account> findByRoleAndStatus(String role, String status);
}
