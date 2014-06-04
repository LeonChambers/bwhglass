package com.example.bwhglass;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.util.Log;

import java.lang.Runnable;

public class MainMenuActivity extends Activity {

    private final Handler mHandler = new Handler();
    private final static String TAG = "menu_activity";

    private boolean mAttachedToWindow;
    private boolean mOptionsMenuOpen;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            openOptionsMenu();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bindService(new Intent(this, MainService.class), mConnection, 0);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttachedToWindow = true;
        openOptionsMenu();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAttachedToWindow = false;
    }

    @Override
    public void openOptionsMenu() {
    	Log.i(TAG,"Trying to open Options Menu");
        if (!mOptionsMenuOpen && mAttachedToWindow) {
            super.openOptionsMenu();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	Log.i(TAG,"Trying to create options menu");
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	Log.i(TAG,"Selected Options item");
        switch (item.getItemId()) {
            case R.id.stop:
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopService(new Intent(MainMenuActivity.this, MainService.class));
                    }
                });
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        mOptionsMenuOpen = false;
        unbindService(mConnection);
        finish();
    }
}
