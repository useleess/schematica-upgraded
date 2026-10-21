package com.github.lunatrius.schematica.reference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Reference {

    public static final String MODID = "schematica_plus";
    public static final String NAME = "Schematica Plus";
    public static final String VERSION = "1.0.0-beta.3";
    public static final String MC_VERSION = "[1.12.2]";
    public static final String DEPENDENCIES = "";
    public static final String PROXY_SERVER = "com.github.lunatrius.schematica.proxy.ServerProxy";
    public static final String PROXY_CLIENT = "com.github.lunatrius.schematica.proxy.ClientProxy";
    public static final String GUI_FACTORY = "com.github.lunatrius.schematica.client.gui.GuiFactory";

    public static Logger logger = LogManager.getLogger(Reference.MODID);
}
