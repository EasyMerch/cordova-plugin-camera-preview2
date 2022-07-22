package ru.pronetcom.camerapreview2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class Camera2 {
	public final static String OPEN_FAILED_ERROR = "OPEN_FAILED_ERROR";
	public final static String SESSION_CONFIGURATION_ERROR = "SESSION_CONFIGURATION_ERROR";
	public final static String CAMERA_ACCESS_ERROR = "CAMERA_ACCESS_ERROR";

	public abstract static class StateCallback{
		public abstract void onError(String code, String message);
		public abstract void onOpen() throws CameraAccessException;
		public abstract void onClose();
	}

	public static abstract class ShootCallback {
		public abstract void onError(String code, String message);
		public abstract void onShoot(Image image);
	}

	public interface PreviewStateCallback {
		void onSurfaceCreated(@NonNull Preview preview);
		void onSurfaceDestroyed(@NonNull Preview preview);
		default void onSurfaceReady(@NonNull Preview preview){};
	}

	public static abstract class Preview{
		protected final static class PreviewStateCallbackSet extends HashSet<PreviewStateCallback> implements PreviewStateCallback{
			@Override
			public void onSurfaceCreated(@NonNull Preview preview){
				for(PreviewStateCallback callback : this) callback.onSurfaceCreated(preview);
			};
			@Override
			public void onSurfaceDestroyed(@NonNull Preview preview){
				for(PreviewStateCallback callback : this) callback.onSurfaceDestroyed(preview);
			};
			@Override
			public void onSurfaceReady(@NonNull Preview preview){
				for(PreviewStateCallback callback : this) callback.onSurfaceReady(preview);
			};
		}
		protected final PreviewStateCallbackSet stateCallbacks = new PreviewStateCallbackSet();

		public void addStateCallback(PreviewStateCallback callback){
			stateCallbacks.add(callback);
			onAddState(callback);
		};

		public void removeStateCallback(PreviewStateCallback callback){
			stateCallbacks.remove(callback);
		};

		protected void onAddState(PreviewStateCallback callback){};
		public abstract Surface getSurface();
		public abstract Size getSize();
		public abstract void setCameraSize(Size size, boolean rotated);
		public abstract Class getPreviewClass();
	}

	private final String cameraId;
	private final CameraCharacteristics characteristics;
	private final Context context;
	private final ConcurrentLinkedQueue<ShootCallback> shootQueue = new ConcurrentLinkedQueue<>();

	private CameraDevice cameraDevice = null;
	private CameraCaptureSession session = null;
	private StateCallback stateCallback = null;
	private boolean opened = false;
	private Preview preview = null;
	private ImageReader imageReader = null;
	private final PreviewStateCallback startPreviewCallback = new PreviewStateCallback(){
		@Override
		public void onSurfaceReady(@NonNull Preview preview){
			Log.e("TEMP", "DEBUG SurfaceHolder.Callback onSurfaceReady");
			try {
				CaptureRequest.Builder previewCaptureRequest = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

				previewCaptureRequest.addTarget(preview.getSurface());

				session.setRepeatingRequest(previewCaptureRequest.build(), null, null);
			} catch (CameraAccessException e) {
				e.printStackTrace();
			}
		}
		
		@Override
		public void onSurfaceCreated(@NonNull Preview preview) {}
		@Override
		public void onSurfaceDestroyed(@NonNull Preview preview) {}
	};

	public static CameraAccessException cameraInUseException(){
		int code;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			code = CameraAccessException.CAMERA_IN_USE;
		} else {
			code = CameraAccessException.CAMERA_ERROR;
		}

		return new CameraAccessException(code);
	}

	public Camera2(@NonNull String cameraId, @NonNull Context context) throws CameraAccessException{
		this.cameraId = cameraId;
		this.context = context;

		CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
		characteristics = cameraManager.getCameraCharacteristics(cameraId);
	}
	
	
	public void setPreview(Preview preview){
		this.preview = preview;
	}

	private int displayRotation(){
		return ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
	}

	public boolean rotated(){
		return rotated(displayRotation());
	}

	public boolean rotated(int orientation){
		int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
		int rotateOrientation = Math.abs(sensorOrientation - orientation);

		return rotateOrientation == 90 || rotateOrientation == 270;
	}

	private void setPreviewCameraSize() throws CameraAccessException{
		if(preview == null) return;

		Size[] sizes;
		int screenOrientation = displayRotation();
		sizes = getSupportedSizes(preview.getPreviewClass(), screenOrientation);

		Size previewSize = preview.getSize();
		Size minPreviewSize = null;
		for(Size size : sizes){
			if(size.getWidth() < previewSize.getWidth() || size.getHeight() < previewSize.getHeight()) continue;

			if(minPreviewSize == null) {
				minPreviewSize = size;
				continue;
			}

			if(minPreviewSize.getWidth() * minPreviewSize.getHeight() > size.getWidth() * size.getHeight()) minPreviewSize = size;
		}

		if(minPreviewSize == null){
			// throw new Exception("Unsupported preview size");
			Log.e("TMP", "Unsupported preview size");
			return;
		}

		preview.setCameraSize(minPreviewSize, rotated(screenOrientation));
	}

	public void setPicture(@NonNull Size size){
		imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 5);

		imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener(){
			public void onImageAvailable(ImageReader reader){
				Image image = reader.acquireLatestImage();
	
				ShootCallback callback = shootQueue.poll();
				if(callback != null){
					callback.onShoot(image);
				}
	
				image.close();
			}
		}, null);
	}

	public void open(@NonNull StateCallback stateCallback){
		if(opened){
			stateCallback.onError(CAMERA_ACCESS_ERROR, Camera2.cameraInUseException().getMessage());
			return;
		}

		this.stateCallback = stateCallback;
		if(preview != null){
			preview.addStateCallback(new PreviewStateCallback(){
				@Override
				public void onSurfaceCreated(@NonNull Preview preview) {
					try {
						preview.removeStateCallback(this);
						setPreviewCameraSize();
						openCamera();
					} catch (CameraAccessException e) {
						e.printStackTrace();
						stateCallback.onError(CAMERA_ACCESS_ERROR, e.getMessage());
					}
				}

				@Override
				public void onSurfaceDestroyed(@NonNull Preview preview) {}
			});
		} else{
			openCamera();
		}
		opened = true;
	}

	private void openCamera(){
		if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			stateCallback.onError(OPEN_FAILED_ERROR, "Permission not granted");
			return;
		}
		try{
			CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
			cameraManager.openCamera(cameraId, new CameraDevice.StateCallback(){
				@Override
				public void onOpened(@NonNull CameraDevice _cameraDevice) {
					cameraDevice = _cameraDevice;
					try {
						Log.e("TEMP", "DEBUG CameraDevice.StateCallback onOpened");
						createSession();
					} catch (CameraAccessException e) {
						stateCallback.onError(CAMERA_ACCESS_ERROR, e.getMessage());
					}
				}
		
				@Override
				public void onDisconnected(@NonNull CameraDevice cameraDevice) {
					Log.e("TEMP", "DEBUG CameraDevice.StateCallback onDisconnected");
					close();
				}
		
				@Override
				public void onClosed(@NonNull CameraDevice cameraDevice) {
					Log.e("TEMP", "DEBUG CameraDevice.StateCallback onClosed");
					stateCallback.onClose();
				}
		
				@Override
				public void onError(@NonNull CameraDevice cameraDevice, int i) {
					Log.e("TEMP", "DEBUG CameraDevice.StateCallback onError");
					String message = "Unknown error";
		
					switch (i) {
						case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
							message = "Camera device has encountered a fatal error";
							break;
						case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
							message = "Camera device could not be opened due to a device policy";
							break;
						case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
							message = "Camera device is in use already";
							break;
						case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
							message = "Camera service has encountered a fatal error";
							break;
						case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
							message = "Camera device could not be opened because there are too many other open camera devices";
							break;
					}
		
					stateCallback.onError(OPEN_FAILED_ERROR, message);
					close();
				}
			}, null);
		} catch (CameraAccessException e) {
			stateCallback.onError(CAMERA_ACCESS_ERROR, e.getMessage());
		}
	}

	public void close() {
		preview.removeStateCallback(startPreviewCallback);
		cameraDevice.close();
	}

	private void createSession() throws CameraAccessException {
		ArrayList<Surface> targets = new ArrayList<>(2);
		if(preview != null){
			targets.add(preview.getSurface());
		}
		if(imageReader != null){
			targets.add(imageReader.getSurface());
		}

		cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
			@Override
			public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
				Log.e("TEMP", "DEBUG CameraCaptureSession.StateCallback onConfigured");
				session = cameraCaptureSession;
				try {
					stateCallback.onOpen();
				} catch (CameraAccessException e) {
					stateCallback.onError(CAMERA_ACCESS_ERROR, e.getMessage());
					close();
				}
			}

			@Override
			public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
				Log.e("TEMP", "DEBUG CameraCaptureSession.StateCallback onConfigureFailed");
				stateCallback.onError(SESSION_CONFIGURATION_ERROR, "Session configuration error");
				close();
			}

			@Override
			public void onClosed (CameraCaptureSession _session){
				Log.e("TEMP", "DEBUG CameraCaptureSession.StateCallback onClosed");
				if(_session == session) session = null;
			}
		}, null);
	}

	public void startPreview() throws CameraAccessException{
		if(preview == null){
			return;
		}
		preview.addStateCallback(startPreviewCallback);
	}

	public <T> Size[] getSupportedSizes(T format, int orientation) throws CameraAccessException {
		StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

		Size[] sizes = null;
		if(format instanceof Class) {
			sizes = configs.getOutputSizes((Class) format);
		}
		if(format instanceof Integer){
			sizes = configs.getOutputSizes((int)format);
		}

		if(sizes == null){
			sizes = new Size[0];
		}
		
		Size[] retSizes;
		if(rotated(orientation)){
			retSizes = new Size[sizes.length];
			for(int i = 0; i < sizes.length; i++){
				Size size = sizes[i];
				retSizes[i] = new Size(size.getHeight(), size.getWidth());
			}
		} else {
			retSizes = sizes;
		}

		return retSizes;
	}

	public void takePicture(ShootCallback shootCallback) {
		if(session == null){
			shootCallback.onError("TODO", "TODO");
			return;
		}

		try{
			CaptureRequest.Builder singleRequest =
				session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			singleRequest.addTarget(imageReader.getSurface());

			session.capture(singleRequest.build(), new CameraCaptureSession.CaptureCallback() {
				public void onCaptureFailed (CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
					shootCallback.onError("TODO", "TODO");
				}

				public void onCaptureCompleted (CameraCaptureSession session, CaptureRequest request, TotalCaptureResult totalResult){
					shootQueue.add(shootCallback);
				}
			}, null);
		} catch(CameraAccessException e){
			shootCallback.onError(CAMERA_ACCESS_ERROR, e.getMessage());
		}
	}

}
