package com.example.bwhglass;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.lang.Runnable;

public class VideoMenuActivity extends Activity {

    private final Handler mHandler = new Handler();
    private MainService.MyBinder mBinder;

    private boolean mAttachedToWindow;
    private boolean mOptionsMenuOpen;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        	mBinder = (MainService.MyBinder)service;
            openOptionsMenu();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
        	mBinder = null;
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
        if (!mOptionsMenuOpen && mAttachedToWindow) {
            super.openOptionsMenu();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.video, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.stop:
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopService(new Intent(VideoMenuActivity.this, MainService.class));
                    }
                });
                return true;
            case R.id.toggle_view:
            	mBinder.toggleView();
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
