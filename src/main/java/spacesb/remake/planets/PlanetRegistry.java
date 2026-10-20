package spacesb.remake.planets;

import spacesb.remake.SpaceEngine.MenuItem;

public class PlanetRegistry {
    public static MenuItem[] menuItems;

    private static MenuItem fromPlanet(BasePlanet p) {
        return new MenuItem(
                p.getName(),
                p.getGuiTex(),
                p.getPlanetTex(),
                p.getRadius(),
                p.getMass(),
                p.getTilt(),
                p.getRotationPeriod(),
                p.getTemperature(),
                p.getUvMap(),
                p.getType(),
                p.isRigid() // Передаем агрегатное состояние в движок
        );
    }

    public static void init() {
        menuItems = new MenuItem[] {
                fromPlanet(new Sun()),
                fromPlanet(new Stephenson2_18()),
                fromPlanet(new Mercury()),
                fromPlanet(new Venus()),
                fromPlanet(new Earth()),
                fromPlanet(new Moon()),
                fromPlanet(new Jupiter()),
                fromPlanet(new Saturn()),
                fromPlanet(new Uranus()),
                fromPlanet(new Titan()),
                fromPlanet(new Enceladus()),
                fromPlanet(new M82X1()),
                fromPlanet(new M15()),
                fromPlanet(new SagittariusA()),
                fromPlanet(new M87()),
                fromPlanet(new Ton618()),
                fromPlanet(new PhoenixA())
        };
    }
}