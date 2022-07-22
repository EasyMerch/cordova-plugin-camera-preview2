package ru.pronetcom.camerapreview2;

import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class SurfaceViewPreview extends Camera2.Preview{
	private final SurfaceView surfaceView;
	private final Size size;

	private boolean exists = false;
	private boolean needChange = false;

	public SurfaceViewPreview(@NonNull SurfaceView surfaceView, @NonNull Size size){
		this.size = size;
		this.surfaceView = surfaceView;
		// Surface.isValid

		surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override
			public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
				Log.e("TEMP", "DEBUG SurfaceHolder.Callback surfaceCreated");
				boolean isReadyStart = isReady();
				exists = true;
				if(!isReadyStart && isReady()) stateCallbacks.onSurfaceReady(SurfaceViewPreview.this);
				stateCallbacks.onSurfaceCreated(SurfaceViewPreview.this);
			}

			@Override
			public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
				Log.e("TEMP", "DEBUG SurfaceHolder.Callback surfaceChanged");
				boolean isReadyStart = isReady();
				needChange = false;
				if(!isReadyStart && isReady()) stateCallbacks.onSurfaceReady(SurfaceViewPreview.this);
			}

			@Override
			public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
				Log.e("TEMP", "DEBUG SurfaceHolder.Callback surfaceDestroyed");
				exists = false;
				stateCallbacks.onSurfaceDestroyed(SurfaceViewPreview.this);
			}
		});
	}

	@Override
	public Size getSize(){
		return size;
	};

	@Override
	public Surface getSurface(){
		return surfaceView.getHolder().getSurface();
	}

    @Override
	public void setCameraSize(@NonNull Size cameraSize, boolean rotated){
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(cameraSize.getWidth(), cameraSize.getHeight());
		surfaceView.setLayoutParams(params);
		if(rotated){
			surfaceView.getHolder().setFixedSize(cameraSize.getHeight(), cameraSize.getWidth());
		} else {
			surfaceView.getHolder().setFixedSize(cameraSize.getWidth(), cameraSize.getHeight());
		}
		surfaceView.setClipBounds(new Rect(0, 0, size.getWidth(), size.getHeight()));
		needChange = true;
	}

	@Override
	public Class getPreviewClass(){
		return SurfaceHolder.class;
	};

	protected void onAddState(Camera2.PreviewStateCallback callback){
		if(isReady()){
			callback.onSurfaceReady(this);
		}
	};

	private boolean isReady(){
		return exists && !needChange;
	}
}