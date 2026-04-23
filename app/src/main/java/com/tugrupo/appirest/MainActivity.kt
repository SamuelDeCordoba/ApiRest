package com.tugrupo.appirest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tugrupo.appirest.ui.screens.CatalogScreen
import com.tugrupo.appirest.ui.theme.AppiRestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppiRestTheme {
                CatalogScreen()
            }
        }
    }
}