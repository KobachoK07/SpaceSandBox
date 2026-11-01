package spacesb.remake.planets;

public class Mars extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {0, 8, 4, 12}, {8, 0, 12, 4}};

    @Override public String getName() { return "Mars"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/marsgui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/mars.png"; }
    @Override public double getRadius() { return 2.6901; }
    @Override public double getMass() { return 0.107; }
    @Override public float getTilt() { return 25.19f; }
    @Override public double getRotationPeriod() { return 24.623; }
    @Override public double getTemperature() { return -63.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}