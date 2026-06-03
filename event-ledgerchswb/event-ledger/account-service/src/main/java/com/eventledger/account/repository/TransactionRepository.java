package com.eventledger.account.repository;

import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByEventId(String eventId);

    List<Transaction> findByAccountIdOrderByEventTimestampAsc(String accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.accountId = :accountId AND t.type = :type")
    BigDecimal sumAmountByAccountIdAndType(@Param("accountId") String accountId,
                                           @Param("type") TransactionType type);
}
