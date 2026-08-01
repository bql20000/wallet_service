package com.example.wallet_service

import com.example.wallet_service.model.Wallet
import java.math.BigDecimal
import java.time.Instant

data class CreateWalletRequest(val username: String, val name: String, val currency: String)

data class CreateWalletResponse(
    val id: Long,
    val userId: Long,
    val name: String,
    val currency: String,
    val availableBalance: BigDecimal,
    val createdAt: Instant,
)

data class BalanceResponse(val walletId: Long, val availableBalance: BigDecimal, val currency: String)

fun Wallet.toCreateWalletResponse() = CreateWalletResponse(id!!, userId, name, currency, availableBalance, createdAt)
fun Wallet.toBalanceResponse() = BalanceResponse(id!!, availableBalance, currency)
