package net.yudichev.jiotty.common.geo;

/// How finely a latitude or longitude, in degrees, is kept when it leaves the process.
public enum CoordinatePrecision {
    /// A tenth of a degree: about 11 km of latitude.
    ONE_DECIMAL_PLACE(10.0),
    /// A hundredth of a degree: about 1.1 km of latitude.
    TWO_DECIMAL_PLACES(100.0);

    private final double stepsPerDegree;

    CoordinatePrecision(double stepsPerDegree) {
        this.stepsPerDegree = stepsPerDegree;
    }

    /// A tie goes towards positive infinity, as in [Math#round(double)]. A value that rounds to zero gives positive zero. A NaN or infinite `coordinate` is
    /// returned as it is.
    ///
    /// @return `coordinate` rounded to this precision
    public double round(double coordinate) {
        if (!Double.isFinite(coordinate)) {
            return coordinate;
        }
        return Math.round(coordinate * stepsPerDegree) / stepsPerDegree;
    }
}
