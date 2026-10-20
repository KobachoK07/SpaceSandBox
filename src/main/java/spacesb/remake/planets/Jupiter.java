package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Jupiter extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8},{4, 12, 0, 8},{12, 0, 8, 4}};
    public String getName() { return "Jupiter"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/jupitergui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/jupiter.png"); }
    public float getRadius() { return 56.739f; }
    public double getMass() { return 317.8; }
    public float getTilt() { return 3.13f; }
    public double getRotationPeriod() { return 9.93; }
    public double getTemperature() { return -110.0; }
    public float[][] getUvMap() { return UV_MAP; }
}