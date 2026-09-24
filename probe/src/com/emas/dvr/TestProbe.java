package com.emas.dvr;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestProbe {
    public static void main(String[] args) {
        System.out.println("=== EMAS DVR CAMERA 0 YUV TEST START ===");
        try {
            Looper.prepareMainLooper();
        } catch (Throwable t) {}

        HandlerThread thread = new HandlerThread("CameraBackground");
        thread.start();
        Handler bgHandler = new Handler(thread.getLooper());

        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method sysMainMethod = atClass.getMethod("systemMain");
            Object atObj = sysMainMethod.invoke(null);
            Method getSysCtxMethod = atClass.getMethod("getSystemContext");
            Context context = (Context) getSysCtxMethod.invoke(atObj);

            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            File outputFile = new File("/storage/usbotg-otg1/DVR/probe_camera0.yuv");
            if (outputFile.exists()) {
                outputFile.delete();
            }

            // Test ImageFormat.YUV_420_888 (format 35) at 1280x720
            ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2);
            CountDownLatch frameLatch = new CountDownLatch(1);

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    System.out.println("FRAME RECEIVED on ImageReader (YUV_420_888)!");
                    try (Image image = r.acquireLatestImage()) {
                        if (image != null) {
                            Image.Plane yPlane = image.getPlanes()[0];
                            ByteBuffer buffer = yPlane.getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                fos.write(bytes);
                            }
                            System.out.println("Saved Y plane to " + outputFile.getAbsolutePath() + " (" + bytes.length + " bytes)");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    frameLatch.countDown();
                }
            }, bgHandler);

            cm.openCamera("0", new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    System.out.println("Camera 0 opened. Creating CaptureSession with YUV ImageReader...");
                    try {
                        camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                System.out.println("Session configured! Sending capture request...");
                                try {
                                    CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    builder.addTarget(reader.getSurface());
                                    session.capture(builder.build(), null, bgHandler);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    frameLatch.countDown();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                System.out.println("ERROR: Session configuration failed!");
                                frameLatch.countDown();
                            }
                        }, bgHandler);
                    } catch (Exception e) {
                        e.printStackTrace();
                        camera.close();
                        frameLatch.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    System.out.println("Camera 0 disconnected.");
                    frameLatch.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    System.out.println("ERROR: Camera 0 error=" + error);
                    frameLatch.countDown();
                }
            }, bgHandler);

            boolean gotFrame = frameLatch.await(10, TimeUnit.SECONDS);
            if (!gotFrame) {
                System.out.println("Timeout waiting for YUV frame.");
            } else {
                System.out.println("YUV Capture completed!");
            }

            if (outputFile.exists()) {
                System.out.println("SUCCESS! Output YUV size: " + outputFile.length() + " bytes");
            } else {
                System.out.println("YUV Image file not created.");
            }

            reader.close();
            thread.quitSafely();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
        System.out.println("=== EMAS DVR CAMERA 0 YUV TEST END ===");
        System.exit(0);
    }
}
