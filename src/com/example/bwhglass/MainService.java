package com.example.bwhglass;

import com.google.android.glass.timeline.LiveCard;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

public class MainService extends Service {

    private static final String LIVE_CARD_TAG = "hello_glass";
    private LiveCard mLiveCard;
    private RemoteViews mLiveCardView;
    
    private int update_index;
    
    private final Handler mHandler = new Handler();
    private final UpdateLiveCardRunnable mUpdateLiveCardRunnable = new UpdateLiveCardRunnable();
    private static final long DELAY_MILLIS = 1000;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {
        	// Create the live card
            mLiveCard = new LiveCard(this, LIVE_CARD_TAG);
            // Make a RemoteView to manage our live card
            mLiveCardView = new RemoteViews(getPackageName(),R.layout.hello_glass);
            // Set up initial RemoveView values
            update_index = 0;
            mLiveCardView.setTextViewText(R.id.number,String.valueOf(update_index));
            // Set up the LiveCard's action
            Intent menuIntent = new Intent(this, MainMenuActivity.class);
            mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));
            // Publish the live card
            mLiveCard.attach(this);
            mLiveCard.publish(LiveCard.PublishMode.REVEAL);
            mHandler.post(mUpdateLiveCardRunnable);
        }
        else {
            mLiveCard.navigate();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mLiveCard != null && mLiveCard.isPublished()) {
        	mUpdateLiveCardRunnable.setStop(true);
            mLiveCard.unpublish();
            mLiveCard = null;
            Log.i(LIVE_CARD_TAG,"Unpublished LiveCard");
        }
        else {
        	Log.i(LIVE_CARD_TAG,"LiveCard not published, so we won't unpublish it");
        }
        super.onDestroy();
    }
    
    // Runnable that updates live card contents
    private class UpdateLiveCardRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			update_index++;
    			// Update the RemoveViews
    			mLiveCardView.setTextViewText(R.id.number,String.valueOf(update_index));
    			// Update the live card's RemoveViews
    			mLiveCard.setViews(mLiveCardView);
    			// Queue another card update
    			mHandler.postDelayed(mUpdateLiveCardRunnable,DELAY_MILLIS);
    		}
    	}
    	public boolean isStopped() {
    		return mIsStopped;
    	}
    	public void setStop(boolean isStopped) {
    		this.mIsStopped = isStopped;
    	}
    }
    
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}

