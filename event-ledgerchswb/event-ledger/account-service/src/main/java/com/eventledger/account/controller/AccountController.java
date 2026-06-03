package com.eventledger.account.controller;

import com.eventledger.account.model.ApplyTransactionRequest;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<Map<String, Object>> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody ApplyTransactionRequest request) {
        Map<String, Object> result = accountService.applyTransaction(accountId, request);
        String status = (String) result.get("status");
        return "ALREADY_APPLIED".equals(status)
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(201).body(result);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(accountService.getBalance(accountId));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccountDetails(@PathVariable String accountId) {
        return ResponseEntity.ok(accountService.getAccountDetails(accountId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "account-service");
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
