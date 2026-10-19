package com.github.lunatrius.schematica.client.input;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.lwjgl.input.Keyboard;
import static org.junit.Assert.*;

public class LitematicaKeysImportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String MALILIB = "{\"config_version\":1,"
        + "\"Generic\":{\"easyPlaceMode\":{\"enabled\":false,\"hotkey\":{\"keys\":\"X\"}},\"customSchematicDirectory\":{\"enabled\":false,\"directory\":\"schematics\"}},"
        + "\"Visuals\":{\"mainRenderingToggle\":{\"enabled\":true,\"hotkey\":{\"keys\":\"M,R\"}}},"
        + "\"Hotkeys\":{\"openMainMenuScreen\":{\"keys\":\"L_ALT,M\"},\"layerNext\":{\"keys\":\"BUTTON4\"},\"executeOperation\":{\"keys\":\"BACK\"},"
        + "\"layerModeNext\":{\"keys\":\"M,PAGE_UP\"},\"toolPlaceCorner1\":{\"keys\":\"LEFT_MOUSE\"},\"addSelectionBox\":{\"keys\":\"\"},\"cloneSelection\":{\"keys\":\"C\",\"settings\":{\"activate_on\":\"RELEASE\",\"context\":\"ANY\",\"allow_extra_keys\":true,\"cancel\":false}},"
        + "\"layerPrevious\":{\"keys\":\"SCROLL_UP\"},\"notOurs\":{\"keys\":\"K\"}}}";

    @Test public void malilibConfigIsCopiedOnFirstStart() throws Exception {
        Path game = temporary.newFolder("game").toPath(), config = temporary.newFolder("config").toPath();
        Path lite = game.resolve("liteconfig/common"); Files.createDirectories(lite);
        Files.write(lite.resolve("litematica.json"), MALILIB.getBytes(StandardCharsets.UTF_8));
        assertEquals(9, LitematicaKeysImport.run(config, game, Hotkeys.ALL));
        assertEquals(Arrays.asList(Keyboard.KEY_LMENU, Keyboard.KEY_M), Hotkeys.get("openGuiMainMenu").keys());
        assertEquals(Collections.singletonList(-96), Hotkeys.get("layerNext").keys());
        assertEquals(Collections.singletonList(Keyboard.KEY_BACK), Hotkeys.get("executeOperation").keys());
        assertEquals(Arrays.asList(Keyboard.KEY_M, Keyboard.KEY_PRIOR), Hotkeys.get("layerModeNext").keys());
        assertEquals(Collections.singletonList(-100), Hotkeys.get("toolPlaceCorner1").keys());
        assertEquals(Collections.emptyList(), Hotkeys.get("addSelectionBox").keys());
        assertEquals(Collections.singletonList(Keyboard.KEY_X), Hotkeys.get("easyPlaceToggle").keys());
        assertEquals(Arrays.asList(Keyboard.KEY_M, Keyboard.KEY_R), Hotkeys.get("toggleAllRendering").keys());
        Hotkey clone = Hotkeys.get("cloneSelection");
        assertEquals(Collections.singletonList(Keyboard.KEY_C), clone.keys());
        assertEquals(Hotkey.Action.RELEASE, clone.settings.action); assertEquals(Hotkey.Context.ANY, clone.settings.context);
        assertTrue(clone.settings.allowExtra); assertFalse(clone.settings.cancel); assertTrue(clone.settings.ordered);
        assertEquals(Collections.singletonList(Keyboard.KEY_NEXT), Hotkeys.get("layerPrevious").keys());
        for (Hotkey key : Hotkeys.ALL) key.reset();
    }
    @Test public void oldLitematicaStringFormatAndMissingOrBrokenFilesAreHandled() throws Exception {
        Path game = temporary.newFolder("game").toPath(), config = temporary.newFolder("config").toPath();
        assertEquals(0, LitematicaKeysImport.run(config, game, Hotkeys.ALL));
        Files.write(config.resolve("litematica.json"), "{".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, LitematicaKeysImport.run(config, game, Hotkeys.ALL));
        Files.write(config.resolve("litematica.json"), "{\"Hotkeys\":{\"openGuiSettings\":\"LCONTROL,C\"}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, LitematicaKeysImport.run(config, game, Hotkeys.ALL));
        assertEquals(Arrays.asList(Keyboard.KEY_LCONTROL, Keyboard.KEY_C), Hotkeys.get("openGuiSettings").keys());
        for (Hotkey key : Hotkeys.ALL) key.reset();
    }
}
