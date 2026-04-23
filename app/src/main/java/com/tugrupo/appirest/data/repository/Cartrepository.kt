package com.tugrupo.appirest.data.repository

import com.tugrupo.appirest.data.network.CartApiService
import com.tugrupo.appirest.model.Cart

class CartRepository(private val api: CartApiService) {

    suspend fun getAllCarts(): List<Cart> = api.getAllCarts()

    suspend fun getCartById(id: Int): Cart = api.getCartById(id)

    suspend fun getCartsByUser(userId: Int): List<Cart> = api.getCartsByUser(userId)

    suspend fun createCart(cart: Cart): Cart = api.createCart(cart)

    suspend fun updateCart(id: Int, cart: Cart): Cart = api.updateCart(id, cart)

    suspend fun deleteCart(id: Int): Cart = api.deleteCart(id)
}