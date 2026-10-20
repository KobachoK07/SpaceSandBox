package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Earth extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8 },{4, 12, 0, 8},{12, 0, 8, 4    }};
    public String getName() { return "Earth"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/earthgui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/earth.png"); }
    public float getRadius() { return 5.056f; } // РОВНО 10.1 блоков в диаметре (6,371 км)
    public double getMass() { return 1.0; } // 1 масса Земли
    public float getTilt() { return 23.44f; }
    public double getRotationPeriod() { return 23.93; } // В ЧАСАХ
    public double getTemperature() { return 15.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}