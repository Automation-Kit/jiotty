package net.yudichev.jiotty.common.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatLonRectangleTest {
    @Test
    void intersectsFromAllSides() {
        LatLonRectangle base = new LatLonRectangle(0, 10, 0, 10);
        assertThat(base.intersectsWith(new LatLonRectangle(5, 15, 2, 8))).isTrue();
        assertThat(base.intersectsWith(new LatLonRectangle(-5, 5, 2, 8))).isTrue();
        assertThat(base.intersectsWith(new LatLonRectangle(2, 8, 5, 15))).isTrue();
        assertThat(base.intersectsWith(new LatLonRectangle(2, 8, -5, 5))).isTrue();
    }

    @Test
    void doesNotIntersectFromAllSides() {
        LatLonRectangle base = new LatLonRectangle(0, 10, 0, 10);
        assertThat(base.intersectsWith(new LatLonRectangle(11, 20, 0, 10))).isFalse();
        assertThat(base.intersectsWith(new LatLonRectangle(-20, -1, 0, 10))).isFalse();
        assertThat(base.intersectsWith(new LatLonRectangle(0, 10, 11, 20))).isFalse();
        assertThat(base.intersectsWith(new LatLonRectangle(0, 10, -20, -1))).isFalse();
    }

    @Test
    void equalRectanglesIntersect() {
        LatLonRectangle base = new LatLonRectangle(0, 10, 0, 10);
        assertThat(base.intersectsWith(new LatLonRectangle(0, 10, 0, 10))).isTrue();
    }
}
