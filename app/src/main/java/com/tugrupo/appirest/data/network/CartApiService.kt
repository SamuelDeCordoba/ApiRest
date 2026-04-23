package com.tugrupo.appirest.data.network

import com.tugrupo.appirest.model.Cart
import retrofit2.http.*

interface CartApiService {

    // GET  /carts           → todos los carritos
    @GET("carts")
    suspend fun getAllCarts(): List<Cart>

    // GET  /carts/{id}      → carrito por ID
    @GET("carts/{id}")
    suspend fun getCartById(@Path("id") id: Int): Cart

    // GET  /carts/user/{userId} → carritos de un usuario
    @GET("carts/user/{userId}")
    suspend fun getCartsByUser(@Path("userId") userId: Int): List<Cart>

    // POST /carts           → crear carrito
    @POST("carts")
    suspend fun createCart(@Body cart: Cart): Cart

    // PUT  /carts/{id}      → reemplazar carrito completo
    @PUT("carts/{id}")
    suspend fun updateCart(@Path("id") id: Int, @Body cart: Cart): Cart

    // DELETE /carts/{id}    → eliminar carrito
    @DELETE("carts/{id}")
    suspend fun deleteCart(@Path("id") id: Int): Cart
}