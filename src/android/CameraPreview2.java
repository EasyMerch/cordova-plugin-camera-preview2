package ru.pronetcom.camerapreview2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;

import org.json.JSONArray;
import org.json.JSONException;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;

import ru.pronetcom.easymerch2.R;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraPreview2 extends CordovaPlugin {
	public static final String TAG = "EMPlugin";

	public String frontCameraId = null;
	public String backCameraId = null;

	private FrameLayout camera2Layout;
	private Camera2 mCamera2 = null;

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		Log.e(TAG, "initialize");

		try {
			findCameraDevices();
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}


	public void findCameraDevices() throws CameraAccessException {
		CameraManager cameraManager = (CameraManager) cordova.getContext().getSystemService(Context.CAMERA_SERVICE);
		String[] idList = cameraManager.getCameraIdList();

		for (String id : idList) {
			CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);

			switch (characteristics.get(CameraCharacteristics.LENS_FACING)) {
				case CameraMetadata.LENS_FACING_FRONT:
					frontCameraId = id;
					break;
				case CameraMetadata.LENS_FACING_BACK:
					backCameraId = id;
					break;
			}
		}
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
			case "startCamera":
				cordova.getActivity().runOnUiThread(new Runnable() {
					@Override
					public void run() {
						startCamera(callbackContext);
					}
				});
				return true;
			case "shoot": shoot(callbackContext); return true;
			case "close": close(callbackContext); return true;
		}
		return false;
	}

	private void initLayout(){
		LayoutInflater inflater = cordova.getActivity().getLayoutInflater();
		
		camera2Layout = (FrameLayout) inflater.inflate(R.layout.camera2_layout, null);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		
		camera2Layout.setLayoutParams(params);
		cordova.getActivity().addContentView(camera2Layout, params);
	}

	public void startCamera(CallbackContext callbackContext) {
		if(mCamera2 != null){
			callbackContext.error(Camera2.cameraAccessExceptionMessage(Camera2.cameraInUseException()));
			return;
		}
		initLayout();

		String cameraId = backCameraId;
		CameraManager cameraManager = (CameraManager) cordova.getContext().getSystemService(Context.CAMERA_SERVICE);
		CameraCharacteristics characteristics = null;
		try {
			characteristics = cameraManager.getCameraCharacteristics(cameraId);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
		StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

		for(int formatId : configs.getOutputFormats()){
			Log.e(TAG, String.format("SUPPORTED FORMAT %s", formatId));
		}

		View parent = ((View)camera2Layout.getParent());
		SurfaceView surfaceView = (SurfaceView) camera2Layout.findViewById(R.id.camera2_surface);
		ImageReader cameraImageReader = ImageReader.newInstance(parent.getWidth(), parent.getHeight(), ImageFormat.RAW_SENSOR, 1);

		if (ActivityCompat.checkSelfPermission(cordova.getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			return;
		}

		mCamera2 = new Camera2(surfaceView, cameraImageReader);
		mCamera2.open(cordova.getContext(), cameraId, new Camera2.StateCallback() {
			@Override
			public void onError(String code, String message){
				callbackContext.error(message);
			}

			@Override
			public void onOpen(){
				callbackContext.success();
			}

			@Override
			public void onClose() {
				((ViewGroup)camera2Layout.getParent()).removeView(camera2Layout);
				camera2Layout = null;
				mCamera2 = null;
			}
		});
	}

	public void shoot(CallbackContext callbackContext) {
		mCamera2.shoot(new Camera2.ShootCallback() {
			public void onError(String code, String message){
				callbackContext.error(message);
			}

			@Override
			public void onShoot(Image image){
				try {
					File tempFile = File.createTempFile("123", ".jpeg");

					Image.Plane plane = image.getPlanes()[0];

					int bitsPerPixel = ImageFormat.getBitsPerPixel(image.getFormat());
					int pixelStride = plane.getPixelStride();
					int rowStride = plane.getRowStride();

					Log.e("TEMP", String.format(
						"bitsPerPixel = %s\n"+
						"pixelStride = %s\n"+
						"rowStride = %s\n"+
						""
						,
						bitsPerPixel,
						pixelStride,
						rowStride
					));


					throw new IOException("TEMP");
					/*
					FileOutputStream out = new FileOutputStream(tempFile);
					FileChannel channel = out.getChannel();

					Image.Plane[] planes = image.getPlanes();
					for(Image.Plane plane : planes){
						ByteBuffer buffer = plane.getBuffer();
						channel.write(buffer);
					}
					out.close();
					callbackContext.success(tempFile.getPath());
					 */
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
}