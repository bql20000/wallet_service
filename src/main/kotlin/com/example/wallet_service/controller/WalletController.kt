package com.example.wallet_service.controller

import com.example.wallet_service.BalanceResponse
import com.example.wallet_service.CreateWalletRequest
import com.example.wallet_service.CreateWalletResponse
import com.example.wallet_service.service.WalletService
import com.example.wallet_service.toBalanceResponse
import com.example.wallet_service.toCreateWalletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/wallets")
class WalletController(private val walletService: WalletService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createWallet(@RequestBody request: CreateWalletRequest): CreateWalletResponse =
        walletService.createWallet(request).toCreateWalletResponse()

    @GetMapping("/{id}/balance")
    fun getBalance(@PathVariable id: Long): BalanceResponse =
        walletService.getBalance(id).toBalanceResponse()
}
