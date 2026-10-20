package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Moon extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8},{4, 12, 0, 8},{12, 0, 8, 4}};
    public String getName() { return "Moon"; }
    public String getType() { return "Satellite"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/moongui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/moon.png"); }
    public float getRadius() { return 1.378f; }
    public double getMass() { return 0.0123; }
    public float getTilt() { return 1.54f; }
    public double getRotationPeriod() { return 655.7; }
    public double getTemperature() { return -20.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}