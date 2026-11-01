package spacesb.remake.planets;

public class Titan extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {12, 4, 8, 0}, {12, 0, 8, 4}};

    @Override public String getName() { return "Titan"; }
    @Override public String getType() { return "Satellite"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/titangui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/titan.png"; }
    @Override public double getRadius() { return 2.0434; }
    @Override public double getMass() { return 0.0225; }
    @Override public float getTilt() { return 0.3f; }
    @Override public double getRotationPeriod() { return 382.68; }
    @Override public double getTemperature() { return -179.5; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}