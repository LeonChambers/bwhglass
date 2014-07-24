package com.example.bwhglass;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;

import com.google.android.glass.timeline.DirectRenderingCallback;
import com.google.android.glass.timeline.LiveCard;
import com.googlecode.charts4j.*;

import static com.googlecode.charts4j.Color.WHITE;
import static com.googlecode.charts4j.Color.BLUE;

import org.json.*;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.*;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.*;
import android.util.Log;
import android.view.SurfaceHolder;

public class MainService extends Service {

	private boolean isRunning = false;
	private static String TAG = "MAIN_SERVICE";
	
	private String[] mSensors;
    private LiveCard[] mLiveCards;
    private Map<String,Double> mCurrentSensorValues;
    private Map<String, ArrayList<DataPoint>> mSensorGraphData;
    private Map<String,Bitmap> mCurrentSensorGraphs;
    
    private LiveCard mVideoCard;
    private static final String VIDEO_CARD_TAG = "microscope video";
    private static final String VIDEO_FILE_NAME = Environment.getExternalStorageDirectory()+"/microscope_video.mp4";
    private static final String TEMP_VIDEO_FILE_NAME = Environment.getExternalStorageDirectory()+"/temp_microscope_video.mp4";
    
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private final UpdateSensorValuesRunnable mUpdateSensorValuesRunnable = new UpdateSensorValuesRunnable();
    private final UpdateSensorGraphsRunnable mUpdateSensorGraphsRunnable = new UpdateSensorGraphsRunnable();
    private final UpdateMicroscopeVideoRunnable mUpdateMicroscopeVideoRunnable = new UpdateMicroscopeVideoRunnable();
    
    private static final long DATA_UPDATE_DELAY_MILLIS = 500;
    private static final long GRAPH_UPDATE_DELAY_MILLIS = 5000;
    private static final long FRAME_TIME_MILLIS = 100;
    private static final long VIDEO_UPDATE_DELAY_MILLIS = 10*1000;

    // Called when the app is opened. Initialize all the things
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
    		Log.i(TAG,"Setting up app");
    		mSensors = getSensorList();
    		mLiveCards = new LiveCard[mSensors.length];
    		mCurrentSensorValues = new HashMap<String,Double>();
    		mSensorGraphData = new HashMap<String, ArrayList<DataPoint>>();
    		mCurrentSensorGraphs = new HashMap<String,Bitmap>();
    		for (int i = 0; i < mSensors.length; i++) {
    			// Make dummy values for everything
    			mCurrentSensorValues.put(mSensors[i],0.);
    			mSensorGraphData.put(mSensors[i], new ArrayList<DataPoint>());
    			mCurrentSensorGraphs.put(mSensors[i], null);
    		}
    		Log.i(TAG,"Loading initial data");
    		// Get real initial data
    		mUpdateSensorValuesRunnable.run();
    		mUpdateSensorGraphsRunnable.run();
    		mUpdateMicroscopeVideoRunnable.run();
    		Log.i(TAG,"Making live cards");
    		// Create the live cards
    		for (int i = 0; i < mSensors.length; i++) {
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
    		}
    		// Make the live card to show the webcam video. Same setup as above
    		mVideoCard = new LiveCard(service[0],VIDEO_CARD_TAG);
    		mVideoCard.setDirectRenderingEnabled(true);
    		mVideoCard.getSurfaceHolder().addCallback(new VideoCardRenderer());
    		Intent intent = new Intent(service[0], MainMenuActivity.class);
    		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    		mVideoCard.setAction(PendingIntent.getActivity(service[0],0,intent,0));
    		mVideoCard.attach(service[0]);
    		mVideoCard.publish(LiveCard.PublishMode.SILENT);
    		if (mLiveCards.length > 0) {
    			mLiveCards[0].navigate();
    		}
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
    			paint.setStyle(Paint.Style.FILL); 
    			canvas.drawPaint(paint); 
    			// Draw the text
    			paint.setColor(Color.WHITE); 
    			paint.setTextAlign(Paint.Align.CENTER);
    			paint.setTextSize(30);
    			int textX = (int)(mWidth*0.85); // Use the right 30% of the screen
    			int textY = (int)(mHeight/3.); // A third of the height of the screen
    			String sensor = mSensor;
    			if (sensor.equals("temperature")) {
    				sensor = "Temperature";
    			}
    			canvas.drawText(sensor, textX, textY, paint);
    			paint.setTextSize(30);
    			canvas.drawText(String.format("%.5g", sensorValue), textX, 2*textY, paint);
    			Bitmap graph = mCurrentSensorGraphs.get(mSensor);
    			if (graph != null) {
    				Rect dest = canvas.getClipBounds();
    				dest.inset((int)(mWidth*0.15), 0);
    				dest.offset(-(int)(mWidth*0.15), 0);
    				canvas.drawBitmap(graph, null, dest, null);
    			}
    			else {
    				Log.i(TAG,"No graph found");
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
    				SystemClock.sleep(FRAME_TIME_MILLIS);
    			}
    		}
    	}
    }

 // Used to render the live card that shows the webcam video
    public class VideoCardRenderer implements DirectRenderingCallback {
		private SurfaceHolder mSurfaceHolder;
    	private boolean mPaused;
    	private RenderThread mRenderThread;
    	
    	public VideoCardRenderer() {
    	}
    	
    	@Override
    	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
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
    	
    	// A thread that will periodically call the draw function to redraw this card
    	private class RenderThread extends Thread implements MediaPlayer.OnCompletionListener {
    		private boolean mShouldRun;
    		private boolean videoIsPlaying;
    		private MediaPlayer mMediaPlayer;
    		
    		public RenderThread() {
    			mShouldRun = true;
    			videoIsPlaying = false;
    		}
    		
    		private synchronized boolean shouldRun() {
    			return mShouldRun;
    		}
    		
    		public synchronized void quit() {
    			mShouldRun = false;
    		}
    		
    		public synchronized void onCompletion(MediaPlayer mp) {
    			videoIsPlaying = false;
    		}
    		
    		@Override
    		public void run() {
    			while (shouldRun()) {
    				try {
    					copyFile(new File(TEMP_VIDEO_FILE_NAME), new File(VIDEO_FILE_NAME));
    					mMediaPlayer = new MediaPlayer();
        				mMediaPlayer.setDataSource(VIDEO_FILE_NAME);
        				mMediaPlayer.setDisplay(mSurfaceHolder);
        				mMediaPlayer.setScreenOnWhilePlaying(true);
        				mMediaPlayer.setOnCompletionListener(this);
        				mMediaPlayer.prepare();
        				mMediaPlayer.start();
        				videoIsPlaying = true;
        				while (shouldRun() && videoIsPlaying) {
        					SystemClock.sleep(100);
        				}
        				mMediaPlayer.stop();
        				mMediaPlayer.reset();
        				mMediaPlayer.release();
        			}
        			catch (IOException e) {
        				Log.e(VIDEO_CARD_TAG,"Failed to read microscope video file",e);
        				mMediaPlayer.stop();
        				mMediaPlayer.reset();
        				mMediaPlayer.release();
        			}
    			}
    		}
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
    			mHandler.postDelayed(mUpdateSensorValuesRunnable, DATA_UPDATE_DELAY_MILLIS);
    		}
    	}
    	public boolean isStopped() {
    		return mIsStopped;
    	}
    	public void setStop(boolean isStopped) {
    		this.mIsStopped = isStopped;
    	}
    }
    
    // Runnable that updates the graphs
    private class UpdateSensorGraphsRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			// Loop through each of the sensors
    			for (int i = 0; i < mSensors.length; i++) {
    				String curr_sensor = mSensors[i];
    				ArrayList<DataPoint> curr_data = mSensorGraphData.get(curr_sensor);
    				double lastTimestamp = curr_data.size() > 0 ? curr_data.get(curr_data.size()-1).getTimestamp() : 0.;
    				// Get a list of the new data points for this sensor
    				JSONArray newPoints = getDataPoints(curr_sensor, lastTimestamp);
    				for (int j = 0; j < newPoints.length(); j++) {
    					// Save each data point
    					try {
    						JSONObject point = newPoints.getJSONObject(j);
    						double timestamp = (Double)point.get("timestamp");
    						double value = (Double)point.get("value");
    						curr_data.add(new DataPoint(timestamp, value));
    					}
    					catch (JSONException e) {
    						Log.e(TAG, "JSON error", e);
    						continue;
    					}
    				}
    				// Clear out points that are over an hour old
    				while (true) {
    					if (curr_data.size() > 0 && curr_data.get(0).getTimestamp() < (curr_data.get(curr_data.size()-1).getTimestamp())-3600) {
    						Log.i(TAG,"Deleting old data point");
    						curr_data.remove(0);
    					}
    					else {
    						break;
    					}
    				}
    				// If there are no points don't show a graph
    				if (curr_data.size() == 0) {
    					continue;
    				}
    				// Store the timestamps and values in separate arrays for graphing
    				ArrayList<Double> timestamps = new ArrayList<Double>();
    				ArrayList<Double> values = new ArrayList<Double>();
    				for (int j = 0; j < curr_data.size(); j++) {
    					DataPoint curr_point = curr_data.get(j);
    					timestamps.add(curr_point.getTimestamp());
    					values.add(curr_point.getValue());
    				}
    				// Scale the timestamp data
    				double maxTimestamp = Collections.max(timestamps);
    				double zeroVal = maxTimestamp - 3600.;
    				for (int j = 0; j < timestamps.size(); j++) {
    					double currVal = timestamps.get(j);
    					currVal -= zeroVal;
    					currVal /= 36;
    					timestamps.set(j,currVal);
    				}
    				// Scale the value data
    				double maxVal = Collections.max(values);
    				double minVal = Collections.min(values);
    				maxVal *= 1.2;
    				minVal *= 0.8;
    				double intervalSize = maxVal - minVal;
    				for (int j = 0; j < values.size(); j++) {
    					double currVal = values.get(j);
    					currVal -= minVal;
    					currVal /= intervalSize;
    					currVal *= 100;
    					values.set(j,currVal);
    				}
    				
    				Data xData = Data.newData(timestamps);
    				Data yData = Data.newData(values);
    				ScatterPlotData data = Plots.newScatterPlotData(xData, yData);
    		        data.addShapeMarkers(Shape.DIAMOND, BLUE, 20);
    		        data.setColor(BLUE);
        			ScatterPlot chart = GCharts.newScatterPlot(data);
        			chart.setSize(400,400);
        			chart.setGrid(25, 20, 3, 2);
        			AxisLabels yAxisLabels = AxisLabelsFactory.newNumericRangeAxisLabels(minVal, maxVal);
        			yAxisLabels.setAxisStyle(AxisStyle.newAxisStyle(BLUE, 30, AxisTextAlignment.CENTER));
        	        chart.addYAxisLabels(yAxisLabels);

        	        chart.setBackgroundFill(Fills.newSolidFill(WHITE));
        	        chart.setAreaFill(Fills.newSolidFill(WHITE));
        			try {
    					URL chartURL = new URL(chart.toURLString());
    					mCurrentSensorGraphs.put(curr_sensor, getImage(chartURL));
    				} catch (MalformedURLException e) {
    					Log.e(TAG,"Invalid chart URL", e);
    				} catch (IOException e) {
    					Log.e(TAG,"Failed to download graph", e);
    				}
    			}
    			mHandler.postDelayed(mUpdateSensorGraphsRunnable,GRAPH_UPDATE_DELAY_MILLIS);
    		}
    	}
    	public boolean isStopped() {
    		return mIsStopped;
    	}
    	public void setStop(boolean isStopped) {
    		this.mIsStopped = isStopped;
    	}
    }

    // Runnable that updates the microscope video
    private class UpdateMicroscopeVideoRunnable implements Runnable {
    	private boolean mIsStopped = false;
    	public void run() {
    		if (!isStopped()) {
    			getMicroscopeVideo();
    			mHandler.postDelayed(mUpdateMicroscopeVideoRunnable, VIDEO_UPDATE_DELAY_MILLIS);
    		}
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
    	mUpdateSensorGraphsRunnable.setStop(true);
    	mUpdateMicroscopeVideoRunnable.setStop(true);
    	for (int i = 0; i < mLiveCards.length; i++) {
    		if (mLiveCards[i] != null && mLiveCards[i].isPublished()) {
    			mLiveCards[i].unpublish();
    			mLiveCards[i] = null;
    			Log.i(TAG,"Unpublished live card");
    		}
    	}
    	if (mVideoCard != null && mVideoCard.isPublished()) {
    		mVideoCard.unpublish();
    		mVideoCard = null;
    		Log.i(TAG,"Unpublished image card");
    	}
        super.onDestroy();
    }
    
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	class DataPoint {
		private final double mTimestamp;
		private final double mValue;
		DataPoint(double timestamp, double value) {
			mTimestamp = timestamp;
			mValue = value;
		}
		public double getTimestamp() {
			return mTimestamp;
		}
		public double getValue() {
			return mValue;
		}
	}
	
	// Pull an image from a URL
	private Bitmap getImage(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setDoInput(true);
		connection.connect();
		InputStream input = connection.getInputStream();
		Bitmap res = BitmapFactory.decodeStream(input);
		return res;
	}
	
	// Pull the microscope video from a URL
	private void getMicroscopeVideo() {
		try {
			URL url = new URL("http://planar-contact-601.appspot.com/video/view");
	        long startTime = System.currentTimeMillis();
	        Log.i(TAG, "video download beginning: "+url);
	        URLConnection ucon = url.openConnection();
	        ucon.setReadTimeout(0);
	        ucon.setConnectTimeout(0);
	        // Define InputStreams to read from the URLConnection.
	        InputStream is = ucon.getInputStream();
	        BufferedInputStream inStream = new BufferedInputStream(is, 1024*5);
	        File file = new File(TEMP_VIDEO_FILE_NAME);
	        FileOutputStream outStream = new FileOutputStream(file);
	        FileLock lock = outStream.getChannel().lock();
	        byte[] buff = new byte[1024*5];
	        // Read bytes (and store them) until there is nothing more to read(-1)
	        int len;
	        while ((len = inStream.read(buff)) != -1) {
	            outStream.write(buff,0,len);
	        }
	        // Clean up
	        outStream.flush();
	        lock.release();
	        outStream.close();
	        inStream.close();
	        Log.i(TAG, "download completed in "
	                + ((System.currentTimeMillis() - startTime) / 1000)
	                + " sec");
		}
		catch (IOException e) {
			Log.e(VIDEO_CARD_TAG, "Failed to download microscope video", e);
		}
	}

	// Copy a file from <src> to <dst>
	public void copyFile(File src, File dst) {
		// Acquire i/o streams for each of the files
		RandomAccessFile inFile = null;
		FileOutputStream outStream = null;
		try {
			inFile = new RandomAccessFile(src, "rw");
			outStream = new FileOutputStream(dst);
		}
	    catch (FileNotFoundException e) {
	    	Log.e(TAG,"File not found", e);
	    	return;
	    }
	    FileChannel inChannel = inFile.getChannel();
	    FileChannel outChannel = outStream.getChannel();
	    FileLock inLock = null;
	    FileLock outLock = null;
	    try {
	    	inLock = inChannel.lock();
	    	outLock = outChannel.lock();
		    inChannel.transferTo(0, inChannel.size(), outChannel);
		}
		catch (IOException e) {
			Log.e(TAG,"Failed to acquire locks and copy file", e);
		}
		try {
		    inLock.release();
		    inFile.close();
		    outLock.release();
		    outStream.close();
		}
		catch (Exception e) {
			Log.e(TAG,"An error occurred while cleaning up",e);
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
	
	// Get the new data points that we will need to graph
	private JSONArray getDataPoints(String sensor, double last_timestamp) {
		try {
			String url = "http://planar-contact-601.appspot.com/graphing_data?sensor=" + sensor + "&last_timestamp=" + String.valueOf(last_timestamp);
			String values = getURL(url);
			return new JSONArray(new JSONTokener(values));
		}
		catch (Exception e) {
			Log.e(TAG,"Failed to get data points",e);
			return null;
		}
	}
	
	// Download data from a URL
	private String getURL(String _url) throws MalformedURLException, IOException {
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