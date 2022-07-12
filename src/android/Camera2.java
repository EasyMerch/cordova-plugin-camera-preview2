package ru.pronetcom.camerapreview2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class Camera2 {
	public final static String OPEN_FAILED_ERROR = "OPEN_FAILED_ERROR";
	public final static String SESSION_CONFIGURATION_ERROR = "SESSION_CONFIGURATION_ERROR";
	public final static String CAMERA_ACCESS_ERROR = "CAMERA_ACCESS_ERROR";

	private boolean isOpen = false;

	private CameraCaptureSession session;
	private SurfaceView previewSurfaceView;
	private ImageReader imageReader;
	private StateCallback stateCallback;
	private CameraDevice cameraDevice;
	private ConcurrentLinkedQueue<ShootCallback> shootQueue = new ConcurrentLinkedQueue();

	public static String cameraAccessExceptionMessage(CameraAccessException e){
		String message = "";

		switch(e.getReason()){
			case CameraAccessException.CAMERA_DISABLED:
				message = "Camera is disabled due to a device policy, and cannot be opened";
				break;
			case CameraAccessException.CAMERA_DISCONNECTED:
				message = "Camera disconnected";
				break;
			case CameraAccessException.CAMERA_ERROR:
				message = "Camera device is currently in the error state.";
				break;
			case CameraAccessException.CAMERA_IN_USE:
				message = "Camera device is in use already";
				break;
			case CameraAccessException.MAX_CAMERAS_IN_USE:
				message = "Limit of active camera devices has been reached";
				break;
		}

		return message;
	}

	public static abstract class StateCallback {
		public void handleCameraAccessException(CameraAccessException e){
			this.onError(CAMERA_ACCESS_ERROR, Camera2.cameraAccessExceptionMessage(e));
		}

		public abstract void onError(String code, String message);
		public abstract void onOpen();
		public abstract void onClose();
	}

	public static abstract class ShootCallback {
		public void handleCameraAccessException(CameraAccessException e){
			this.onError(CAMERA_ACCESS_ERROR, Camera2.cameraAccessExceptionMessage(e));
		}

		public abstract void onError(String code, String message);
		public abstract void onShoot(Image image);
	}

	public static CameraAccessException cameraInUseException(){
		int code;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			code = CameraAccessException.CAMERA_IN_USE;
		} else {
			code = CameraAccessException.CAMERA_ERROR;
		}

		return new CameraAccessException(code);
	}

	public Camera2(SurfaceView _previewSurfaceView, ImageReader _imageReader) {
		previewSurfaceView = _previewSurfaceView;
		imageReader = _imageReader;

		imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener(){
			public void onImageAvailable(ImageReader reader){
				Log.e("TEMP", "onImageAvailable");
				Image image = reader.acquireLatestImage();

				ShootCallback callback = shootQueue.poll();
				if(callback != null){
					callback.onShoot(image);
				}

				image.close();
			}
		},null);
	}

	public void open(Context context, String cameraId, StateCallback _callback) {
		if(this.isOpen()){
			_callback.handleCameraAccessException(Camera2.cameraInUseException());
			return;
		}

		stateCallback = _callback;

		try {
			openCamera(context, cameraId);
		} catch (CameraAccessException e) {
			stateCallback.handleCameraAccessException(e);
		}
	}

	private void openCamera(Context context, String cameraId) throws CameraAccessException{
		if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			stateCallback.onError(OPEN_FAILED_ERROR, "Permission not granted");
			return;
		}
		isOpen = true;

		CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
		cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {

			@Override
			public void onOpened(@NonNull CameraDevice _cameraDevice) {
				cameraDevice = _cameraDevice;
				try{
					createSession(cameraDevice);
				} catch(CameraAccessException e){
					stateCallback.handleCameraAccessException(e);
				}
			}

			@Override
			public void onDisconnected(@NonNull CameraDevice cameraDevice) {
				cameraDevice.close();
			}

			@Override
			public void onClosed(@NonNull CameraDevice cameraDevice) {
				isOpen = false;
				stateCallback.onClose();
			}

			@Override
			public void onError(@NonNull CameraDevice cameraDevice, int i) {
				cameraDevice.close();
				
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
			}
		}, null);
	}

	private void createSession(CameraDevice cameraDevice) throws CameraAccessException{
		List<Surface> targets = Arrays.asList(previewSurfaceView.getHolder().getSurface(), imageReader.getSurface());

		cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
			@Override
			public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
				try {
					startSession(cameraCaptureSession);
				} catch (CameraAccessException e) {
					stateCallback.handleCameraAccessException(e);
					cameraDevice.close();
				}
			}

			@Override
			public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
				stateCallback.onError(SESSION_CONFIGURATION_ERROR, "Session configuration error");
				cameraDevice.close();
			}
		}, null);
	}

	private void startSession(@NonNull CameraCaptureSession cameraCaptureSession) throws CameraAccessException{
		session = cameraCaptureSession;

		CaptureRequest.Builder previewCaptureRequest =
				session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
		previewCaptureRequest.addTarget(previewSurfaceView.getHolder().getSurface());

		session.setRepeatingRequest(previewCaptureRequest.build(), null, null);

		stateCallback.onOpen();
	}

	public void shoot(ShootCallback shootCallback) {
		try{
			CaptureRequest.Builder singleRequest =
				session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			singleRequest.addTarget(imageReader.getSurface());

			session.capture(singleRequest.build(), new CameraCaptureSession.CaptureCallback() {
				public void onCaptureFailed (CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
					shootCallback.onError("TODO", "TODO");
				}

				public void onCaptureCompleted (CameraCaptureSession session, CaptureRequest request, TotalCaptureResult totalResult){
					Log.e("TEMP", "onCaptureCompleted");
					shootQueue.add(shootCallback);
				}
			}, null);
		} catch(CameraAccessException e){
			shootCallback.handleCameraAccessException(e);
		}
	}

	public void close(){
		cameraDevice.close();
	}

	public boolean isOpen(){
		return isOpen;
	}
}
