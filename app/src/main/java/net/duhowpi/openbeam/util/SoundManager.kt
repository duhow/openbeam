package net.duhowpi.openbeam.util

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/**
 * Plays UI sound feedback during sharing interactions.
 *
 * Sound effects:
 * - [playBeam]    – NFC tag detected / sending
 * - [playSuccess] – Sharing completed successfully
 * - [playError]   – Sharing failed
 * - [playTap]     – Generic button tap
 */
object SoundManager {

    /**
     * Play the "beam" sound – emitted when an NFC tag is detected and we start writing.
     * Uses the system camera shutter / focus sound as a proxy for "beaming".
     */
    fun playBeam(context: Context) {
        playSystemSound(context, AudioManager.FX_FOCUS_NAVIGATION_UP)
    }

    /** Play the success notification sound (standard Android notification). */
    fun playSuccess(context: Context) {
        playRingtone(context, RingtoneManager.TYPE_NOTIFICATION)
    }

    /** Play the error / failure sound. */
    fun playError(context: Context) {
        playSystemSound(context, AudioManager.FX_KEYPRESS_DELETE)
    }

    /** Play a light tap / click sound (button interaction). */
    fun playTap(context: Context) {
        playSystemSound(context, AudioManager.FX_KEY_CLICK)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun playSystemSound(context: Context, effectType: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.playSoundEffect(effectType, 1.0f)
    }

    private fun playRingtone(context: Context, type: Int) {
        runCatching {
            val uri: Uri = RingtoneManager.getDefaultUri(type) ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            ringtone.play()
        }
    }
}
