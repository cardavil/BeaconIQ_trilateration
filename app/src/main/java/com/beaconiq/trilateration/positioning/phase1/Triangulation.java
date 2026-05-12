/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/util/Triangulation.java
 * Lineas: 1-75
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase I — modelo original de TEDtour, fiel 1:1
 */
package com.beaconiq.trilateration.positioning.phase1;

import java.util.HashMap;

public class Triangulation {

    // Define the position of the single iBeacon
    private static final double[] beaconPosition = {0, 0}; // Assuming the beacon is located at the origin (0, 0)

    // Method to calculate the distance between the cellphone and the beacon
    public static double calculateDistance(double rssi) {
        // Convert RSSI value to distance using a calibration model (you may need to adjust this)
        double distance = Math.pow(10, ((-59 - rssi) / (10 * 2))); // Assuming txPower is -59 dBm and path loss exponent is 2
        return distance;
    }

    // Method to calculate the position of the cellphone based on distance from the beacon
    public static double[] calculatePosition(double distance) {
        // Since we only have one beacon, the position of the cellphone is the same as the beacon's position
        return beaconPosition;
    }

    // Method to perform triangulation (not needed for single beacon)
    // Keeping it for consistency in case you expand the class in the future
    public static double[] performTriangulation(HashMap<String, Double> distances) {
        // Triangulation is not needed for a single beacon
        // Return null or handle accordingly based on your requirements
        return null;
    }

}
