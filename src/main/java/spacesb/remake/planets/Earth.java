package spacesb.remake.planets;

public class Earth extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8 },{4, 12, 0, 8},{12, 0, 8, 4}};

    @Override public String getName() { return "Earth"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/earthgui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/earth.png"; }
    @Override public double getRadius() { return 5.056f; }
    @Override public double getMass() { return 1.0; }
    @Override public float getTilt() { return 23.44f; }
    @Override public double getRotationPeriod() { return 23.93; }
    @Override public double getTemperature() { return 15.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}