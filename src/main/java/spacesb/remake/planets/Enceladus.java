package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Enceladus extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4}, {0, 0, 4, 4}, {4, 4, 8, 8}, {0, 4, 4, 8}, {12, 4, 8, 0}, {0, 8, 4, 12}};
    public String getName() { return "Enceladus"; }
    public String getType() { return "Satellite"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/enceladusgui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/enceladus.png"); }
    public float getRadius() { return 0.2f; }
    public double getMass() { return 0.000018; }
    public float getTilt() { return 0.0f; }
    public double getRotationPeriod() { return 32.88; }
    public double getTemperature() { return -201.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}