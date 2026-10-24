package spacesb.remake.planets;

public class Venus extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {4, 12, 0, 8}, {4, 8, 0, 12}};

    @Override public String getName() { return "Venus"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/venusgui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/venus.png"; }
    @Override public double getRadius() { return 4.803f; }
    @Override public double getMass() { return 0.815; }
    @Override public float getTilt() { return 177.36f; }
    @Override public double getRotationPeriod() { return 5832.5; }
    @Override public double getTemperature() { return 464.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}