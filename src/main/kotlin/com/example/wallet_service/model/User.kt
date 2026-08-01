package com.example.wallet_service.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("USERS")
data class User(
    @Id
    var id: Long? = null,
    val username: String,
) {
    companion object {
        fun newUser(username: String) = User(username = username)
    }
}
