package com.tugrupo.appirest.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.tugrupo.appirest.services.CartSyncService
import com.tugrupo.appirest.services.SyncEvent   // <- import directo, sin ambigüedad
import kotlinx.coroutines.delay
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
    private val cartRepository    = CartRepository(RetrofitClient.cartApiService)

    private var lastAction: (() -> Unit)? = null

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
    // SINCRONIZACIÓN CON LA API DE CARTS
    // ──────────────────────────────────────────────────────────

    fun loadRemoteCarts() {
        viewModelScope.launch {
            try {
                remoteCarts = cartRepository.getAllCarts()
            } catch (e: Exception) {
                // No bloqueamos la UI si falla
            }
        }
    }

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
     * Envía el carrito a la API y actualiza la notificación obligatoria
     * con el resultado REAL de la operación de red.
     *
     * Flujo:
     *  1. Lanza CartSyncService  → notificación "Enviando…" (barra indeterminada)
     *  2. Hace POST /carts
     *  3a. Éxito  → emite SyncEvent.Success  → notificación "✅ Pedido #N confirmado"
     *  3b. Error  → emite SyncEvent.Failure  → notificación "❌ Error al enviar"
     *  4. El servicio se autodestruye y la notificación desaparece sola
     */
    fun checkoutCart(context: Context, onSuccess: (Cart) -> Unit) {
        if (cartItems.isEmpty()) {
            snackbarMessage = "El carrito está vacío"
            return
        }

        // 1. Señaliza estado inicial y lanza el servicio
        CartSyncService.syncEvent.value = SyncEvent.Sending
        val serviceIntent = Intent(context, CartSyncService::class.java).apply {
            putExtra(CartSyncService.EXTRA_ITEM_COUNT, cartCount)
            putExtra(CartSyncService.EXTRA_TOTAL, cartTotal)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        viewModelScope.launch {
            cartSyncState = CartSyncState.Syncing
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val cartPayload = Cart(
                    userId   = currentUserId,
                    date     = today,
                    products = cartItems.map { item ->
                        CartProduct(productId = item.product.id, quantity = item.quantity)
                    }
                )

                // 2. Llamada real a la API
                val response = cartRepository.createCart(cartPayload)

                // Mantiene la notificación obligatoria visible el tiempo suficiente
                // para que el usuario compruebe que NO se puede deslizar ni quitar.
                // En producción con una API real este delay no es necesario porque
                // la operación de red tarda varios segundos por sí sola.
                delay(4000)

                // 3a. Éxito → notificación muestra el ID del pedido real
                CartSyncService.syncEvent.value = SyncEvent.Success(response.id)

                cartSyncState = CartSyncState.Synced(response.id)
                snackbarMessage = "✅ Pedido #${response.id} creado exitosamente"
                clearCart()
                loadRemoteCarts()
                onSuccess(response)

            } catch (e: Exception) {
                // 3b. Error → notificación muestra el motivo
                CartSyncService.syncEvent.value = SyncEvent.Failure("Intenta de nuevo más tarde")

                cartSyncState = CartSyncState.Error("Error al procesar pedido: ${e.message}")
                snackbarMessage = "Error al finalizar compra: ${e.message}"
            }
        }
    }

    fun updateRemoteCart(cartId: Int) {
        viewModelScope.launch {
            cartSyncState = CartSyncState.Syncing
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val cartPayload = Cart(
                    userId   = currentUserId,
                    date     = today,
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

    fun resetSnackbarMessage()  { snackbarMessage = null }
    fun retryLastAction()       { lastAction?.invoke() }
    fun resetCartSyncState()    { cartSyncState = CartSyncState.Idle }
}