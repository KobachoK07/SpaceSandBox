package spacesb.remake.planets;

public class Jupiter extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {4, 12, 0, 8}, {12, 0, 8, 4}};

    @Override public String getName() { return "Jupiter"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/jupitergui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/jupiter.png"; }
    @Override public double getRadius() { return 56.739f; }
    @Override public double getMass() { return 317.8; }
    @Override public float getTilt() { return 3.13f; }
    @Override public double getRotationPeriod() { return 9.93; }
    @Override public double getTemperature() { return -110.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
}