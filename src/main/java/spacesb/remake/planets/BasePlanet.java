package spacesb.remake.planets;

import net.minecraft.util.Identifier;

public abstract class BasePlanet {
    public static final float[][] UV_DEFAULT = {{0,0,4,4},{0,4,4,8},{4,0,8,4},{4,4,8,8},{4,12,0,8},{12,0,8,4}};

    public abstract String getName();
    public abstract String getType();
    public abstract Identifier getGuiTex();
    public abstract Identifier getPlanetTex();
    public abstract float getRadius();
    public abstract double getMass();
    public abstract float getTilt();
    public abstract double getRotationPeriod();
    public abstract double getTemperature();
    public boolean isRigid() { return false; }

    // Если у планеты нет своей развертки, она возьмет дефолтную автоматически
    public float[][] getUvMap() { return UV_DEFAULT; }
}