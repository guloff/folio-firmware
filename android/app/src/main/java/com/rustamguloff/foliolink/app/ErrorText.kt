package com.rustamguloff.foliolink.app

import android.content.Context
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.ble.BleError
import com.rustamguloff.foliolink.core.ble.BleServerProofException
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.core.net.DeviceError.PairingFailure

/** Текст ошибки для человека (строки `strings_core.xml`). */
fun errorText(error: Throwable, t: Texts): String = when (error) {
    DeviceError.InvalidAddress -> t.get(R.string.core_error_invalid_address)
    DeviceError.Unreachable -> t.get(R.string.core_error_unreachable)
    DeviceError.Timeout -> t.get(R.string.core_error_timeout)
    is DeviceError.Server -> t.get(R.string.core_error_server, error.status, error.text.ifEmpty { reasonPhrase(error.status) })
    is DeviceError.AlreadyExists -> t.get(R.string.core_error_already_exists, error.name)
    is DeviceError.Decoding -> t.get(R.string.core_error_decoding, error.what)
    DeviceError.NotSupported -> t.get(R.string.core_error_not_supported)
    DeviceError.PairingRequired -> t.get(R.string.core_error_pairing_required)
    is DeviceError.Pairing -> when (val f = error.failure) {
        is PairingFailure.BadPin -> f.attemptsLeft?.let { t.get(R.string.core_error_bad_pin_left, it) }
            ?: t.get(R.string.core_error_bad_pin)
        PairingFailure.Expired -> t.get(R.string.core_error_pin_expired)
        PairingFailure.NoActivePin -> t.get(R.string.core_error_no_active_pin)
        is PairingFailure.RateLimited -> t.get(R.string.core_error_rate_limited, f.retryIn)
    }
    is DeviceError.Other -> error.text
    is DeviceError.TooLarge -> t.get(R.string.core_error_too_large, (error.limit / (1024 * 1024)).toInt())
    is com.rustamguloff.foliolink.core.store.Outbox.TooLargeException ->
        t.get(R.string.core_error_too_large, (error.limit / (1024 * 1024)).toInt())
    is DeviceError.RetryLater -> errorText(error.cause, t)
    is AppModel.PairingNotSaved -> t.get(R.string.core_pairing_save_failed)
    is BleServerProofException -> t.get(
        when (error.kind) {
            BleServerProofException.Kind.MISMATCH -> R.string.core_ble_server_proof_failed
            BleServerProofException.Kind.MISSING -> R.string.core_ble_server_proof_missing
            BleServerProofException.Kind.WRONG_READER -> R.string.core_ble_wrong_reader
        },
    )
    is GalleryAccessDenied -> t.get(R.string.core_error_gallery_denied)
    is ImageDecodeFailed -> t.get(R.string.core_error_image_decode, error.name)
    BleError.NoToken -> t.get(R.string.core_ble_no_token)
    BleError.Timeout -> t.get(R.string.core_ble_timeout)
    BleError.Disconnected -> t.get(R.string.core_ble_disconnected)
    is BleError.Busy -> t.get(R.string.core_ble_busy)
    BleError.PairingRequired -> t.get(R.string.core_ble_pairing_rejected)
    is BleError.Gatt -> t.get(R.string.core_ble_gatt, error.code)
    is BleError.Remote -> error.error
    else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

fun Context.errorText(error: Throwable): String = errorText(error) { id, args -> getString(id, *args) }

/** Стандартная фраза статуса, когда прошивка не прислала текст. */
fun reasonPhrase(status: Int): String = when (status) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    410 -> "Gone"
    413 -> "Payload Too Large"
    422 -> "Unprocessable Entity"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    503 -> "Service Unavailable"
    507 -> "Insufficient Storage"
    else -> "HTTP $status"
}
