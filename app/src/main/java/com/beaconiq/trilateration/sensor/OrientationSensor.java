package com.beaconiq.trilateration.sensor;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class OrientationSensor implements SensorEventListener {

    public interface OrientationListener {
        void onOrientationChanged(float azimuthDeg, float pitchDeg, float rollDeg);
    }

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private OrientationListener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    public void setListener(OrientationListener listener) {
        this.listener = listener;
    }

    public void start(Activity activity) {
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    public boolean isAvailable() {
        return rotationSensor != null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        if (azimuth < 0) azimuth += 360f;
        float pitch = (float) Math.toDegrees(orientationAngles[1]);
        float roll = (float) Math.toDegrees(orientationAngles[2]);

        if (listener != null) {
            listener.onOrientationChanged(azimuth, pitch, roll);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public static String getCardinalDirection(float azimuth) {
        if (azimuth >= 337.5f || azimuth < 22.5f) return "N";
        if (azimuth < 67.5f) return "NE";
        if (azimuth < 112.5f) return "E";
        if (azimuth < 157.5f) return "SE";
        if (azimuth < 202.5f) return "S";
        if (azimuth < 247.5f) return "SW";
        if (azimuth < 292.5f) return "W";
        return "NW";
    }

    public static String getTiltDescription(float pitch, float roll) {
        float threshold = 20f;
        if (Math.abs(pitch) < threshold && Math.abs(roll) < threshold) return "flat";
        if (pitch < -threshold) return "tilted forward";
        if (pitch > threshold) return "tilted back";
        if (roll > threshold) return "tilted right";
        if (roll < -threshold) return "tilted left";
        return "flat";
    }
}
