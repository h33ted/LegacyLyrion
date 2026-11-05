package com.h33ted.legacylyrion.retro;

import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;

/** One level of a Jive menu: an album, a playlist, an artist, a search result... */
public class BrowseActivity extends RetroActivity {
    private static final int M_PLAY = 100, M_ADD = 101;
    private JiveList list;
    private JSONArray play, add;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        play = parse(getIntent().getStringExtra("play"));
        add = parse(getIntent().getStringExtra("add"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(titleBar(getIntent().getStringExtra("title"), getIntent().getStringExtra("subtitle")));
        FrameLayout body = new FrameLayout(this);
        TextView status = text(16, false, 0xffdddddd);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(false);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        list = new JiveList(this, status);
        body.addView(list.list);
        body.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));
        setContentView(root);
        String node = getIntent().getStringExtra("node");
        if (node != null) list.loadNode(node);
        else list.load(parse(getIntent().getStringExtra("cmd")));
    }

    private static JSONArray parse(String s) {
        if (s == null) return null;
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    void reload() { list.reload(); }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        if (play != null) m.add(0, M_PLAY, 0, "Play all").setIcon(android.R.drawable.ic_media_play);
        if (add != null) m.add(0, M_ADD, 0, "Add to queue").setIcon(android.R.drawable.ic_menu_add);
        return super.onCreateOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == M_PLAY) {
            JiveList.exec(this, play, "Playing " + getIntent().getStringExtra("title"));
            return true;
        }
        if (item.getItemId() == M_ADD) {
            JiveList.exec(this, add, "Added to queue");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
