package com.example.wallet_service

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

class WalletNotFoundException(walletId: Long) : RuntimeException("Wallet not found: $walletId")

class InvalidCurrencyException(currency: String) : RuntimeException("Unsupported currency: $currency")

data class ErrorResponse(val errorCode: String, val message: String, val timestamp: Instant = Instant.now())

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException::class)
    fun handleWalletNotFound(ex: WalletNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(errorCode = "WALLET_NOT_FOUND", message = ex.message ?: "Wallet not found"))

    @ExceptionHandler(InvalidCurrencyException::class)
    fun handleInvalidCurrency(ex: InvalidCurrencyException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse(errorCode = "INVALID_CURRENCY", message = ex.message ?: "Invalid currency"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.internalServerError()
            .body(ErrorResponse(errorCode = "INTERNAL_ERROR", message = ex.message ?: "Unexpected error"))
}
