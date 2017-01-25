package com.flurgle.camerakit;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.support.v4.util.SparseArrayCompat;
import android.view.SurfaceHolder;

import com.flurgle.camerakit.utils.Size;
import com.flurgle.camerakit.utils.YuvUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class Camera1 extends CameraViewImpl {

    private static final SparseArrayCompat<String> FLASH_MODES = new SparseArrayCompat<>();
    static {
        FLASH_MODES.put(CameraKit.Constants.FLASH_OFF, Camera.Parameters.FLASH_MODE_OFF);
        FLASH_MODES.put(CameraKit.Constants.FLASH_ON, Camera.Parameters.FLASH_MODE_ON);
        FLASH_MODES.put(CameraKit.Constants.FLASH_AUTO, Camera.Parameters.FLASH_MODE_AUTO);
    }

    private static File VIDEO_FILE;

    private int mCameraId;
    Camera mCamera;
    private Camera.Parameters mCameraParameters;
    private final Camera.CameraInfo mCameraInfo = new Camera.CameraInfo();

    private boolean mShowingPreview;
    private boolean mAutoFocus;
    private int mFacing;
    private int mFlash;
    private int mDisplayOrientation;

    private SortedSet<Size> mPreviewSizes;
    private SortedSet<Size> mCaptureSizes;

    private MediaRecorder mMediaRecorder;

    Camera1(CameraListener callback, PreviewImpl preview) {
        super(callback, preview);
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    setUpPreview();
                    adjustCameraParameters();
                }
            }
        });

        mPreviewSizes = new TreeSet<>();
        mCaptureSizes = new TreeSet<>();

        VIDEO_FILE = new File(getView().getContext().getExternalFilesDir(null), "video.mp4");;
    }

    @Override
    void start() {
        setFacingInternal(mFacing);
        openCamera();
        if (mPreview.isReady()) {
            setUpPreview();
        }
        mShowingPreview = true;
        mCamera.startPreview();
    }

    @Override
    void stop() {
        if (mCamera != null) {
            mCamera.stopPreview();
        }
        mShowingPreview = false;
        releaseCamera();
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        if (setFacingInternal(facing) && isCameraOpened()) {
            stop();
            start();
        }
    }

    private boolean setFacingInternal(int facing) {
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == facing) {
                mCameraId = i;
                mFacing = facing;
                return true;
            }
        }

        return false;
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    void setFlash(int flash) {
        if (flash == mFlash) {
            return;
        }
        if (setFlashInternal(flash)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    private boolean setFlashInternal(int flash) {
        if (isCameraOpened()) {
            List<String> modes = mCameraParameters.getSupportedFlashModes();
            String mode = FLASH_MODES.get(flash);
            if (modes != null && modes.contains(mode)) {
                mCameraParameters.setFlashMode(mode);
                mFlash = flash;
                return true;
            }
            String currentMode = FLASH_MODES.get(mFlash);
            if (modes == null || !modes.contains(currentMode)) {
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                mFlash = CameraKit.Constants.FLASH_OFF;
                return true;
            }
            return false;
        } else {
            mFlash = flash;
            return false;
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (autoFocus == mAutoFocus) {
            return;
        }
        if (setAutoFocusInternal(autoFocus)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    private boolean setAutoFocusInternal(boolean autoFocus) {
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedFocusModes();
            if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            } else {
                mCameraParameters.setFocusMode(modes.get(0));
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    boolean getAutoFocus() {
        if (!isCameraOpened()) {
            return mAutoFocus;
        }
        String focusMode = mCameraParameters.getFocusMode();
        return focusMode != null && focusMode.contains("continuous");
    }

    @Override
    void capturePicture() {
        if (!isCameraOpened()) {
            throw new IllegalStateException("Camera is not ready. Call start() before takePicture().");
        }
        if (getAutoFocus()) {
            mCamera.cancelAutoFocus();
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    takePictureInternal();
                }
            });
        } else {
            takePictureInternal();
        }
    }

    void takePictureInternal() {
        mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                getCameraListener().onPictureTaken(data);
                camera.startPreview();
            }
        });
    }

    @Override
    void captureStill() {
        mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                new Thread(new ProcessStillTask(data, camera, mCameraInfo, new ProcessStillTask.OnStillProcessedListener() {
                    @Override
                    public void onStillProcessed(final byte[] data) {
                        getView().post(new Runnable() {
                            @Override
                            public void run() {
                                getCameraListener().onPictureTaken(data);
                            }
                        });
                    }
                })).start();
            }
        });
    }

    static class ProcessStillTask implements Runnable {

        byte[] data;
        Camera camera;
        Camera.CameraInfo cameraInfo;
        OnStillProcessedListener onStillProcessedListener;

        public ProcessStillTask(byte[] data, Camera camera, Camera.CameraInfo cameraInfo, OnStillProcessedListener onStillProcessedListener) {
            this.data = data;
            this.camera = camera;
            this.cameraInfo = cameraInfo;
            this.onStillProcessedListener = onStillProcessedListener;
        }

        @Override
        public void run() {
            Camera.Parameters parameters = camera.getParameters();
            int width = parameters.getPreviewSize().width;
            int height = parameters.getPreviewSize().height;
            int rotation = cameraInfo.orientation;
            byte[] rotatedData = YuvUtils.rotateNV21(data, width, height, rotation);

            int postWidth;
            int postHeight;

            switch (rotation) {
                case 90:
                case 270:
                    postWidth = height;
                    postHeight = width;
                    break;

                case 0:
                case 180:
                default:
                    postWidth = width;
                    postHeight = height;
                    break;
            }

            YuvImage yuv = new YuvImage(rotatedData, parameters.getPreviewFormat(), postWidth, postHeight, null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, postWidth, postHeight), 50, out);

            onStillProcessedListener.onStillProcessed(out.toByteArray());
        }

        interface OnStillProcessedListener {
            void onStillProcessed(byte[] data);
        }

    }

    @Override
    void startVideo() {
        try {
            prepareMediaRecorder();
        } catch (IOException e) {
            if (mMediaRecorder != null) {
                mMediaRecorder.release();
            }

            return;
        }
    }

    void prepareMediaRecorder() throws IOException {
        mCamera.unlock();

        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));

        mMediaRecorder.setOutputFile(VIDEO_FILE.getAbsolutePath());
        mMediaRecorder.setOrientationHint(mCameraInfo.orientation);

        mMediaRecorder.prepare();
        mMediaRecorder.start();
    }

    @Override
    void endVideo() {
        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder = null;
        }

        getCameraListener().onVideoTaken(VIDEO_FILE);

    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        if (mDisplayOrientation == displayOrientation) {
            return;
        }
        mDisplayOrientation = displayOrientation;
        if (isCameraOpened()) {
            int cameraRotation = calcCameraRotation(displayOrientation);
            mCameraParameters.setRotation(cameraRotation);
            mCamera.setParameters(mCameraParameters);
            final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
            if (needsToStopPreview) {
                mCamera.stopPreview();
            }
            mCamera.setDisplayOrientation(cameraRotation);
            if (needsToStopPreview) {
                mCamera.startPreview();
            }
        }
    }

    private int calcCameraRotation(int rotation) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + rotation) % 360) % 360;
        } else {
            return (mCameraInfo.orientation - rotation + 360) % 360;
        }
    }

    private void setUpPreview() {
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
                if (needsToStopPreview) {
                    mCamera.stopPreview();
                }
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
                if (needsToStopPreview) {
                    mCamera.startPreview();
                }
            } else {
                mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void adjustCameraParameters() {
        SortedSet<Size> sizes = mPreviewSizes;
        Size previewSize = chooseOptimalSize(sizes);
        final Camera.Size currentSize = mCameraParameters.getPictureSize();
        if (currentSize.width != previewSize.getWidth() || currentSize.height != previewSize.getHeight()) {
            final Size pictureSize = mCaptureSizes.last();
            if (mShowingPreview) {
                mCamera.stopPreview();
            }

            mCameraParameters.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
            mPreview.setTruePreviewSize(previewSize.getWidth(), previewSize.getHeight());

            mCameraParameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());

            // TODO: fix this
            mCameraParameters.setRotation(calcCameraRotation(mDisplayOrientation) + (mFacing == CameraKit.Constants.FACING_FRONT ? 180 : 0));

            setAutoFocusInternal(mAutoFocus);
            setFlashInternal(mFlash);
            mCamera.setParameters(mCameraParameters);
            if (mShowingPreview) {
                mCamera.startPreview();
            }
        }
    }

    private Size chooseOptimalSize(SortedSet<Size> sizes) {
        if (!mPreview.isReady()) { // Not yet laid out
            return sizes.last(); // Return the smallest size
        }

        int desiredWidth;
        int desiredHeight;
        final int surfaceWidth = mPreview.getWidth();
        final int surfaceHeight = mPreview.getHeight();
        if (mDisplayOrientation == 90 || mDisplayOrientation == 270) {
            desiredWidth = surfaceHeight;
            desiredHeight = surfaceWidth;
        } else {
            desiredWidth = surfaceWidth;
            desiredHeight = surfaceHeight;
        }
        Size result = null;
        for (Size size : sizes) { // Iterate from small to large
            if (desiredWidth <= size.getWidth() && desiredHeight <= size.getHeight()) {
                return size;

            }
            result = size;
        }
        return result;
    }

    private void openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }
        mCamera = Camera.open(mCameraId);
        mCameraParameters = mCamera.getParameters();
        // Supported preview sizes
        mPreviewSizes.clear();
        for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
            mPreviewSizes.add(new Size(size.width, size.height));
        }
        // Supported picture sizes;
        mCaptureSizes.clear();
        for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
            mCaptureSizes.add(new Size(size.width, size.height));
        }

        adjustCameraParameters();
        mCamera.setDisplayOrientation(calcCameraRotation(mDisplayOrientation));

        getCameraListener().onCameraOpened();
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            getCameraListener().onCameraClosed();
        }
    }

}