package com.example.bwhglass;

import java.io.InputStream;
import java.net.URL;

import com.google.android.glass.timeline.LiveCard;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

public class MainService extends Service {

	private boolean isRunning = false;
	private static String TAG = "MAIN_SERVICE";
	
	private String[] mSensors;
    private LiveCard[] mLiveCards;
    private RemoteViews[] mLiveCardViews;
    private String[] mSensorValues;
    private Bitmap[] mSensorGraphs;
    
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private final UpdateLiveCardsRunnable mUpdateLiveCardsRunnable = new UpdateLiveCardsRunnable();
    private final UpdateSensorValuesRunnable mUpdateSensorValuesRunnable = new UpdateSensorValuesRunnable();
    private final UpdateSensorGraphsRunnable mUpdateSensorGraphsRunnable = new UpdateSensorGraphsRunnable();
    private static final long DATA_UPDATE_DELAY_MILLIS = 500;
    private static final long IMAGE_UPDATE_DELAY_MILLIS = 10000;
    private static final long SCREEN_UPDATE_DELAY_MILLIS = 1000;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
    	if (!isRunning) {
    		mHandlerThread = new HandlerThread("myHandlerThread");
    		mHandlerThread.start();
    		mHandler = new Handler(mHandlerThread.getLooper());
    		MakeLiveCardsTask task = new MakeLiveCardsTask();
    		task.execute(this);
    	}
    	else {
    		mLiveCards[0].navigate();
    	}
        return START_STICKY;
    }
    
    // Live cards must be loaded asynchronously so that we are not going web queries in the main thread
    private class MakeLiveCardsTask extends AsyncTask<MainService,Integer,Boolean> {
    	protected Boolean doInBackground(MainService... service) {
    		// Initialize everything
    		mSensors = getSensorList();
    		mLiveCards = new LiveCard[mSensors.length];
    		mLiveCardViews = new RemoteViews[mSensors.length];
    		mSensorValues = new String[mSensors.length];
    		mSensorGraphs = new Bitmap[mSensors.length];
    		// Loop through all the sensors
    		for (int i = 0; i < mSensors.length; i++) {
    			// Create a live card for each sensor
    			mLiveCards[i] = new LiveCard(service[0],mSensors[i]);
    			mLiveCardViews[i] = new RemoteViews(getPackageName(),R.layout.live_card);
    			mLiveCardViews[i].setTextViewText(R.id.sensor_name,mSensors[i]);
    			mLiveCardViews[i].setTextViewText(R.id.value,mSensorValues[i]);
    			
    			// Set up the live card's action
    			Intent menuIntent = new Intent(service[0], MainMenuActivity.class);
    			menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    			mLiveCards[i].setAction(PendingIntent.getActivity(service[0],0,menuIntent,0));
    			
    			// Publish the live cards
    			mLiveCards[i].attach(service[0]);
    			mLiveCards[i].publish(LiveCard.PublishMode.SILENT);
    		}
    		mLiveCards[0].navigate();
    		mHandler.post(mUpdateSensorValuesRunnable);
    		mHandler.post(mUpdateLiveCardsRunnable);
    		mHandler.post(mUpdateSensorGraphsRunnable);
    		return true;
    	}
    }

    @Override
    public void onDestroy() {
    	isRunning = false;
    	mUpdateLiveCardsRunnable.setStop(true);
    	mUpdateSensorValuesRunnable.setStop(true);
    	mUpdateSensorGraphsRunnable.setStop(true);
    	if (!mHandlerThread.quitSafely()) {
    		Log.e(TAG, "Failed to quit handler thread");
    	}
    	for (int i = 0; i < mLiveCards.length; i++) {
    		LiveCard curr_card = mLiveCards[i];
    		if (curr_card != null && curr_card.isPublished()) {
    			curr_card.unpublish();
    			mLiveCards[i] = null;
    			Log.i(TAG,"Unpublished live card");
    		}
    		else {
    			Log.i(TAG,"Live card not published, so we won't unpublish it");
    		}
    	}
        super.onDestroy();
    }
    
    // Runnable that updates live card contents
    private class UpdateLiveCardsRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			for (int i = 0; i < mLiveCards.length; i++) {
    				if (mLiveCards[i].isPublished()) {
    					// Update things
    					if (mSensorGraphs[i] != null) {
    						mLiveCardViews[i].setImageViewBitmap(R.id.graph, mSensorGraphs[i]);
    					}
        				mLiveCardViews[i].setTextViewText(R.id.value,String.valueOf(mSensorValues[i]));
        				mLiveCards[i].setViews(mLiveCardViews[i]);
    				}
    			}
    			mHandler.postDelayed(mUpdateLiveCardsRunnable,SCREEN_UPDATE_DELAY_MILLIS);
    		}
    	}
    	public boolean isStopped() {
    		return mIsStopped;
    	}
    	public void setStop(boolean isStopped) {
    		this.mIsStopped = isStopped;
    	}
    }
    
    // Runnable that updates sensor values
    private class UpdateSensorValuesRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			for (int i = 0; i < mSensors.length; i++) {
    				mSensorValues[i] = getSensorValue(mSensors[i]);
    			}
    		}
    		mHandler.postDelayed(mUpdateSensorValuesRunnable, DATA_UPDATE_DELAY_MILLIS);
    	}
    	public boolean isStopped() {
    		return mIsStopped;
    	}
    	public void setStop(boolean isStopped) {
    		this.mIsStopped = isStopped;
    	}
    }
    
    private class UpdateSensorGraphsRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			for (int i = 0; i < mSensors.length; i++) {
    				try {
    					InputStream in = new java.net.URL("http://planar-contact-601.appspot.com/graph/"+mSensors[i]).openStream();
    					mSensorGraphs[i] = BitmapFactory.decodeStream(in);
    				}
    				catch (Exception e) {
    					Log.e(TAG,"Failed to load graph", e);
    				}
    			}
    		}
    		mHandler.postDelayed(mUpdateSensorGraphsRunnable, IMAGE_UPDATE_DELAY_MILLIS);
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
	
	private String getURL(String _url) throws Exception {
		URL url = new URL(_url);
		InputStream is = url.openStream();
		int ptr = 0;
		StringBuffer buffer = new StringBuffer();
		while ((ptr = is.read()) != -1) {
			buffer.append((char)ptr);
		}
		return buffer.toString();
	}
	
	private String[] getSensorList() {
		String sensors = "";
		try {
			sensors = getURL("http://planar-contact-601.appspot.com/sensors");
		} catch (Exception e) {
			Log.e(TAG, "Failed to get sensor list", e);
		}
		if (sensors.contains(",")) {
			return sensors.split(",");
		}
		else {
			return new String[] {sensors};
		}
	}
	
	private String getSensorValue(String sensor) {
		try {
			return getURL("http://planar-contact-601.appspot.com/value/"+sensor);
		} catch (Exception e) {
			Log.e(TAG,"Failed to get sensor value",e);
			return null;
		}
	}
}

