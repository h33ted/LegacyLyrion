package com.h33ted.legacylyrion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.KeyEvent;

/** Headset / Bluetooth / lock-screen media keys, and pausing when headphones are unplugged. */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = null;
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
            // only pause if this phone is the speaker
            if (SlimProtoService.localMac(c).equalsIgnoreCase(Prefs.player(c))) action = MediaNotifier.ACTION_PAUSE;
        } else if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return;
            switch (ev.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    action = MediaNotifier.ACTION_TOGGLE;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    action = MediaNotifier.ACTION_PAUSE;
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    action = MediaNotifier.ACTION_NEXT;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    action = MediaNotifier.ACTION_PREV;
                    break;
                default:
                    return;
            }
            if (isOrderedBroadcast()) abortBroadcast();
        }
        if (action != null) c.startService(new Intent(c, SlimProtoService.class).setAction(action));
    }
}
