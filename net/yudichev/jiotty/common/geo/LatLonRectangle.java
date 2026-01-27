package net.yudichev.jiotty.common.geo;

public record LatLonRectangle(double minLat, double maxLat, double minLon, double maxLon) {
    private static final double EARTH_RADIUS_METERS = 6371000;

    public static LatLonRectangle create(LatLon centre, double halfSideMetres) {
        double lat = Math.toRadians(centre.lat());
        double lon = Math.toRadians(centre.lon());

        double deltaLat = halfSideMetres / EARTH_RADIUS_METERS;
        double deltaLon = halfSideMetres / (EARTH_RADIUS_METERS * StrictMath.cos(lat));

        double minLat = Math.toDegrees(lat - deltaLat);
        double maxLat = Math.toDegrees(lat + deltaLat);
        double minLon = Math.toDegrees(lon - deltaLon);
        double maxLon = Math.toDegrees(lon + deltaLon);
        return new LatLonRectangle(minLat, maxLat, minLon, maxLon);
    }
}
