package spacesb.remake.planets;

public class Moon extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {4, 12, 0, 8}, {12, 0, 8, 4}};

    @Override public String getName() { return "Moon"; }
    @Override public String getType() { return "Satellite"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/moongui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/moon.png"; }
    @Override public double getRadius() { return 1.378f; }
    @Override public double getMass() { return 0.0123; }
    @Override public float getTilt() { return 1.54f; }
    @Override public double getRotationPeriod() { return 655.7; }
    @Override public double getTemperature() { return -20.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}