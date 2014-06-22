package com.example.bwhglass;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.google.android.glass.timeline.DirectRenderingCallback;
import com.google.android.glass.timeline.LiveCard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

public class MainService extends Service {

	private boolean isRunning = false;
	private static String TAG = "MAIN_SERVICE";
	
	private String[] mSensors;
    private LiveCard[] mLiveCards;
    private Map<String,Double> mCurrentSensorValues = new HashMap<String,Double>();
    
    private LiveCard mImageCard;
    private static final String IMAGE_CARD_TAG = "microscope_image";
    
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private final UpdateSensorValuesRunnable mUpdateSensorValuesRunnable = new UpdateSensorValuesRunnable();
    
    private static final long DATA_UPDATE_DELAY_MILLIS = 500;
    private static final long FRAME_TIME_MILLIS = 100;
    private static final long IMAGE_FRAME_TIME_MILLIS = 1000;

    // Called when the app is opened. Initialize all the things
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
    	// We have to check whether or not the app is already running
    	if (!isRunning) {
    		// Start the app
    		isRunning = true;
    		mHandlerThread = new HandlerThread("myHandlerThread");
    		mHandlerThread.start();
    		mHandler = new Handler(mHandlerThread.getLooper());
    		MakeLiveCardsTask task = new MakeLiveCardsTask();
    		task.execute(this);
    	}
    	else {
    		if (mLiveCards.length > 0) {
    			mLiveCards[0].navigate();
    		}
    	}
    	return START_STICKY;
    }
    
    // Live cards must be loaded asynchronously so that we are not going web queries in the main thread
    private class MakeLiveCardsTask extends AsyncTask<MainService,Void,Void> {
    	protected Void doInBackground(MainService... service) {
    		Log.i(TAG,"Making live cards");
    		mSensors = getSensorList();
    		mLiveCards = new LiveCard[mSensors.length];
    		// Loop through all the sensors
    		for (int i = 0; i < mSensors.length; i++) {
    			// Create a live card for each sensor
    			mLiveCards[i] = new LiveCard(service[0],mSensors[i]);
    			
    			// Enable direct rendering
    			mLiveCards[i].setDirectRenderingEnabled(true);
    			mLiveCards[i].getSurfaceHolder().addCallback(new LiveCardRenderer(mSensors[i]));
    			
    			// Set up the live card's action
    			Intent intent = new Intent(service[0], MainMenuActivity.class);
    			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    			mLiveCards[i].setAction(PendingIntent.getActivity(service[0],0,intent,0));
    			
    			// Publish the live cards
    			mLiveCards[i].attach(service[0]);
    			mLiveCards[i].publish(LiveCard.PublishMode.SILENT);
    			
    			// Make a dummy value for the current sensor value
    			mCurrentSensorValues.put(mSensors[i],0.);
    		}
    		// Make the live card to show the webcam image. Same setup as above
    		mImageCard = new LiveCard(service[0],IMAGE_CARD_TAG);
    		mImageCard.setDirectRenderingEnabled(true);
    		mImageCard.getSurfaceHolder().addCallback(new ImageCardRenderer());
    		Intent intent = new Intent(service[0], MainMenuActivity.class);
    		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    		mImageCard.setAction(PendingIntent.getActivity(service[0], 0, intent, 0));
    		mImageCard.attach(service[0]);
    		mImageCard.publish(LiveCard.PublishMode.SILENT);
    		if (mLiveCards.length > 0) {
    			mLiveCards[0].navigate();
    		}
    		// Start getting sensor values from the server
    		mHandler.post(mUpdateSensorValuesRunnable);
    		return null;
    	}
    }
    
    // Used to render each of the live cards
    public class LiveCardRenderer implements DirectRenderingCallback {
		private SurfaceHolder mSurfaceHolder;
    	private boolean mPaused;
    	private RenderThread mRenderThread;
    	
    	private String mSensor;
    	private int mWidth;
    	private int mHeight;
    	
    	/*private float lastTimestamp;
    	private JSONArray newDataPoints;*/
    	
    	public LiveCardRenderer(String sensor) {
			mSensor = sensor;
		}
    	
    	@Override
    	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    		mSurfaceHolder = holder;
    		mWidth = width;
    		mHeight = height;
    	}
    	
    	@Override
    	public void surfaceCreated(SurfaceHolder holder) {
    		mSurfaceHolder = holder;
    		updateRendering();
    	}
    	
    	@Override
    	public void surfaceDestroyed(SurfaceHolder holder) {
    		mSurfaceHolder = null;
    		updateRendering();
    	}
    	
    	@Override
    	public void renderingPaused(SurfaceHolder holder, boolean paused) {
    		mPaused = paused;
    		updateRendering();
    	}
    	
    	// Start or stop rendering according to the timeline state
    	private synchronized void updateRendering() {
    		boolean shouldRender = (mSurfaceHolder != null) && !mPaused;
    		boolean rendering = mRenderThread != null;
    		if (shouldRender != rendering) {
    			if (shouldRender) {
    				mRenderThread = new RenderThread();
    				mRenderThread.start();
    			}
    			else {
    				mRenderThread.quit();
    				mRenderThread = null;
    			}
    		}
    	}
    	
    	// Draws the view in the SurfaceHolder's canvas
    	private void draw() {
    		Canvas canvas;
    		try {
    			canvas = mSurfaceHolder.lockCanvas();
    		}
    		catch (Exception e) {
    			Log.e(TAG,"Failed to lock canvas",e);
    			return;
    		}
    		if (canvas != null) {
    			double sensorValue = mCurrentSensorValues.get(mSensor);
    			// Draw the background
    			Paint paint = new Paint(); 
    			paint.setColor(Color.BLACK); 
    			paint.setStyle(Style.FILL); 
    			canvas.drawPaint(paint); 
    			// Draw the text
    			paint.setColor(Color.WHITE); 
    			paint.setTextAlign(Align.CENTER);
    			paint.setTextSize(50);
    			int textX = (int)(mWidth*0.85); // Use the right 30% of the screen
    			int textY = (int)(mHeight/3.); // A third of the height of the screen
    			canvas.drawText(mSensor, textX, textY, paint);
    			paint.setTextSize(35);
    			canvas.drawText(String.valueOf(sensorValue), textX, 2*textY, paint);
    			// TODO: Draw the graph
    			mSurfaceHolder.unlockCanvasAndPost(canvas);
    		}
    	}
    	
    	// A thread that will periodically call the draw function to redraw this card
    	private class RenderThread extends Thread {
    		private boolean mShouldRun;
    		
    		public RenderThread() {
    			mShouldRun = true;
    		}
    		
    		private synchronized boolean shouldRun() {
    			return mShouldRun;
    		}
    		
    		public synchronized void quit() {
    			mShouldRun = false;
    		}
    		
    		@Override
    		public void run() {
    			while (shouldRun()) {
    				draw();
    				SystemClock.sleep(FRAME_TIME_MILLIS);
    			}
    		}
    	}
    }

    // Used to render the live card that shows the webcam image
    public class ImageCardRenderer implements DirectRenderingCallback {
		private SurfaceHolder mSurfaceHolder;
    	private boolean mPaused;
    	private RenderThread mRenderThread;
    	
    	@Override
    	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    		// TODO save surface information
    	}
    	
    	@Override
    	public void surfaceCreated(SurfaceHolder holder) {
    		mSurfaceHolder = holder;
    		updateRendering();
    	}
    	
    	@Override
    	public void surfaceDestroyed(SurfaceHolder holder) {
    		mSurfaceHolder = null;
    		updateRendering();
    	}
    	
    	@Override
    	public void renderingPaused(SurfaceHolder holder, boolean paused) {
    		Log.i(TAG,String.valueOf(paused));
    		mPaused = paused;
    		updateRendering();
    	}
    	
    	// Start or stop rendering according to the timeline state
    	private synchronized void updateRendering() {
    		boolean shouldRender = (mSurfaceHolder != null) && !mPaused;
    		boolean rendering = mRenderThread != null;
    		if (shouldRender != rendering) {
    			if (shouldRender) {
    				mRenderThread = new RenderThread();
    				mRenderThread.start();
    			}
    			else {
    				mRenderThread.quit();
    				mRenderThread = null;
    			}
    		}
    	}
    	
    	// Draws the view in the SurfaceHolder's canvas
    	private void draw() {
    		Canvas canvas;
    		try {
    			canvas = mSurfaceHolder.lockCanvas();
    		}
    		catch (Exception e) {
    			Log.e(TAG,"Failed to lock canvas",e);
    			return;
    		}
    		if (canvas != null) {
    			// TODO: Load the image in a different thread
    			Bitmap image = getMicroscopeImage();
    			if (image != null) {
    				canvas.drawBitmap(image,null,canvas.getClipBounds(),null);
    			}
    			mSurfaceHolder.unlockCanvasAndPost(canvas);
    		}
    	}
    	
    	// A thread that will periodically call the draw function to redraw this card
    	private class RenderThread extends Thread {
    		private boolean mShouldRun;
    		
    		public RenderThread() {
    			mShouldRun = true;
    		}
    		
    		private synchronized boolean shouldRun() {
    			return mShouldRun;
    		}
    		
    		public synchronized void quit() {
    			mShouldRun = false;
    		}
    		
    		@Override
    		public void run() {
    			while (shouldRun()) {
    				draw();
    				SystemClock.sleep(IMAGE_FRAME_TIME_MILLIS);
    			}
    		}
    	}
    }
    
    private class DataPoint {
    	private int mValue;
    	private int mTimestamp;
    	DataPoint(int value, int timestamp) {
    		mValue = value;
    		mTimestamp = timestamp;
    	}
    	public int getValue() {
    		return mValue;
    	}
    	public int getTimestamp() {
    		return mTimestamp;
    	}
    }
    
    // Runnable that updates sensor values
    private class UpdateSensorValuesRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			JSONObject values = getSensorValues();
    			if (values != null) {
    				for (int i = 0; i < mSensors.length; i++) {
    					try {
    						mCurrentSensorValues.put(mSensors[i],values.getDouble(mSensors[i]));
    					} catch (JSONException e) {
    					}
    				}
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

    // Called when our app is exited. Clean up everything
    @Override
    public void onDestroy() {
    	isRunning = false;
    	mUpdateSensorValuesRunnable.setStop(true);
    	for (int i = 0; i < mLiveCards.length; i++) {
    		if (mLiveCards[i] != null && mLiveCards[i].isPublished()) {
    			mLiveCards[i].unpublish();
    			mLiveCards[i] = null;
    			Log.i(TAG,"Unpublished live card");
    		}
    	}
    	if (mImageCard != null && mImageCard.isPublished()) {
    		mImageCard.unpublish();
    		mImageCard = null;
    		Log.i(TAG,"Unpublished image card");
    	}
        super.onDestroy();
    }
    
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	// Pull a image from the server
	private Bitmap getMicroscopeImage() {
		try {
			URL url = new URL("http://planar-contact-601.appspot.com/picture/view");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setDoInput(true);
			connection.connect();
			InputStream input = connection.getInputStream();
			Bitmap res = BitmapFactory.decodeStream(input);
			return res;
		}
		catch (IOException e) {
			Log.e(TAG,"Failed to download image", e);
			return null;
		}
	}
	
	// Get a list of sensors from the server
	private String[] getSensorList() {
		try {
			String raw = getURL("http://planar-contact-601.appspot.com/sensor_names");
			JSONArray list = new JSONArray(new JSONTokener(raw));
			String[] res = new String[list.length()];
			for (int i = 0; i < list.length(); i++) {
				res[i] = list.getString(i);
			}
			return res;
		}
		catch (Exception e) {
			Log.e(TAG,"Failed to get sensor list",e);
			return new String[0];
		}
	}
	
	// Get the values of all of the sensors
	private JSONObject getSensorValues() {
		try {
			String values = getURL("http://planar-contact-601.appspot.com/sensor_values");
			return new JSONObject(new JSONTokener(values));
		} catch (Exception e) {
			Log.e(TAG,"Failed to get sensor values",e);
			return null;
		}
	}
	
	/*private JSONArray getDataPoints(String sensor, float last_timestamp) {
		try {
			String url = "http://planar-contact-601.appspot.com/?sensor=" + sensor + "&last_timestamp=" + String.valueOf(last_timestamp);
			String values = getURL(url);
			return new JSONArray(new JSONTokener(values));
		}
		catch (Exception e) {
			Log.e(TAG,"Failed to get data points",e);
			return null;
		}
	}*/
	
	// Download data from a URL
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
}