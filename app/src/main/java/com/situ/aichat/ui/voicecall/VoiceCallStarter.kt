package com.situ.aichat.ui.voicecall

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.situ.aichat.R
import com.situ.aichat.voice.VoiceCallService

/**
 * Returns a `start(conversationUuid, characterUuid)` callback that launches a voice call: if RECORD_AUDIO is
 * already granted it starts the foreground [VoiceCallService] and invokes [onCallStarted] (the caller then
 * navigates to the call screen); otherwise it requests the permission first and, on grant, does the same.
 * Encapsulates the runtime-permission dance so the chat screen stays focused on chat.
 */
@Composable
fun rememberVoiceCallStarter(
    onCallStarted: (characterUuid: String) -> Unit,
): (conversationUuid: String, characterUuid: String) -> Unit {
    val context = LocalContext.current
    val currentOnStarted by rememberUpdatedState(onCallStarted)
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pending
        pending = null
        if (granted && target != null) {
            VoiceCallService.start(context, target.first, target.second)
            currentOnStarted(target.second)
        } else if (!granted) {
            Toast.makeText(context, R.string.voice_call_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    return remember(permissionLauncher, context) {
        { conversationUuid, characterUuid ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                VoiceCallService.start(context, conversationUuid, characterUuid)
                currentOnStarted(characterUuid)
            } else {
                pending = conversationUuid to characterUuid
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
