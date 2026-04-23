package com.tugrupo.appirest.viewmodel

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tugrupo.appirest.data.network.RetrofitClient
import com.tugrupo.appirest.data.repository.ProductRepository
import com.tugrupo.appirest.model.CartItem
import com.tugrupo.appirest.model.Product
import kotlinx.coroutines.launch

sealed interface CatalogState {
    object Loading : CatalogState
    data class Success(val products: List<Product>) : CatalogState
    data class Error(val message: String) : CatalogState
}

class CatalogViewModel : ViewModel() {

    var state: CatalogState by mutableStateOf(CatalogState.Loading)
        private set

    var snackbarMessage by mutableStateOf<String?>(null)
        private set

    // ── Carrito ──────────────────────────────────────────────
    var cartItems by mutableStateOf<List<CartItem>>(emptyList())
        private set

    val cartCount: Int get() = cartItems.sumOf { it.quantity }
    val cartTotal: Double get() = cartItems.sumOf { it.product.price * it.quantity }

    fun addToCart(product: Product) {
        val existing = cartItems.find { it.product.id == product.id }
        cartItems = if (existing != null) {
            cartItems.map {
                if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it
            }
        } else {
            cartItems + CartItem(product)
        }
        snackbarMessage = "'${product.title.take(20)}…' agregado al carrito"
    }

    fun removeFromCart(productId: Int) {
        cartItems = cartItems.filter { it.product.id != productId }
    }

    fun increaseQuantity(productId: Int) {
        cartItems = cartItems.map {
            if (it.product.id == productId) it.copy(quantity = it.quantity + 1) else it
        }
    }

    fun decreaseQuantity(productId: Int) {
        cartItems = cartItems.mapNotNull {
            if (it.product.id == productId) {
                if (it.quantity > 1) it.copy(quantity = it.quantity - 1) else null
            } else it
        }
    }

    fun clearCart() {
        cartItems = emptyList()
        snackbarMessage = "Carrito vaciado"
    }
    // ─────────────────────────────────────────────────────────

    private var lastAction: (() -> Unit)? = null
    private val repository = ProductRepository(RetrofitClient.apiService)

    init { loadProducts() }

    fun loadProducts() {
        viewModelScope.launch {
            state = CatalogState.Loading
            try {
                val list = repository.fetchAllProducts()
                state = CatalogState.Success(list)
            } catch (e: Exception) {
                state = CatalogState.Error("Error de conexión: ${e.message}")
            }
        }
    }

    fun resetSnackbarMessage() { snackbarMessage = null }
    fun retryLastAction() { lastAction?.invoke() }

    fun saveProduct(product: Product) {
        viewModelScope.launch {
            try {
                val response = repository.createProduct(product)
                snackbarMessage = "Producto '${response.title}' guardado con éxito"
                lastAction = null
            } catch (e: Exception) {
                snackbarMessage = "Error al guardar: ${e.localizedMessage}"
                lastAction = { saveProduct(product) }
            }
        }
    }

    fun editProduct(id: Int, product: Product) {
        viewModelScope.launch {
            try {
                repository.updateProduct(id, product)
                // Actualizar la lista local
                val current = (state as? CatalogState.Success)?.products ?: return@launch
                state = CatalogState.Success(
                    current.map { if (it.id == id) product else it }
                )
                snackbarMessage = "Producto actualizado correctamente"
                lastAction = null
            } catch (e: Exception) {
                snackbarMessage = "Error al actualizar: ${e.message}"
                lastAction = { editProduct(id, product) }
            }
        }
    }

    fun removeProduct(id: Int) {
        viewModelScope.launch {
            try {
                repository.deleteProduct(id)
                // Eliminar de la lista local
                val current = (state as? CatalogState.Success)?.products ?: return@launch
                state = CatalogState.Success(
                    current.filter { it.id != id }
                )
                snackbarMessage = "Producto eliminado"
                lastAction = null
            } catch (e: Exception) {
                snackbarMessage = "Error al eliminar el producto"
                lastAction = { removeProduct(id) }
            }
        }
    }
}