package com.example.airis

import android.util.Log

object NativeBridge {
    init {
        try {
            System.loadLibrary("airis")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeBridge", "Failed to load library", e)
            throw e
        }
    }

    external fun loadModel(path: String): Boolean
    external fun generate(prompt: String): String
}
