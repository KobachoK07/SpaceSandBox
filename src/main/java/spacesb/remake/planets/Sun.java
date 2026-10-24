package spacesb.remake.planets;

public class Sun extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}, {0, 0, 16, 16}};

    @Override public String getName() { return "Sun"; }
    @Override public String getType() { return "Star"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/sungui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/sun.png"; }
    @Override public double getRadius() { return 552.65f; }
    @Override public double getMass() { return 333000.0; }
    @Override public float getTilt() { return 7.25f; }
    @Override public double getRotationPeriod() { return 609.12; }
    @Override public double getTemperature() { return 5505.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
}