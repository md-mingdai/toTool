package com.mingdai.toTool.fakecall

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** 注册「模拟来电」电话账户，并通过系统 Telecom 发起来电。 */
internal object FakeCallTelecom {
    const val ACCOUNT_ID = "toTool_fake_call"
    const val EXTRA_CALLER_NAME = "com.mingdai.toTool.fakecall.CALLER_NAME"
    const val EXTRA_CALLER_NUMBER = "com.mingdai.toTool.fakecall.CALLER_NUMBER"
    const val EXTRA_HIDE_NUMBER = "com.mingdai.toTool.fakecall.HIDE_NUMBER"
    const val EXTRA_AUDIO_PATH = "com.mingdai.toTool.fakecall.AUDIO_PATH"
    const val EXTRA_RING_TIMEOUT = "com.mingdai.toTool.fakecall.RING_TIMEOUT"
    const val DEFAULT_ACCOUNT_LABEL = "模拟来电"

    private fun manager(context: Context): TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    fun accountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context, FakeCallConnectionService::class.java),
            ACCOUNT_ID,
        )

    fun register(context: Context, label: String = DEFAULT_ACCOUNT_LABEL): Boolean = runCatching {
        val telecom = manager(context) ?: return@runCatching false
        val account = PhoneAccount.Builder(accountHandle(context), label)
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()
        telecom.registerPhoneAccount(account)
        true
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean = runCatching {
        manager(context)?.getPhoneAccount(accountHandle(context))?.isEnabled == true
    }.getOrDefault(false)

    fun trigger(
        context: Context,
        callerName: String,
        callerNumber: String,
        hideNumber: Boolean,
        audioPath: String?,
        ringTimeoutSeconds: Int,
    ): Boolean = runCatching {
        val telecom = manager(context) ?: return@runCatching false
        val number = callerNumber.trim().ifBlank { "0000" }
        val callExtras = Bundle().apply {
            putString(EXTRA_CALLER_NAME, callerName.trim())
            putString(EXTRA_CALLER_NUMBER, number)
            putBoolean(EXTRA_HIDE_NUMBER, hideNumber)
            putString(EXTRA_AUDIO_PATH, audioPath.orEmpty())
            putInt(EXTRA_RING_TIMEOUT, ringTimeoutSeconds)
        }
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
            )
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras)
        }
        telecom.addNewIncomingCall(accountHandle(context), extras)
        true
    }.getOrDefault(false)

    fun accountSettingsIntent(): Intent =
        Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
