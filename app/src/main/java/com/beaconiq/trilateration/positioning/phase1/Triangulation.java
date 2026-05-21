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

    /*

    // Define the positions of the three iBeacons (assuming they form a equilateral triangle)
    private static final double[][] beaconPositions = {{0, 0}, {2, 0}, {1, Math.sqrt(3)}};

    // Method to calculate the distance between the cellphone and each beacon
    public static HashMap<String, Double> calculateDistances(double[] rssiValues) {
        HashMap<String, Double> distances = new HashMap<>();
        for (int i = 0; i < rssiValues.length; i++) {
            double distance = calculateDistanceFromRSSI(rssiValues[i]);
            distances.put("beacon" + (i + 1), distance);
        }
        return distances;
    }

    // Method to calculate distance from RSSI value (you may need to calibrate this based on your setup)
    private static double calculateDistanceFromRSSI(double rssi) {
        // A simple linear model to convert RSSI to distance (you may need to refine this based on your setup)
        double txPower = -59; // The transmitted power in dBm at 1 meter from the beacon
        return Math.pow(10, ((txPower - rssi) / (10 * 2))); // 2 is the path loss exponent
    }

    // Method to calculate the position of the cellphone using triangulation
    public static double[] calculatePosition(HashMap<String, Double> distances) {
        double[] position = new double[2];
        double sumX = 0, sumY = 0;
        for (String beacon : distances.keySet()) {
            int beaconIndex = Integer.parseInt(beacon.substring(6)) - 1;
            double[] beaconPosition = beaconPositions[beaconIndex];
            double distance = distances.get(beacon);
            sumX += beaconPosition[0] * distance;
            sumY += beaconPosition[1] * distance;
        }
        double totalDistance = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            totalDistance = distances.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        position[0] = sumX / totalDistance;
        position[1] = sumY / totalDistance;
        return position;
    }
    */

}
