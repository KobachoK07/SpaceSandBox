package spacesb.remake.planets;

public class M60UDS1 extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}};

    @Override public String getName() { return "M60-UDS1"; }
    @Override public String getType() { return "Hole"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/blackholegui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/sa.png"; }
    @Override public double getRadius() { return 50000.0f; }
    @Override public double getMass() { return 6.99e12; }
    @Override public float getTilt() { return 0f; }
    @Override public double getRotationPeriod() { return 0; }
    @Override public double getTemperature() { return 0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
}