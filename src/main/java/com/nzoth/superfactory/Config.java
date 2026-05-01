package com.nzoth.superfactory;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String greeting = "Hello World";
    public static int mteIdOffset = 18000;
    public static boolean enableSuperProxyFactory = true;
    public static boolean enableOutputMultiplierAdjustment = false;
    public static boolean enableRuntimeAdjustment = false;
    public static boolean enableOutputRangeAdjustment = false;
    private static File boundConfigFile;

    public static void synchronizeConfiguration(File configFile) {
        boundConfigFile = configFile;
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
        enableOutputMultiplierAdjustment = configuration.getBoolean(
            "enableOutputMultiplierAdjustment",
            Configuration.CATEGORY_GENERAL,
            enableOutputMultiplierAdjustment,
            "Enable item/fluid output multiplier parameters.");
        enableRuntimeAdjustment = configuration.getBoolean(
            "enableRuntimeAdjustment",
            Configuration.CATEGORY_GENERAL,
            enableRuntimeAdjustment,
            "Enable minimum/maximum runtime parameters.");
        enableOutputRangeAdjustment = configuration.getBoolean(
            "enableOutputRangeAdjustment",
            Configuration.CATEGORY_GENERAL,
            enableOutputRangeAdjustment,
            "Enable minimum/maximum item/fluid output parameters.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void reload() {
        if (boundConfigFile != null) {
            synchronizeConfiguration(boundConfigFile);
        }
    }
}
