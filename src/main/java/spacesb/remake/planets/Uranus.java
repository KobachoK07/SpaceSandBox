package spacesb.remake.planets;

public class Uranus extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4}, {0, 0, 4, 4}, {4, 4, 8, 8}, {0, 4, 4, 8}, {4, 12, 0, 8}, {8, 0, 12, 4}};

    @Override public String getName() { return "Uranus"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/uranusgui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/uranus.png"; }
    @Override public double getRadius() { return 20.128f; }
    @Override public double getMass() { return 14.53; }
    @Override public float getTilt() { return 97.77f; }
    @Override public double getRotationPeriod() { return 17.24; }
    @Override public double getTemperature() { return -195.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
}