package com.neocopier.repository;

import com.neocopier.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(String status);
    List<Order> findByAccountId(String accountId);
    List<Order> findByMasterOrderId(String masterOrderId);
    List<Order> findByMasterOrderIdAndStatus(String masterOrderId, String status);
}
