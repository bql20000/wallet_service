package com.example.wallet_service.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

@Table("WALLETS")
data class Wallet(
    @Id
    val id: Long? = null,
    val userId: Long,
    val name: String,
    val currency: String,
    val availableBalance: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun newWallet(userId: Long, name: String, currency: String): Wallet {
            val now = Instant.now()
            return Wallet(
                id = null, userId = userId, name = name, currency = currency,
                availableBalance = BigDecimal.ZERO,
                createdAt = now, updatedAt = now,
            )
        }
    }
}
