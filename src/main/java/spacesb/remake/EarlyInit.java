package spacesb.remake;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class EarlyInit implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        // Мы прописываем системное свойство ДО того, как Minecraft загрузит LWJGL.
        // Когда игра начнет стартовать, драйвер сам прочитает это значение.
        System.setProperty("org.lwjgl.system.stackSize", "1024");
        System.out.println("[SBox] АБСОЛЮТНЫЙ ПЕРЕХВАТ: Стек памяти LWJGL расширен до 1024 KB");
    }
}