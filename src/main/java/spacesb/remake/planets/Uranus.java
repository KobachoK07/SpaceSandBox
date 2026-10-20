package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Uranus extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4}, {0, 0, 4, 4}, {4, 4, 8, 8}, {0, 4, 4, 8}, {4, 12, 0, 8}, {8, 0, 12, 4}};
    public String getName() { return "Uranus"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/uranusgui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/uranus.png"); }
    public float getRadius() { return 20.128f; }
    public double getMass() { return 14.53; }
    public float getTilt() { return 97.77f; }
    public double getRotationPeriod() { return 17.24; }
    public double getTemperature() { return -195.0; }
    public float[][] getUvMap() { return UV_MAP; }
}