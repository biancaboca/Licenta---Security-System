package com.example.tryagain;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfRect;
import org.opencv.face.LBPHFaceRecognizer;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    public static boolean apduServiceEnabled = false;

    PreviewView cameraView;
    CameraSelector cameraSelector;
    File cascFile;
    LBPHFaceRecognizer lbphFaceRecognizer;
    private boolean isFaceRecognized = false;
    private int recognizedFrameCount = 0;
    private static final int FRAMES_BEFORE_RETRAINING = 10;

    CascadeClassifier faceDetector;

    private ExecutorService executorService;

    static {
        if (OpenCVLoader.initDebug()) {
            Log.d("MainActivity", "OpenCV loaded successfully");
        } else {
            Log.d("MainActivity", "OpenCV initialization failed");
        }
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.cameraView);
        executorService = Executors.newSingleThreadExecutor();

        cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        if (!OpenCVLoader.initDebug()) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, baseCallback);
        } else {
            baseCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        Button verifyIDButton = findViewById(R.id.verifyIDButton);
        verifyIDButton.setOnClickListener(v -> {
            if (allPermissionsGranted()) {
                loadFaceRecognizer();
                startCameraFeed();
            } else {
                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{Manifest.permission.CAMERA},
                        CAMERA_PERMISSION_REQUEST_CODE
                );
            }
        });

        Button buttonOpenImageSelection = findViewById(R.id.button_open_image_selection);
        buttonOpenImageSelection.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ImageSelectionActivity.class);
            startActivity(intent);
        });
    }

    private void startCameraFeed() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();

                preview.setSurfaceProvider(cameraView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executorService, this::analyzeImage);

                cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy image) {
        Bitmap bitmap = convertImageProxyToBitmap(image);
        executorService.execute(() -> processFrame(bitmap));
        image.close();
    }

    private void processFrame(Bitmap bitmap) {
        Mat mat = new Mat();
        Utils.bitmapToMat(bitmap, mat);

        MatOfRect faceDetections = new MatOfRect();
        faceDetector.detectMultiScale(mat, faceDetections);

        for (org.opencv.core.Rect rect : faceDetections.toArray()) {
            Mat face = new Mat(mat, rect);
            Imgproc.cvtColor(face, face, Imgproc.COLOR_RGB2GRAY);

            int[] label = new int[1];
            double[] confidence = new double[1];
            lbphFaceRecognizer.predict(face, label, confidence);

            final String confidenceText = String.format("Confidence: %.2f", confidence[0]);

            if (confidence[0] <= 35.0) {
                isFaceRecognized = true;
                apduServiceEnabled = true;
                recognizedFrameCount++;

                // Save the recognized frame
                saveRecognizedFrame(bitmap);

                if (recognizedFrameCount >= FRAMES_BEFORE_RETRAINING) {
                    executorService.execute(this::retrainFaceRecognizer);
                    recognizedFrameCount = 0;
                }

                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Face recognized: " + confidenceText, Toast.LENGTH_SHORT).show());
            } else {
                isFaceRecognized = false;
                apduServiceEnabled = false;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Face not recognized: " + confidenceText, Toast.LENGTH_SHORT).show());
            }

            Log.d("FaceRecognition", confidenceText);
        }
    }

    @SuppressLint("RestrictedApi")
    private Bitmap convertImageProxyToBitmap(ImageProxy imageProxy) {
        ByteBuffer yBuffer = imageProxy.getPlanes()[0].getBuffer();
        ByteBuffer uvBuffer = imageProxy.getPlanes()[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uvSize = uvBuffer.remaining();
        byte[] nv21 = new byte[ySize + uvSize];
        yBuffer.get(nv21, 0, ySize);
        uvBuffer.get(nv21, ySize, uvSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);

        byte[] jpegBytes = out.toByteArray();
        Bitmap originalBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        Matrix matrix = new Matrix();

        if (cameraSelector.getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
            matrix.preScale(-1.0f, 1.0f);
        }

        int adjustedRotation = (cameraSelector.getLensFacing() == CameraSelector.LENS_FACING_FRONT) ?
                (360 - rotationDegrees) % 360 : rotationDegrees;

        matrix.postRotate(adjustedRotation);

        return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraFeed();
        } else {
            Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private BaseLoaderCallback baseCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            if (status == LoaderCallbackInterface.SUCCESS) {
                InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalface_alt2);
                File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                cascFile = new File(cascadeDir, "haarcascade_frontalface_alt2.xml");
                try (FileOutputStream fos = new FileOutputStream(cascFile); InputStream inputStream = is) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    faceDetector = new CascadeClassifier(cascFile.getAbsolutePath());
                    if (faceDetector.empty()) {
                        Log.d("MainActivity", "Failed to load cascade classifier");
                        faceDetector = null;
                    } else {
                        Log.d("MainActivity", "Cascade classifier loaded");
                    }
                } catch (IOException e) {
                    Log.e("MainActivity", "Failed to load cascade. Exception: " + e.getMessage());
                }
            } else {
                super.onManagerConnected(status);
            }
        }
    };

    private void loadFaceRecognizer() {
        lbphFaceRecognizer = LBPHFaceRecognizer.create(1, 8, 8, 8, 100);
        try {
            String modelPath = getFilesDir() + "/lbph_face_recognizer_model.xml";
            lbphFaceRecognizer.read(modelPath);
        } catch (Exception e) {
            Log.e("FaceRecognizer", "Failed to load face recognizer model. Exception: " + e.getMessage());
            lbphFaceRecognizer = null;
        }
    }

    private void saveRecognizedFrame(Bitmap bitmap) {
        File storageDir = new File(getFilesDir(), "RecognizedFaces");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File imageFile = new File(storageDir, "RECOGNIZED_" + timeStamp + ".jpg");

        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            Log.d("MainActivity", "Recognized frame saved: " + imageFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e("MainActivity", "Error saving recognized frame", e);
        }
    }

    private void retrainFaceRecognizer() {
        File oldFacesFolder = new File(getFilesDir(), "CroppedFaces");
        File newFacesFolder = new File(getFilesDir(), "RecognizedFaces");

        List<Mat> faceImages = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();

        int label = 0;

        // Load old faces
        File[] oldFaceFiles = oldFacesFolder.listFiles();
        if (oldFaceFiles != null) {
            for (File faceFile : oldFaceFiles) {
                Bitmap faceBitmap = BitmapFactory.decodeFile(faceFile.getAbsolutePath());
                Mat faceMat = new Mat();
                Utils.bitmapToMat(faceBitmap, faceMat);
                Imgproc.cvtColor(faceMat, faceMat, Imgproc.COLOR_RGB2GRAY);
                faceImages.add(faceMat);
                labels.add(label);
            }
        }

        // Load new faces
        File[] newFaceFiles = newFacesFolder.listFiles();
        if (newFaceFiles != null) {
            for (File faceFile : newFaceFiles) {
                Bitmap faceBitmap = BitmapFactory.decodeFile(faceFile.getAbsolutePath());
                Mat faceMat = new Mat();
                Utils.bitmapToMat(faceBitmap, faceMat);
                Imgproc.cvtColor(faceMat, faceMat, Imgproc.COLOR_RGB2GRAY);
                faceImages.add(faceMat);
                labels.add(label);
            }
        }

        if (!faceImages.isEmpty()) {
            Mat[] faceImagesArray = faceImages.toArray(new Mat[0]);
            int[] labelsArray = labels.stream().mapToInt(i -> i).toArray();

            lbphFaceRecognizer = LBPHFaceRecognizer.create();
            lbphFaceRecognizer.setRadius(1);
            lbphFaceRecognizer.setNeighbors(8);
            lbphFaceRecognizer.setGridX(8);
            lbphFaceRecognizer.setGridY(8);

            lbphFaceRecognizer.train(Arrays.asList(faceImagesArray), new MatOfInt(labelsArray));

            String modelPath = getFilesDir() + "/lbph_face_recognizer_model.xml";
            lbphFaceRecognizer.save(modelPath);

            Log.d("FaceRecognizer", "Model retrained and saved at: " + modelPath);
            for (File file : newFaceFiles) {
                file.delete();
            }
        }
    }

    public boolean isApduServiceEnabled() {
        return apduServiceEnabled;
    }


    public void setApduServiceEnabled(boolean apduServiceEnabled) {
        this.apduServiceEnabled = apduServiceEnabled;
    }
}