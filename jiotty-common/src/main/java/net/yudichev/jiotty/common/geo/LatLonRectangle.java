package net.yudichev.jiotty.common.geo;

/// A rectangle on the surface of the Earth, defined by its latitude and longitude bounds.
public record LatLonRectangle(double minLat, double maxLat, double minLon, double maxLon) {
    private static final double EARTH_RADIUS_METERS = 6371000;

    public boolean intersectsWith(LatLonRectangle another) {
        return another.maxLat >= minLat
               && another.minLat <= maxLat
               && another.maxLon >= minLon
               && another.minLon <= maxLon;
    }

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
