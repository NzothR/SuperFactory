package com.nzoth.superfactory;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String greeting = "Hello World";
    public static int mteIdOffset = 18000;
    public static boolean enableSuperProxyFactory = true;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        greeting = configuration.getString("greeting", Configuration.CATEGORY_GENERAL, greeting, "How shall I greet?");
        mteIdOffset = configuration.getInt(
            "mteIdOffset",
            Configuration.CATEGORY_GENERAL,
            mteIdOffset,
            1,
            Short.MAX_VALUE,
            "Meta tile entity id offset reserved for SuperFactory machines.");
        enableSuperProxyFactory = configuration.getBoolean(
            "enableSuperProxyFactory",
            Configuration.CATEGORY_GENERAL,
            enableSuperProxyFactory,
            "Whether the Super Proxy Factory controller should be registered.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
