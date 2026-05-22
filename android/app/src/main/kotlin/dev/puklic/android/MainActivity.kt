package dev.puklic.android

import android.app.Activity
import android.os.Bundle
import android.view.View

/** Phase 1 stub. Phase 2 will host the Compose root and wire ViewModels. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
    }
}
