package com.eventledger.account.service;

import com.eventledger.account.model.*;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    public AccountService(TransactionRepository transactionRepository, MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Map<String, Object> applyTransaction(String accountId, ApplyTransactionRequest req) {
        Optional<Transaction> existing = transactionRepository.findByEventId(req.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate transaction detected: eventId={}", req.getEventId());
            return buildResponse(existing.get(), "ALREADY_APPLIED");
        }

        Transaction tx = new Transaction(
                req.getEventId(), accountId, req.getType(),
                req.getAmount(), req.getCurrency(), req.getEventTimestamp()
        );
        transactionRepository.save(tx);

        meterRegistry.counter("account_transactions_applied_total").increment();
        if (req.getType() == TransactionType.CREDIT) {
            meterRegistry.counter("account_transactions_credit_total").increment();
        } else {
            meterRegistry.counter("account_transactions_debit_total").increment();
        }

        log.info("Transaction applied: eventId={} accountId={} type={} amount={}",
                req.getEventId(), accountId, req.getType(), req.getAmount());
        return buildResponse(tx, "APPLIED");
    }

    public Map<String, Object> getBalance(String accountId) {
        BigDecimal credits = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT);
        BigDecimal debits  = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT);
        BigDecimal balance = credits.subtract(debits);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("balance", balance);
        response.put("currency", "USD");
        return response;
    }

    public Map<String, Object> getAccountDetails(String accountId) {
        List<Transaction> txs = transactionRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal credits = txs.stream().filter(t -> t.getType() == TransactionType.CREDIT)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debits = txs.stream().filter(t -> t.getType() == TransactionType.DEBIT)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("balance", credits.subtract(debits));
        response.put("transactionCount", txs.size());
        response.put("transactions", txs.stream().map(this::txToMap).toList());
        return response;
    }

    private Map<String, Object> buildResponse(Transaction tx, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("eventId", tx.getEventId());
        r.put("accountId", tx.getAccountId());
        r.put("type", tx.getType());
        r.put("amount", tx.getAmount());
        r.put("currency", tx.getCurrency());
        r.put("eventTimestamp", tx.getEventTimestamp());
        return r;
    }

    private Map<String, Object> txToMap(Transaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", tx.getEventId());
        m.put("type", tx.getType());
        m.put("amount", tx.getAmount());
        m.put("currency", tx.getCurrency());
        m.put("eventTimestamp", tx.getEventTimestamp());
        return m;
    }
}
