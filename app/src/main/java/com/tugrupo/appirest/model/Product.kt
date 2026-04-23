package com.tugrupo.appirest.model

data class Product(
    val id: Int,
    val title: String,
    val price: Double,
    val description: String,
    val category: String,
    val image: String // URL de la imagen
)