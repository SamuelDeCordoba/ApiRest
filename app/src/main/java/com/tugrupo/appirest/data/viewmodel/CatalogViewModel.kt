package com.tugrupo.appirest.viewmodel

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tugrupo.appirest.data.network.RetrofitClient
import com.tugrupo.appirest.data.repository.CartRepository
import com.tugrupo.appirest.data.repository.ProductRepository
import com.tugrupo.appirest.model.Cart
import com.tugrupo.appirest.model.CartItem
import com.tugrupo.appirest.model.CartProduct
import com.tugrupo.appirest.model.Product
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed interface CatalogState {
    object Loading : CatalogState
    data class Success(val products: List<Product>) : CatalogState
    data class Error(val message: String) : CatalogState
}

sealed interface CartSyncState {
    object Idle : CartSyncState
    object Syncing : CartSyncState
    data class Synced(val cartId: Int) : CartSyncState
    data class Error(val message: String) : CartSyncState
}

class CatalogViewModel : ViewModel() {

    // ── Estado del catálogo ───────────────────────────────────
    var state: CatalogState by mutableStateOf(CatalogState.Loading)
        private set

    var snackbarMessage by mutableStateOf<String?>(null)
        private set

    // ── Estado del carrito local ──────────────────────────────
    var cartItems by mutableStateOf<List<CartItem>>(emptyList())
        private set

    val cartCount: Int get() = cartItems.sumOf { it.quantity }
    val cartTotal: Double get() = cartItems.sumOf { it.product.price * it.quantity }

    // ── Estado de sincronización con la API ───────────────────
    var cartSyncState: CartSyncState by mutableStateOf(CartSyncState.Idle)
        private set

    // Carritos obtenidos de la API (para consulta/historial)
    var remoteCarts by mutableStateOf<List<Cart>>(emptyList())
        private set

    // ── Repositorios ──────────────────────────────────────────
    private val productRepository = ProductRepository(RetrofitClient.apiService)
    private val cartRepository = CartRepository(RetrofitClient.cartApiService)

    private var lastAction: (() -> Unit)? = null

    // ── Usuario simulado (en una app real vendría del login) ──
    private val currentUserId = 1

    init {
        loadProducts()
        loadRemoteCarts()
    }

    // ──────────────────────────────────────────────────────────
    // PRODUCTOS
    // ──────────────────────────────────────────────────────────

    fun loadProducts() {
        viewModelScope.launch {
            state = CatalogState.Loading
            try {
                val list = productRepository.fetchAllProducts()
                state = CatalogState.Success(list)
            } catch (e: Exception) {
                state = CatalogState.Error("Error de conexión: ${e.message}")
            }
        }
    }

    fun saveProduct(product: Product) {
        viewModelScope.launch {
            try {
                val response = productRepository.createProduct(product)
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
                productRepository.updateProduct(id, product)
                val current = (state as? CatalogState.Success)?.products ?: return@launch
                state = CatalogState.Success(current.map { if (it.id == id) product else it })
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
                productRepository.deleteProduct(id)
                val current = (state as? CatalogState.Success)?.products ?: return@launch
                state = CatalogState.Success(current.filter { it.id != id })
                snackbarMessage = "Producto eliminado"
                lastAction = null
            } catch (e: Exception) {
                snackbarMessage = "Error al eliminar el producto"
                lastAction = { removeProduct(id) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // CARRITO LOCAL
    // ──────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────
    // SINCRONIZACIÓN CON LA API DE CARTS (fakestoreapi)
    // ──────────────────────────────────────────────────────────

    /**
     * Carga todos los carritos del servidor (GET /carts).
     * Útil para mostrar historial de órdenes del usuario.
     */
    fun loadRemoteCarts() {
        viewModelScope.launch {
            try {
                remoteCarts = cartRepository.getAllCarts()
            } catch (e: Exception) {
                // No bloqueamos la UI si falla la carga de carritos remotos
            }
        }
    }

    /**
     * Carga los carritos de un usuario específico (GET /carts/user/{userId}).
     */
    fun loadCartsByUser(userId: Int = currentUserId) {
        viewModelScope.launch {
            try {
                remoteCarts = cartRepository.getCartsByUser(userId)
            } catch (e: Exception) {
                snackbarMessage = "Error al cargar historial: ${e.message}"
            }
        }
    }

    /**
     * Convierte el carrito local y lo envía a la API (POST /carts).
     * Se llama al finalizar la compra.
     */
    fun checkoutCart(onSuccess: (Cart) -> Unit) {
        if (cartItems.isEmpty()) {
            snackbarMessage = "El carrito está vacío"
            return
        }

        viewModelScope.launch {
            cartSyncState = CartSyncState.Syncing
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val cartPayload = Cart(
                    userId = currentUserId,
                    date = today,
                    products = cartItems.map { item ->
                        CartProduct(
                            productId = item.product.id,
                            quantity = item.quantity
                        )
                    }
                )
                val response = cartRepository.createCart(cartPayload)
                cartSyncState = CartSyncState.Synced(response.id)
                snackbarMessage = "✅ Pedido #${response.id} creado exitosamente"
                clearCart()
                loadRemoteCarts()          // refrescar historial
                onSuccess(response)
            } catch (e: Exception) {
                cartSyncState = CartSyncState.Error("Error al procesar pedido: ${e.message}")
                snackbarMessage = "Error al finalizar compra: ${e.message}"
            }
        }
    }

    /**
     * Actualiza un carrito existente en la API (PUT /carts/{id}).
     */
    fun updateRemoteCart(cartId: Int) {
        viewModelScope.launch {
            cartSyncState = CartSyncState.Syncing
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val cartPayload = Cart(
                    userId = currentUserId,
                    date = today,
                    products = cartItems.map { item ->
                        CartProduct(productId = item.product.id, quantity = item.quantity)
                    }
                )
                val response = cartRepository.updateCart(cartId, cartPayload)
                cartSyncState = CartSyncState.Synced(response.id)
                snackbarMessage = "Carrito #${response.id} actualizado"
            } catch (e: Exception) {
                cartSyncState = CartSyncState.Error(e.message ?: "Error desconocido")
                snackbarMessage = "Error al actualizar carrito: ${e.message}"
            }
        }
    }

    /**
     * Elimina un carrito de la API (DELETE /carts/{id}).
     */
    fun deleteRemoteCart(cartId: Int) {
        viewModelScope.launch {
            try {
                cartRepository.deleteCart(cartId)
                remoteCarts = remoteCarts.filter { it.id != cartId }
                snackbarMessage = "Carrito #$cartId eliminado"
            } catch (e: Exception) {
                snackbarMessage = "Error al eliminar carrito: ${e.message}"
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // UTILIDADES
    // ──────────────────────────────────────────────────────────

    fun resetSnackbarMessage() { snackbarMessage = null }
    fun retryLastAction() { lastAction?.invoke() }
    fun resetCartSyncState() { cartSyncState = CartSyncState.Idle }
}