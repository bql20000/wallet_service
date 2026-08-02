package com.example.wallet_service.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

@Table("wallet_tab")
data class Wallet(
    @Id
    val id: Long? = null,
    val userId: Long,
    val name: String,
    val currency: String,
    val availableBalance: BigDecimal,
    /** Unix timestamp in seconds (UTC) — see `created_at INT` in schema.sql. */
    val createdAt: Long,
    /** Unix timestamp in seconds (UTC) — see `updated_at INT` in schema.sql. */
    val updatedAt: Long,
) {
    companion object {
        fun newWallet(userId: Long, name: String, currency: String): Wallet {
            val now = Instant.now().epochSecond
            return Wallet(
                id = null, userId = userId, name = name, currency = currency,
                availableBalance = BigDecimal.ZERO,
                createdAt = now, updatedAt = now,
            )
        }
    }
}
