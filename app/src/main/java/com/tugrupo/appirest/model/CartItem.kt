package com.tugrupo.appirest.model

data class CartItem(
    val product: Product,
    val quantity: Int = 1
)