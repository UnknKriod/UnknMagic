package me.unknkriod.ang.ui

import android.os.Bundle
import me.unknkriod.ang.R
import me.unknkriod.ang.core.CoreServiceManager

class ScStartActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        if (!CoreServiceManager.isRunning()) {
            CoreServiceManager.startVServiceFromToggle(this)
        }
        finish()
    }
}

