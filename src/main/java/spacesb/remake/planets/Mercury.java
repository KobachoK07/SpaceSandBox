package spacesb.remake.planets;

public class Mercury extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {4, 12, 0, 8}, {12, 0, 8, 4}};

    @Override public String getName() { return "Mercury"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/mercurygui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/mercury.png"; }
    @Override public double getRadius() { return 1.936f; }
    @Override public double getMass() { return 0.0553; }
    @Override public float getTilt() { return 0.03f; }
    @Override public double getRotationPeriod() { return 1407.6; }
    @Override public double getTemperature() { return 167.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}