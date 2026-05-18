package com.offline.payment.repository;

import com.offline.payment.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository; // <-- Add this import
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> { // <-- Extend JpaRepository

    // Spring will automatically generate the SQL query for this!
    boolean existsByPacketHash(String packetHash);
}