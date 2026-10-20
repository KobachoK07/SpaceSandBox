package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Mercury extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8},{4, 12, 0, 8},{12, 0, 8, 4}};
    public String getName() { return "Mercury"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/mercurygui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/mercury.png"); }
    public float getRadius() { return 1.936f; }
    public double getMass() { return 0.0553; }
    public float getTilt() { return 0.03f; }
    public double getRotationPeriod() { return 1407.6; }
    public double getTemperature() { return 167.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}