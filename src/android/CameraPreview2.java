package ru.pronetcom.camerapreview2;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;

import ru.pronetcom.easymerch2.R;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraPreview2 extends CordovaPlugin {
	public static final String TAG = "CameraPreview2";

	private CameraManager cameraManager;

	public static class CameraOptions{
		public int previewWidth;
		public int previewHeight;
		public int previewX;
		public int previewY;
		public int pictureWidth;
		public int pictureHeight;
		public int lensFacing;
		public int orientation;

		private final DisplayMetrics metrics;

		private int applyDimension(int value){
			return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics);
		}
		private double applyDimension(double value){
			return (double) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (float)value, metrics);
		}

		CameraOptions(JSONObject options, Context context) throws JSONException{
			if(options == null) options = new JSONObject();

			metrics = context.getResources().getDisplayMetrics();

			previewWidth = applyDimension(options.optInt("width"));
			previewHeight = applyDimension(options.optInt("height"));
			previewX = applyDimension(options.optInt("x"));
			previewY = applyDimension(options.optInt("y"));

			if(previewHeight == 0) previewHeight = FrameLayout.LayoutParams.MATCH_PARENT;
			if(previewWidth == 0) previewWidth = FrameLayout.LayoutParams.MATCH_PARENT;

			pictureWidth = applyDimension(options.optInt("pictureWidth"));
			pictureHeight = applyDimension(options.optInt("pictureHeight"));
			
			switch(options.optString("camera")){
				default:
				case "back":
					lensFacing = CameraMetadata.LENS_FACING_BACK;
					break;
				case "front":
					lensFacing = CameraMetadata.LENS_FACING_FRONT;
					break;
			}
			
			switch(options.optString("orientation")){
				case "portrait":
					orientation = 0;
					break;
				case "landscape":
				default:
					orientation = 90;
					break;
			}
		}
	}

	private FrameLayout cameraLayout;
	private Camera2 mCamera2 = null;

	private final HashMap<Integer, String> cameraTypesMap = new HashMap<>();

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		cameraManager = (CameraManager) cordova.getContext().getSystemService(Context.CAMERA_SERVICE);
	}

	public String findCameraId(int lensFacing) throws CameraAccessException {
		if(cameraTypesMap.containsKey(lensFacing)) return cameraTypesMap.get(lensFacing);

		String[] cameraIds = cameraManager.getCameraIdList();

		Log.e(TAG, String.format("CAMERAS COUNT = %s", cameraIds.length));

		for (String id : cameraIds) {
			CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
			if(characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing){
				cameraTypesMap.put(lensFacing, id);
				return id;
			}
		}

		return null;
	}

	/**
	 * Executes the request and returns PluginResult.
	 *
	 * @param action            The action to execute.
	 * @param args              JSONArry of arguments for the plugin.
	 * @param callbackContext   The callback id used when calling back into JavaScript.
	 * @return True if the action was valid, false if not.
	 */
	public boolean execute(@NonNull String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		switch (action){
			case "startCamera": {
				CameraOptions options = new CameraOptions(args.getJSONObject(0), cordova.getContext());
				cordova.getActivity().runOnUiThread(new Runnable() {
					@Override
					public void run() {
						try {
							startCamera(callbackContext, options);
						} catch (CameraAccessException | JSONException e) {
							callbackContext.error(e.getMessage());
							e.printStackTrace();
						}
					}
				});
				return true;
			}
			case "takePicture": takePicture(callbackContext); return true;
			case "close": close(callbackContext); return true;
			case "getSupportedSizes":
				try {
					getSupportedSizes(callbackContext, args.getJSONObject(0));
				} catch (JSONException e){
					e.printStackTrace();
					throw e;
				}
				return true;
		}
		return false;
	}

	public void getSupportedSizes(CallbackContext callbackContext, JSONObject jsonOptions) throws JSONException{
		try{
			CameraOptions options = new CameraOptions(jsonOptions, cordova.getContext());
			Camera2 camera2 = new Camera2(findCameraId(options.lensFacing), cordova.getContext());
			Size[] sizes = camera2.getSupportedSizes(ImageFormat.JPEG, options.orientation);

			JSONArray jsonSizes = new JSONArray();
			for(Size size : sizes){
				JSONObject jsonSize = new JSONObject();
				jsonSize.put("width", size.getWidth());
				jsonSize.put("height", size.getHeight());
				jsonSizes.put(jsonSize);
			}

			callbackContext.success(jsonSizes);
		} catch (CameraAccessException e) {
			callbackContext.error(e.getMessage());
			e.printStackTrace();
		} catch(JSONException e) {
			e.printStackTrace();
			throw e;
		}
	}

	private FrameLayout initLayout(int x, int y){
		LayoutInflater inflater = cordova.getActivity().getLayoutInflater();
		FrameLayout layout = (FrameLayout) inflater.inflate(R.layout.camera2_layout, null);

		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		layout.setLayoutParams(params);

		layout.setX(x);
		layout.setY(y);

		cordova.getActivity().addContentView(layout, params);
		return layout;
	}

	public void startCamera(CallbackContext callbackContext, CameraOptions options) throws CameraAccessException, JSONException{
		String cameraId = findCameraId(options.lensFacing);

		cameraLayout = initLayout(options.previewX, options.previewY);
		SurfaceView surfaceView = (SurfaceView) cameraLayout.findViewById(R.id.camera2_surface);

		Camera2.Preview preview = new SurfaceViewPreview(surfaceView, new Size(options.previewWidth, options.previewHeight));
		mCamera2 = new Camera2(cameraId, cordova.getContext());
		mCamera2.setPreview(preview);
		mCamera2.setPicture(new Size(options.pictureWidth, options.pictureHeight));
		mCamera2.open(new Camera2.StateCallback(){
			@Override
			public void onError(String code, String message){
				Log.e(TAG, "DEBUG Camera2.StateCallback onError");
				callbackContext.error(message);
			}

			@Override
			public void onOpen() throws CameraAccessException{
				Log.e(TAG, "DEBUG Camera2.StateCallback onOpen");
				mCamera2.startPreview();
				callbackContext.success();
			}

			@Override
			public void onClose() {
				Log.e(TAG, "DEBUG Camera2.StateCallback onClose");
				// clear();
			}
		});
	}

	public void takePicture(CallbackContext callbackContext) {
		if(mCamera2 == null){
			callbackContext.error("Camera is closed");
			return;
		}

		mCamera2.takePicture(new Camera2.ShootCallback() {
			@Override
			public void onError(String code, String message){
				callbackContext.error(message);
			}

			@Override
			public void onShoot(Image image){
				try {
					File file = saveImage(image);
					callbackContext.success(file.getPath());
				} catch (IOException e) {
					callbackContext.error("Can not create file");
				}
			}
		});
	}

	public void close(CallbackContext callbackContext){
		if(mCamera2 != null) mCamera2.close();
		callbackContext.success();
	}

	private void clear(){
		if(cameraLayout != null){
			((ViewGroup)cameraLayout.getParent()).removeView(cameraLayout);
		}
		cameraLayout = null;
		mCamera2 = null;
	}

	private File saveImage(Image image) throws IOException{
		File tempFile = File.createTempFile("camera2", ".jpeg");
		FileOutputStream out = new FileOutputStream(tempFile);
		FileChannel channel = out.getChannel();

		Image.Plane[] planes = image.getPlanes();
		for(Image.Plane plane : planes){
			ByteBuffer buffer = plane.getBuffer();
			channel.write(buffer);
		}
		out.close();

		return tempFile;
	}
}