package com.mingdai.toTool.fakecall

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

class FakeCallConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val number = request.address?.schemeSpecificPart
            ?: extras?.getString(FakeCallTelecom.EXTRA_CALLER_NUMBER)
            ?: "0000"
        val name = extras?.getString(FakeCallTelecom.EXTRA_CALLER_NAME).orEmpty()
        val hideNumber = extras?.getBoolean(FakeCallTelecom.EXTRA_HIDE_NUMBER, false) ?: false
        val audioPath = extras?.getString(FakeCallTelecom.EXTRA_AUDIO_PATH).orEmpty()
        val ringTimeout = extras?.getInt(FakeCallTelecom.EXTRA_RING_TIMEOUT, 45) ?: 45
        return FakeCallConnection(
            context = this,
            callerName = name,
            callerNumber = number,
            hideNumber = hideNumber,
            audioPath = audioPath,
            ringTimeoutSeconds = ringTimeout,
        )
    }
}
