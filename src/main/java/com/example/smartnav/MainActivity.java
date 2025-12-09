package com.example.smartnav;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements SensorEventListener, GLSurfaceView.Renderer {

    // ===========================================================
    // SHADER 1: BACKGROUND CAMERA
    // ===========================================================
    private static final String VERTEX_SHADER =
            "attribute vec4 vPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "   gl_Position = vPosition;\n" +
                    "   vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}";

    // ===========================================================
    // SHADER 2: POINT CLOUD (GREEN DOTS)
    // ===========================================================
    private static final String POINT_VERTEX_SHADER =
            "uniform mat4 u_ModelViewProjection;\n" +
                    "attribute vec4 a_Position;\n" +
                    "varying float v_confidence;\n" +
                    "void main() {\n" +
                    "    gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);\n" +
                    "    v_confidence = a_Position.w;   // Pass confidence to fragment shader\n" +
                    "    gl_PointSize = 22.0;\n" +
                    "}";

    private static final String POINT_FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "varying float v_confidence;\n" +
                    "void main() {\n" +
                    "    float dist = length(gl_PointCoord - vec2(0.5));\n" +
                    "    float alpha = smoothstep(0.5, 0.2, dist);\n" +
                    "\n" +
                    "    // Map confidence → color\n" +
                    "    vec3 low = vec3(1.0, 0.0, 0.0);     // Red\n" +
                    "    vec3 mid = vec3(1.0, 1.0, 0.0);     // Yellow\n" +
                    "    vec3 high = vec3(0.0, 1.0, 0.0);    // Green\n" +
                    "\n" +
                    "    vec3 color;\n" +
                    "    if (v_confidence < 0.5) {\n" +
                    "       color = mix(low, mid, v_confidence * 2.0);\n" +
                    "    } else {\n" +
                    "       color = mix(mid, high, (v_confidence - 0.5) * 2.0);\n" +
                    "    }\n" +
                    "\n" +
                    "    gl_FragColor = vec4(color, alpha);\n" +
                    "}";


    // OpenGL State
    private int programId;
    private int textureId = -1;
    private int positionAttrib;
    private int texCoordAttrib;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private FloatBuffer transformedTexCoordBuffer;

    // Point Cloud State
    private int pointProgramId;
    private int pointPositionAttrib;
    private int pointMvpUniform;

    // Matrices
    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] viewProjectionMatrix = new float[16];

    // Quad coords
    private static final float[] QUAD_COORDS = {-1.0f, -1.0f, -1.0f, +1.0f, +1.0f, -1.0f, +1.0f, +1.0f};
    private static final float[] QUAD_TEX_COORDS = {0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f};

    // ===========================================================
    // SENSORS & APP VARS
    // ===========================================================
    private SensorManager sensorManager;
    private Sensor linearAccelerator, rotationVectorSensor;
    private float[] rotationMatrix = new float[16];
    private float[] position = new float[]{0, 0, 0};
    private float[] velocity = new float[]{0, 0, 0};
    private float[] phoneLinearAcceleration = new float[3];

    // --- TIMESTAMPS (FIXED) ---
    private long lastSensorTimestamp = 0;      // For DR (Blue Line)
    private long lastPointCloudTimestamp = 0;  // For SLAM Dots (Green Dots)

    private static final float NANO_TO_SEC = 1.0f / 1_000_000_000.0f;

    private Session arSession;
    private GLSurfaceView surfaceView;
    private boolean installRequested;
    private boolean isSlamInitialized = false;
    private float startAnchorX = 0, startAnchorZ = 0;

    // UI
    private PathView pathView;
    private TextView tvPositionX, tvSlamPos, tvStatus;
    private Button btnReset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pathView = findViewById(R.id.pathView);
        tvPositionX = findViewById(R.id.tv_position_x);
        tvSlamPos = findViewById(R.id.tv_slam_pos);
        tvStatus = findViewById(R.id.tv_status);
        btnReset = findViewById(R.id.btn_reset);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        linearAccelerator = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        surfaceView = findViewById(R.id.surfaceview);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        btnReset.setOnClickListener(v -> resetPaths());

        // Init Buffers
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(QUAD_COORDS).position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(QUAD_TEX_COORDS).position(0);
        transformedTexCoordBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private void resetPaths() {
        lastSensorTimestamp = 0; // Reset Sensor time
        // Do NOT reset PointCloud timestamp, or it might flicker

        position = new float[]{0, 0, 0};
        velocity = new float[]{0, 0, 0};
        isSlamInitialized = false;
        pathView.resetPath();
        tvPositionX.setText("DR: 0.00, 0.00");
        tvSlamPos.setText("SLAM: 0.00, 0.00");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 0);
            return;
        }
        try {
            if (arSession == null) {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                arSession = new Session(this);
                Config config = new Config(arSession);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.BLOCKING);
                arSession.configure(config);
            }
            arSession.resume();
            surfaceView.onResume();
        } catch (Exception e) {
            tvStatus.setText("AR Error: " + e.getMessage());
        }

        if (linearAccelerator != null) sensorManager.registerListener(this, linearAccelerator, SensorManager.SENSOR_DELAY_FASTEST);
        if (rotationVectorSensor != null) sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (arSession != null) arSession.pause();
        surfaceView.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        }
    }

    // ==================== SENSOR LOGIC (DR - BLUE LINE) ====================
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        }
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            System.arraycopy(event.values, 0, phoneLinearAcceleration, 0, 3);
            if (lastSensorTimestamp != 0) {
                float dt = (event.timestamp - lastSensorTimestamp) * NANO_TO_SEC;
                float[] worldLinearAccel = new float[4];
                float[] phoneAccelVec = new float[]{phoneLinearAcceleration[0], phoneLinearAcceleration[1], phoneLinearAcceleration[2], 0};
                android.opengl.Matrix.multiplyMV(worldLinearAccel, 0, rotationMatrix, 0, phoneAccelVec, 0);

                velocity[0] += worldLinearAccel[0] * dt;
                velocity[1] += worldLinearAccel[1] * dt;
                position[0] += velocity[0] * dt;
                position[1] += velocity[1] * dt;

                runOnUiThread(() -> {
                    pathView.updateDrPosition(position[0], position[1]);
                    tvPositionX.setText(String.format("DR: %.2f, %.2f", position[0], position[1]));
                });
            }
            lastSensorTimestamp = event.timestamp; // Update SENSOR time only
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ==================== OPENGL RENDERING (SLAM + POINTS) ====================
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // 1. Camera Texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        // 2. Camera Program
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionAttrib = GLES20.glGetAttribLocation(programId, "vPosition");
        texCoordAttrib = GLES20.glGetAttribLocation(programId, "aTexCoord");

        // 3. Point Cloud Program
        pointProgramId = createProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER);
        pointPositionAttrib = GLES20.glGetAttribLocation(pointProgramId, "a_Position");
        pointMvpUniform = GLES20.glGetUniformLocation(pointProgramId, "u_ModelViewProjection");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        if (arSession != null) {
            Display display = getWindowManager().getDefaultDisplay();
            arSession.setDisplayGeometry(display.getRotation(), width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (arSession == null) return;

        try {
            if (textureId != -1) arSession.setCameraTextureName(textureId);
            Frame frame = arSession.update();
            Camera camera = frame.getCamera();

            // 1. DRAW CAMERA BACKGROUND
            GLES20.glUseProgram(programId);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionAttrib);

            frame.transformDisplayUvCoords(texCoordBuffer, transformedTexCoordBuffer);
            GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoordBuffer);
            GLES20.glEnableVertexAttribArray(texCoordAttrib);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttrib);
            GLES20.glDisableVertexAttribArray(texCoordAttrib);

            // 2. DRAW POINT CLOUD (GREEN DOTS)
            try (PointCloud pointCloud = frame.acquirePointCloud()) {

                GLES20.glUseProgram(pointProgramId);

                // Enable blending for glow effect
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);
                camera.getViewMatrix(viewMatrix, 0);
                Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

                GLES20.glUniformMatrix4fv(pointMvpUniform, 1, false, viewProjectionMatrix, 0);

                FloatBuffer points = pointCloud.getPoints();
                int numPoints = points.remaining() / 4;

                GLES20.glVertexAttribPointer(pointPositionAttrib, 4,
                        GLES20.GL_FLOAT, false, 4 * 4, points);

                GLES20.glEnableVertexAttribArray(pointPositionAttrib);

                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints);

                GLES20.glDisableVertexAttribArray(pointPositionAttrib);
                GLES20.glDisable(GLES20.GL_BLEND);
            }


            // 3. UPDATE UI (SLAM - RED LINE)
            TrackingState state = camera.getTrackingState();
            runOnUiThread(() -> tvStatus.setText("Status: " + state.toString()));

            if (state == TrackingState.TRACKING) {
                Pose pose = camera.getPose();
                float x = pose.tx();
                float z = pose.tz();

                if (!isSlamInitialized) {
                    startAnchorX = x;
                    startAnchorZ = z;
                    isSlamInitialized = true;
                }
                float plotX = x - startAnchorX;
                float plotY = -(z - startAnchorZ);

                runOnUiThread(() -> {
                    pathView.updateSlamPosition(plotX, plotY);
                    tvSlamPos.setText(String.format("SLAM: %.2f, %.2f", plotX, plotY));
                });
            }

        } catch (Exception e) {
            // Prevent crash
        }
    }

    private int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }
}