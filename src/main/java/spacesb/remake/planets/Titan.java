package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Titan extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4}, {4, 4, 8, 8}, {12, 4, 8, 0}, {12, 0, 8, 4}};
    public String getName() { return "Titan"; }
    public String getType() { return "Satellite"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/titangui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/titan.png"); }
    public float getRadius() { return 2.043f; }
    public double getMass() { return 0.0225; }
    public float getTilt() { return 0.0f; }
    public double getRotationPeriod() { return 382.69; }
    public double getTemperature() { return -179.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}