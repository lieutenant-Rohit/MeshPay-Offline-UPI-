package com.offline.payment.repository;

import com.offline.payment.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET balance = balance - :amount WHERE vpa = :vpa AND balance >= :amount",
           nativeQuery = true)
    int atomicDebit(@Param("vpa") String vpa, @Param("amount") BigDecimal amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET balance = balance + :amount WHERE vpa = :vpa",
           nativeQuery = true)
    int atomicCredit(@Param("vpa") String vpa, @Param("amount") BigDecimal amount);
}
