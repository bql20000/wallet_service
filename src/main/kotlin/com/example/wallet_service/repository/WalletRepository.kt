package com.example.wallet_service.repository

import com.example.wallet_service.model.Wallet
import org.springframework.data.repository.CrudRepository

interface WalletRepository : CrudRepository<Wallet, Long>
