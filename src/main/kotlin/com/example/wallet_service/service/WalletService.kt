package com.example.wallet_service.service

import org.slf4j.LoggerFactory

import com.example.wallet_service.CreateWalletRequest
import com.example.wallet_service.InvalidCurrencyException
import com.example.wallet_service.WalletNotFoundException
import com.example.wallet_service.model.User
import com.example.wallet_service.model.Wallet
import com.example.wallet_service.repository.UserRepository
import com.example.wallet_service.repository.WalletRepository
import org.springframework.stereotype.Service

@Service
class WalletService(
    private val walletRepository: WalletRepository,
    private val userRepository: UserRepository,
) {
    fun createWallet(request: CreateWalletRequest): Wallet {
        if (request.currency !in SUPPORTED_CURRENCIES) {
            throw InvalidCurrencyException(request.currency)
        }
        val user = userRepository.findByUsername(request.username)
            ?: userRepository.save(User.newUser(request.username))

        val wallet = walletRepository.save(Wallet.newWallet(userId = user.id!!, name = request.name, currency = request.currency))
        log.info("create_wallet|username={},user_id={},wallet_name={},wallet_id={}", user.username, user.id, wallet.name, wallet.id)
        return wallet
    }

    fun getBalance(walletId: Long): Wallet =
        walletRepository.findById(walletId).orElseThrow { WalletNotFoundException(walletId) }

    companion object {
        private val SUPPORTED_CURRENCIES = setOf("USD")
        private val log = LoggerFactory.getLogger(WalletService::class.java)
    }
}
