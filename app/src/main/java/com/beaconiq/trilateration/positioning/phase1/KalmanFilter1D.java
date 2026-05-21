/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/KalmanFilter1D.java
 * Lineas: 1-25
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase I — modelo original de TEDtour, fiel 1:1
 */
package com.beaconiq.trilateration.positioning.phase1;

public class KalmanFilter1D {
    private final double q;
    private final double r;
    private Double x = null;
    private double p = 1.0;

    public KalmanFilter1D(double q, double r) {
        this.q = q;
        this.r = r;
    }

    public double update(double z) {
        if (x == null) {
            x = z;
            return z;
        }
        p += q;
        double k = p / (p + r);
        x = x + k * (z - x);
        p = (1 - k) * p;
        return x;
    }
}
