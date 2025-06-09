package com.tak.lite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tak.lite.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
abstract class BaseActivity : AppCompatActivity() {
    @Inject
    lateinit var messageRepository: MessageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The repository will be injected by Hilt and start observing messages
    }
} 