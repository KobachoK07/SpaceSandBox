package spacesb.remake.planets;

public class Saturn extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4}, {0, 0, 4, 4}, {4, 4, 8, 8}, {0, 4, 4, 8}, {4, 12, 0, 8}, {0, 8, 4, 12}};

    @Override public String getName() { return "Saturn"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/saturngui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/saturn.png"; }
    @Override public double getRadius() { return 46.215f; }
    @Override public double getMass() { return 95.15; }
    @Override public float getTilt() { return 26.73f; }
    @Override public double getRotationPeriod() { return 10.66; }
    @Override public double getTemperature() { return -140.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
}