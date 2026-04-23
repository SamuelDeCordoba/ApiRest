package com.tugrupo.appirest.model

data class CartProduct(
    val productId: Int,
    val quantity: Int
)

data class Cart(
    val id: Int = 0,
    val userId: Int,
    val date: String,
    val products: List<CartProduct>
)