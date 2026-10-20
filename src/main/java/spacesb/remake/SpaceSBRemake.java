package spacesb.remake;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpaceSBRemake implements ModInitializer {
	public static final String MOD_ID = "spacesb-remake";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Взламываем Майнкрафт...");

		// Врубаем статус в Discord с кнопкой на Modrinth
		DiscordRPC.start();
	}
}