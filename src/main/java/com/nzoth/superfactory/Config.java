package com.nzoth.superfactory;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String greeting = "Hello World";
    public static int mteIdOffset = 18000;
    public static boolean enableSuperProxyFactory = true;
    public static boolean enableSuperIntegratedFactory = true;
    public static boolean enableCheapSuperProxyFactoryRecipe = false;
    public static boolean enableCheapSuperIntegratedFactoryRecipe = false;
    public static boolean enableOutputMultiplierAdjustment = false;
    public static boolean enableRuntimeAdjustment = false;
    public static boolean enableOutputRangeAdjustment = false;
    public static int superProxyFactorySuccessfulRecipeCacheSize = 64;
    public static int superProxyFactoryPendingOutputEntryLimit = 64;
    public static int superIntegratedFactoryMaxOutputFlushEntriesPerTick = 128;
    public static int superIntegratedFactoryMaxNodeStartsPerTick = 16;
    public static int superIntegratedFactoryLookaheadTicks = 20;
    public static boolean debugSuperProxyFactoryRuntime = false;
    public static boolean debugExportIntegratedFactoryInternalBuffer = false;
    public static boolean debugIntegratedFactoryRuntime = false;
    public static boolean debugIntegratedFactoryNetwork = true;
    public static boolean allowProxyFactoryAsIntegratedRecipeHost = true;
    private static File boundConfigFile;

    public static void synchronizeConfiguration(File configFile) {
        boundConfigFile = configFile;
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
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
        enableSuperIntegratedFactory = configuration.getBoolean(
            "enableSuperIntegratedFactory",
            Configuration.CATEGORY_GENERAL,
            enableSuperIntegratedFactory,
            "Whether the Super Integrated Factory controller should be registered.");
        enableCheapSuperProxyFactoryRecipe = configuration.getBoolean(
            "enableCheapSuperProxyFactoryRecipe",
            Configuration.CATEGORY_GENERAL,
            enableCheapSuperProxyFactoryRecipe,
            "Enable an optional cheap LV assembler recipe for the Super Proxy Factory controller.");
        enableCheapSuperIntegratedFactoryRecipe = configuration.getBoolean(
            "enableCheapSuperIntegratedFactoryRecipe",
            Configuration.CATEGORY_GENERAL,
            enableCheapSuperIntegratedFactoryRecipe,
            "Enable an optional cheap LV assembler recipe for the Super Integrated Factory controller.");
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
        superProxyFactorySuccessfulRecipeCacheSize = configuration.getInt(
            "superProxyFactorySuccessfulRecipeCacheSize",
            Configuration.CATEGORY_GENERAL,
            superProxyFactorySuccessfulRecipeCacheSize,
            0,
            1024,
            "Maximum number of recently successful recipes cached per Super Proxy Factory. Set to 0 to disable.");
        superProxyFactoryPendingOutputEntryLimit = configuration.getInt(
            "superProxyFactoryPendingOutputEntryLimit",
            Configuration.CATEGORY_GENERAL,
            superProxyFactoryPendingOutputEntryLimit,
            0,
            4096,
            "Maximum number of buffered output stack entries allowed before the Super Proxy Factory pauses new recipes. Set to 0 to disable the pause.");
        superIntegratedFactoryMaxOutputFlushEntriesPerTick = configuration.getInt(
            "superIntegratedFactoryMaxOutputFlushEntriesPerTick",
            Configuration.CATEGORY_GENERAL,
            superIntegratedFactoryMaxOutputFlushEntriesPerTick,
            0,
            4096,
            "Maximum number of Integrated Factory buffered item/fluid output entries flushed per tick. Set to 0 for unlimited.");
        superIntegratedFactoryMaxNodeStartsPerTick = configuration.getInt(
            "superIntegratedFactoryMaxNodeStartsPerTick",
            Configuration.CATEGORY_GENERAL,
            superIntegratedFactoryMaxNodeStartsPerTick,
            1,
            1024,
            "Maximum number of Integrated Factory process nodes started per tick.");
        superIntegratedFactoryLookaheadTicks = configuration.getInt(
            "superIntegratedFactoryLookaheadTicks",
            Configuration.CATEGORY_GENERAL,
            superIntegratedFactoryLookaheadTicks,
            0,
            200,
            "Running job lookahead window in ticks for Integrated Factory projected watermarks.");
        debugSuperProxyFactoryRuntime = configuration.getBoolean(
            "debugSuperProxyFactoryRuntime",
            Configuration.CATEGORY_GENERAL,
            debugSuperProxyFactoryRuntime,
            "Debug only: log Super Proxy Factory recipe input scanning and special dual input hatch compatibility.");
        debugExportIntegratedFactoryInternalBuffer = configuration.getBoolean(
            "debugExportIntegratedFactoryInternalBuffer",
            Configuration.CATEGORY_GENERAL,
            debugExportIntegratedFactoryInternalBuffer,
            "Debug only: export Integrated Factory internal intermediate buffers during OUTPUT instead of discarding them.");
        debugIntegratedFactoryRuntime = configuration.getBoolean(
            "debugIntegratedFactoryRuntime",
            Configuration.CATEGORY_GENERAL,
            debugIntegratedFactoryRuntime,
            "Debug only: log Integrated Factory runtime scheduling, input bottlenecks, and waterline throttling.");
        debugIntegratedFactoryNetwork = configuration.getBoolean(
            "debugIntegratedFactoryNetwork",
            Configuration.CATEGORY_GENERAL,
            debugIntegratedFactoryNetwork,
            "Debug only: log Integrated Factory network packet transfer for graph sync and process submission.");
        allowProxyFactoryAsIntegratedRecipeHost = configuration.getBoolean(
            "allowProxyFactoryAsIntegratedRecipeHost",
            Configuration.CATEGORY_GENERAL,
            allowProxyFactoryAsIntegratedRecipeHost,
            "Allow Super Proxy Factory controllers to substitute missing Integrated Factory recipe host controllers.");

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
