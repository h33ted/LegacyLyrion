package com.h33ted.legacylyrion.retro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Headset / Bluetooth media keys, and pausing the phone when headphones are pulled out. */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Prefs.setupDone(c)) return;
        String action = null;
        if ("android.media.AUDIO_BECOMING_NOISY".equals(intent.getAction())) {
            if (Prefs.playerIsPhone(c)) action = SlimProtoService.ACTION_PAUSE;
        } else if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            // only while the phone itself plays: otherwise leave the keys to the phone's own player
            if (!Prefs.playerIsPhone(c)) return;
            KeyEvent ev = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (ev == null) return;
            try {
                abortBroadcast();   // media keys are an ordered broadcast; keep them from the phone's own player
            } catch (RuntimeException ignored) {}
            if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return;
            switch (ev.getKeyCode()) {
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case 85:   // MEDIA_PLAY_PAUSE
                case 126:  // MEDIA_PLAY (3.0+)
                    action = SlimProtoService.ACTION_TOGGLE;
                    break;
                case 127:  // MEDIA_PAUSE
                    action = SlimProtoService.ACTION_PAUSE;
                    break;
                case 87:   // MEDIA_NEXT
                    action = SlimProtoService.ACTION_NEXT;
                    break;
                case 88:   // MEDIA_PREVIOUS
                    action = SlimProtoService.ACTION_PREV;
                    break;
                default:
                    return;
            }
        }
        if (action != null) c.startService(new Intent(c, SlimProtoService.class).setAction(action));
    }
}
