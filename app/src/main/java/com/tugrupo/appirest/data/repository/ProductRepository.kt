package com.tugrupo.appirest.data.repository

import com.tugrupo.appirest.data.network.ProductApiService
import com.tugrupo.appirest.model.Product

class ProductRepository(private val api: ProductApiService) {

    suspend fun fetchAllProducts() = api.getProducts()

    suspend fun createProduct(product: Product) = api.addProduct(product)

    suspend fun updateProduct(id: Int, product: Product) = api.updateProduct(id, product)

    suspend fun deleteProduct(id: Int) = api.deleteProduct(id)
}