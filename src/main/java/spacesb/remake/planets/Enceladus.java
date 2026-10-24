package spacesb.remake.planets;

public class Enceladus extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4}, {0, 0, 4, 4}, {4, 4, 8, 8}, {0, 4, 4, 8}, {12, 4, 8, 0}, {0, 8, 4, 12}};

    @Override public String getName() { return "Enceladus"; }
    @Override public String getType() { return "Satellite"; }
    @Override public String getGuiTex() { return "assets/spacesb-remake/textures/gui/enceladusgui.png"; }
    @Override public String getPlanetTex() { return "assets/spacesb-remake/textures/planet/enceladus.png"; }
    @Override public double getRadius() { return 0.2f; }
    @Override public double getMass() { return 0.000018; }
    @Override public float getTilt() { return 0.0f; }
    @Override public double getRotationPeriod() { return 32.88; }
    @Override public double getTemperature() { return -201.0; }
    @Override public float[][] getUvMap() { return UV_MAP; }
    @Override public boolean isRigid() { return true; }
}