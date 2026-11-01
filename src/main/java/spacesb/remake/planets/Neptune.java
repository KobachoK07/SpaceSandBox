package spacesb.remake.planets;

public class Neptune extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {0, 8, 4, 12}, {8, 0, 12, 4}};

    @Override public String getName() { return "Neptune"; }
    @Override public String getType() { return "Planet"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/neptunegui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/neptune.png"; }
    @Override public double getRadius() { return 19.5413; }
    @Override public double getMass() { return 17.147; }
    @Override public float getTilt() { return 28.32f; }
    @Override public double getRotationPeriod() { return 16.11; }
    @Override public double getTemperature() { return -200.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return false; }
}