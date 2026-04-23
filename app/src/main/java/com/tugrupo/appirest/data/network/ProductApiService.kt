package com.tugrupo.appirest.data.network

import com.tugrupo.appirest.model.Product
import retrofit2.http.*

interface ProductApiService {

    @GET("products")
    suspend fun getProducts(): List<Product>

    @POST("products")
    suspend fun addProduct(@Body product: Product): Product

    @PUT("products/{id}")
    suspend fun updateProduct(@Path("id") id: Int, @Body product: Product): Product

    @DELETE("products/{id}")
    suspend fun deleteProduct(@Path("id") id: Int): Product
}