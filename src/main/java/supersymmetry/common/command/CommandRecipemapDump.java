package supersymmetry.common.command;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Supplier;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.*;

import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.ITieredMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.WorkableTieredMetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.CleanroomType;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.metatileentity.multiblock.RecipeMapMultiblockController;
import gregtech.api.pattern.BlockPattern;
import gregtech.api.pattern.TraceabilityPredicate;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.recipeproperties.CleanroomProperty;
import gregtech.api.recipes.recipeproperties.FusionEUToStartProperty;
import gregtech.api.recipes.recipeproperties.RecipeProperty;
import gregtech.api.recipes.recipeproperties.TemperatureProperty;
import gregtech.api.unification.FluidUnifier;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.Materials;
import gregtech.api.unification.material.properties.*;
import gregtech.api.unification.stack.MaterialStack;
import gregtech.api.unification.stack.UnificationEntry;
import gregtech.common.blocks.BlockWireCoil;
import gregtech.common.crafting.GTFluidCraftingIngredient;
import gregtech.common.metatileentities.multi.multiblockpart.*;
import gregtech.common.pipelike.cable.Insulation;
import gregtech.common.pipelike.fluidpipe.FluidPipeType;
import gregtech.common.pipelike.itempipe.ItemPipeType;
import gregtech.core.unification.material.internal.MaterialRegistryManager;
import it.unimi.dsi.fastutil.ints.IntList;
import supersymmetry.api.SusyLog;
import supersymmetry.api.recipes.properties.DimensionProperty;
import supersymmetry.api.recipes.properties.MixerSettlerCellsProperty;

public class CommandRecipemapDump extends CommandBase {

    private static final Field BLOCK_MATCHES;
    private static final Field IS_CENTER;
    private static final Field ITEM_PIPE_RESISTANCE;
    private static final Field ENERGY_HATCH_AMPERAGE;
    private static final Field MUFFLER_RECOVERY_CHANCE;
    private static final Field ENERGY_HATCH_IS_EXPORT;
    private static final Field NOTIFIABLE_IS_EXPORT;
    private static final java.lang.reflect.Method ITEM_BUS_INVENTORY_SIZE;

    static {
        try {
            BLOCK_MATCHES = BlockPattern.class.getDeclaredField("blockMatches");
            BLOCK_MATCHES.setAccessible(true);
            IS_CENTER = TraceabilityPredicate.class.getDeclaredField("isCenter");
            IS_CENTER.setAccessible(true);
            ITEM_PIPE_RESISTANCE = ItemPipeType.class.getDeclaredField("resistanceMultiplier");
            ITEM_PIPE_RESISTANCE.setAccessible(true);
            ENERGY_HATCH_AMPERAGE = MetaTileEntityEnergyHatch.class.getDeclaredField("amperage");
            ENERGY_HATCH_AMPERAGE.setAccessible(true);
            MUFFLER_RECOVERY_CHANCE = MetaTileEntityMufflerHatch.class.getDeclaredField("recoveryChance");
            MUFFLER_RECOVERY_CHANCE.setAccessible(true);
            ENERGY_HATCH_IS_EXPORT = MetaTileEntityEnergyHatch.class.getDeclaredField("isExportHatch");
            ENERGY_HATCH_IS_EXPORT.setAccessible(true);
            NOTIFIABLE_IS_EXPORT = MetaTileEntityMultiblockNotifiablePart.class.getDeclaredField("isExportHatch");
            NOTIFIABLE_IS_EXPORT.setAccessible(true);
            ITEM_BUS_INVENTORY_SIZE = MetaTileEntityItemBus.class.getDeclaredMethod("getInventorySize");
            ITEM_BUS_INVENTORY_SIZE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static JsonElement nbtToJson(NBTBase nbt) {
        if (nbt == null) {
            return JsonNull.INSTANCE;
        }
        if (nbt instanceof NBTTagCompound comp) {
            JsonObject jo = new JsonObject();
            for (String key : comp.getKeySet()) {
                jo.add(key, nbtToJson(comp.getTag(key)));
            }
            return jo;
        } else if (nbt instanceof NBTTagList list) {
            JsonArray ja = new JsonArray();
            for (int i = 0; i < list.tagCount(); i++) {
                ja.add(nbtToJson(list.get(i)));
            }
            return ja;
        } else if (nbt instanceof NBTTagByte)
            return new JsonPrimitive(((NBTTagByte) nbt).getByte());
        else if (nbt instanceof NBTTagShort)
            return new JsonPrimitive(((NBTTagShort) nbt).getShort());
        else if (nbt instanceof NBTTagInt)
            return new JsonPrimitive(((NBTTagInt) nbt).getInt());
        else if (nbt instanceof NBTTagLong)
            return new JsonPrimitive(((NBTTagLong) nbt).getLong());
        else if (nbt instanceof NBTTagFloat)
            return new JsonPrimitive(((NBTTagFloat) nbt).getFloat());
        else if (nbt instanceof NBTTagDouble)
            return new JsonPrimitive(((NBTTagDouble) nbt).getDouble());
        else if (nbt instanceof NBTTagByteArray)
            return new JsonPrimitive(nbt.toString());
        else if (nbt instanceof NBTTagString)
            return new JsonPrimitive(((NBTTagString) nbt).getString());
        else if (nbt instanceof NBTTagIntArray)
            return new JsonPrimitive(nbt.toString());
        else if (nbt instanceof NBTTagLongArray)
            return new JsonPrimitive(nbt.toString());
        else
            throw new IllegalArgumentException("weird nbt class" + nbt.getClass());
    }

    public static @Nullable Material getMaterialFromFluid(@Nullable Fluid fluid) {
        if (fluid == null) {
            return null;
        }
        Material material = FluidUnifier.getMaterialFromFluid(fluid);

        if (material == null) {
            // You do have to check FluidRegistry separately.
            // The wonders of experimental API!
            if (fluid == FluidRegistry.WATER) {
                return Materials.Water;
            } else if (fluid == FluidRegistry.LAVA) {
                return Materials.Lava;
            }
        }
        return material;
    }

    private static @NotNull JsonArray getPropertiesArray(Recipe recipe) {
        JsonArray propertyArray = new JsonArray();
        for (Entry<RecipeProperty<?>, Object> propEntry : recipe.getPropertyValues()) {
            JsonObject propdesc = new JsonObject();
            String key = propEntry.getKey().getKey();
            propdesc.addProperty("propertyKey", key);
            if (key.equals(DimensionProperty.KEY)) { // dimension
                JsonArray arr = new JsonArray();
                for (int dim : (IntList) propEntry.getValue()) {
                    arr.add(dim);
                }
                propdesc.add("dimensions", arr);
            } else if (key.equals(CleanroomProperty.KEY)) { // cleanroom
                propdesc.addProperty("cleanroom", ((CleanroomType) propEntry.getValue()).getName());
            } else if (key.equals(TemperatureProperty.KEY)) { // temperature
                propdesc.addProperty("temperature", (int) propEntry.getValue());
            } else if (key.equals(MixerSettlerCellsProperty.KEY)) { // mixer_settler_cells
                propdesc.addProperty("cells", (int) propEntry.getValue());
            } else if (key.equals(FusionEUToStartProperty.KEY)) { // eu_to_start
                propdesc.addProperty("eu_to_start", (long) propEntry.getValue());
            }
            propertyArray.add(propdesc);
        }
        return propertyArray;
    }

    private static String getAbilityName(MultiblockAbility<?> ability) {
        for (var entry : MultiblockAbility.NAME_REGISTRY.entrySet()) {
            if (entry.getValue() == ability) return entry.getKey();
        }
        return null;
    }

    private static int parseTierSuffix(String suffix) {
        for (int i = 0; i < GTValues.VN.length; i++) {
            if (GTValues.VN[i].equalsIgnoreCase(suffix)) return i;
        }
        return -1;
    }

    private Map<Material, List<ItemStack>> itemStorage = new HashMap<>();
    private Map<Material, List<Fluid>> fluidStorage = new HashMap<>();

    @Override
    public String getName() {
        return "recipemapdump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "susy.command.recipemapdump.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        itemStorage.clear();
        fluidStorage.clear();

        Map<String, Supplier<JsonElement>> fns = Map.ofEntries(
                Map.entry("items", () -> this.dumpItems()),
                Map.entry("fluids", () -> this.dumpFluids()),
                Map.entry("oreDict", () -> this.dumpOreDict()),
                Map.entry("recipemaps", () -> this.gtRecipeMaps()),
                Map.entry("smelting", () -> this.dumpSmeltingRecipes()),
                Map.entry("crafting", () -> this.dumpCraftingRecipes()),
                Map.entry("gtMTEs", () -> this.dumpMachines()),
                Map.entry("multi", () -> this.dumpMultis()),
                Map.entry("materials", () -> this.dumpMaterials()),
                Map.entry("pipeTypes", () -> this.dumpPipeTypes()),
                Map.entry("cableTypes", () -> this.dumpCableTypes()),
                Map.entry("coverTypes", () -> this.dumpCoverTypes()),
                Map.entry("hatchTypes", () -> this.dumpHatchTypes()),
                Map.entry("cleanroomTypes", () -> this.dumpCleanroomTypes()));
        if (args.length == 0) {
            this.runAll(sender, fns);
            return;
        }
        for (String arg : args) {
            if (!fns.containsKey(arg)) {
                throw new CommandException(
                        String.format("Unknown function: \"%s\" Available: %s", arg, fns.keySet().toString()));
            }
        }
        JsonObject root = new JsonObject();
        for (String arg : args) {
            long start = System.nanoTime();
            JsonElement el = fns.get(arg).get();
            long end = System.nanoTime();
            sender.sendMessage(
                    new TextComponentString(String.format("%s" + " ran in %.3fms", arg, (end - start) / 1e6)));

            root.add(arg, el);
        }
        this.writeJsonToRoot(root, "recipedump", sender);
    }

    public JsonElement dumpMaterials() {
        JsonObject allMatsObj = new JsonObject();
        for (Material mat : MaterialRegistryManager.getInstance().getRegisteredMaterials()) {
            allMatsObj.add(mat.getUnlocalizedName(), materialToJson(mat));
        }
        return allMatsObj;
    }

    public JsonElement dumpOreDict() {
        JsonObject allOresObj = new JsonObject();
        String[] ores = OreDictionary.getOreNames();
        for (String ore : ores) {
            JsonArray oreArray = new JsonArray();
            for (ItemStack stack : OreDictionary.getOres(ore)) {
                oreArray.add(stackToJson(stack));
            }
            allOresObj.add(ore, oreArray);
        }
        return allOresObj;
    }

    public JsonElement dumpFluids() {
        JsonArray allFluidsObj = new JsonArray();
        Map<String, Fluid> fluids = FluidRegistry.getRegisteredFluids();
        for (Fluid fluid : fluids.values()) {
            allFluidsObj.add(fullFluidInformation(fluid));
        }
        return allFluidsObj;
    }

    public JsonElement dumpItems() {
        JsonArray allItemsObj = new JsonArray();
        Collection<Item> items = ForgeRegistries.ITEMS.getValuesCollection();
        for (Item item : items) {
            if (item.getHasSubtypes()) {
                NonNullList<ItemStack> subItems = NonNullList.create();
                for (CreativeTabs tab : item.getCreativeTabs()) {
                    if (tab != null) {
                        item.getSubItems(tab, subItems);
                    }
                }
                for (ItemStack stack : subItems) {
                    allItemsObj.add(fullStackInformation(stack));
                }
            } else {
                allItemsObj.add(fullStackInformation(new ItemStack(item)));
            }
        }
        return allItemsObj;
    }

    public void writeJsonToRoot(JsonObject obj, String name, ICommandSender sender) {
        File root = FMLCommonHandler.instance().getSavesDirectory().getParentFile();
        File outFile = new File(root, name + ".json");
        try (FileWriter writer = new FileWriter(outFile)) {
            new Gson().toJson(obj, writer);
            if (sender != null) {
                sender.sendMessage(new TextComponentString("wrote to " + root.getAbsolutePath()));
            }
        } catch (IOException e) {
            SusyLog.logger.error("failed to write the json: " + e);
        }
    }

    public JsonObject gtRecipeMaps() {
        JsonObject allRecipeMapsObj = new JsonObject();
        for (RecipeMap<?> map : RecipeMap.getRecipeMaps()) {
            JsonObject recipemapObj = new JsonObject();

            recipemapObj.addProperty("translationKey", map.getTranslationKey());
            if (map.getSound() == null) {
                recipemapObj.add("sound", JsonNull.INSTANCE);
            } else {
                recipemapObj.addProperty("sound", map.getSound().getRegistryName().toString());
            }
            recipemapObj.addProperty("maxFluidInputs", map.getMaxFluidInputs());
            recipemapObj.addProperty("maxInputs", map.getMaxInputs());
            recipemapObj.addProperty("maxOutputs", map.getMaxOutputs());
            recipemapObj.addProperty("maxFluidOutputs", map.getMaxFluidOutputs());
            recipemapObj.addProperty("unlocalizedName", map.getUnlocalizedName());
            JsonArray recipes = new JsonArray();
            for (Recipe recipe : map.getRecipeList()) {
                recipes.add(recipeToJson(recipe));
            }
            recipemapObj.add("recipes", recipes);
            allRecipeMapsObj.add(map.getUnlocalizedName(), recipemapObj);
        }
        return allRecipeMapsObj;
    }

    public JsonObject stackToJson(ItemStack stack) {
        JsonObject stackObj = new JsonObject();
        if (stack == null || stack.getItem() == null)
            return stackObj;

        stackObj.addProperty("resource", stack.getItem().getRegistryName().toString());
        stackObj.addProperty("count", stack.getCount());
        stackObj.addProperty("metadata", stack.getMetadata());
        if (stack.getTagCompound() != null) {
            stackObj.add("nbt", CommandRecipemapDump.nbtToJson(stack.getTagCompound()));
        }
        return stackObj;
    }

    public JsonObject fullStackInformation(ItemStack stack) {
        JsonObject stackObj = new JsonObject();
        if (stack == null || stack.getItem() == null)
            return stackObj;

        stackObj.addProperty("displayName", stack.getDisplayName());
        stackObj.addProperty("translationKey", stack.getTranslationKey());
        stackObj.addProperty("resource", stack.getItem().getRegistryName().toString());
        stackObj.addProperty("maxDamage", stack.getMaxDamage());
        stackObj.addProperty("metadata", stack.getMetadata());
        stackObj.addProperty("repairCost", stack.getRepairCost());
        stackObj.addProperty("hasSubtypes", stack.getHasSubtypes());
        stackObj.addProperty("maxStackSize", stack.getMaxStackSize());
        stackObj.addProperty("rarity", stack.getRarity().toString());
        stackObj.addProperty("itemClass", stack.getItem().getClass().toString());
        stackObj.addProperty("itemTranslationKey", stack.getItem().getTranslationKey());

        UnificationEntry entry = OreDictUnifier.getUnificationEntry(stack);
        if (entry != null && entry.material != null) {
            MaterialStack matStack = new MaterialStack(entry.material,
                    entry.orePrefix.getMaterialAmount(entry.material));

            stackObj.addProperty("material", matStack.material.getRegistryName());
            stackObj.addProperty("materialAmount", matStack.amount);
            itemStorage.computeIfAbsent(matStack.material, m -> new ArrayList<>()).add(stack);
        }

        return stackObj;
    }

    public JsonElement fullFluidInformation(Fluid fluid) {
        JsonObject fluidObj = new JsonObject();
        if (fluid == null)
            return JsonNull.INSTANCE;
        fluidObj.addProperty("fluidName", fluid.getName());
        fluidObj.addProperty("unlocalizedName", fluid.getUnlocalizedName());
        fluidObj.addProperty("localizedName", fluid.getLocalizedName(new FluidStack(fluid, 1)));
        fluidObj.addProperty("fluidColor", fluid.getColor());

        fluidObj.addProperty("fluidDensity", fluid.getDensity());
        fluidObj.addProperty("fluidRarity", fluid.getRarity().toString());
        fluidObj.addProperty("fluidViscosity", fluid.getViscosity());
        fluidObj.addProperty("fluidLuminosity", fluid.getLuminosity());
        fluidObj.addProperty("fluidTemperature", fluid.getTemperature());

        Material fluidMat = getMaterialFromFluid(fluid);
        if (fluidMat != null) {
            fluidStorage.computeIfAbsent(fluidMat, _ -> new ArrayList<>()).add(fluid);
            fluidObj.addProperty("material", fluidMat.getRegistryName());
        }

        return fluidObj;
    }

    public JsonObject fluidStackToJson(FluidStack fluidStack) {
        JsonObject stackObj = new JsonObject();
        if (fluidStack == null)
            return stackObj;
        if (fluidStack.getFluid() == null)
            return stackObj;
        stackObj.addProperty("type", "FluidStack");
        stackObj.addProperty("unlocalizedName", fluidStack.getUnlocalizedName());
        stackObj.addProperty("specificLocalizedName", fluidStack.getLocalizedName());
        stackObj.addProperty("amount", fluidStack.amount);

        return stackObj;
    }

    public JsonObject ingredientToJson(Ingredient ingredient) {
        JsonObject ingredientObj = new JsonObject();

        JsonArray possibleInputs = new JsonArray();
        for (var v : ingredient.getMatchingStacks()) {
            possibleInputs.add(this.stackToJson(v));
        }
        ingredientObj.addProperty("class", ingredient.getClass().getName());
        ingredientObj.add("validInputs", possibleInputs);
        if (ingredient instanceof GTFluidCraftingIngredient fluid) {
            ingredientObj.add("fluid", fluidStackToJson(fluid.getFluidStack()));
        }
        return ingredientObj;
    }

    public JsonElement dumpCraftingRecipes() {
        JsonArray root = new JsonArray();
        for (var cr : ForgeRegistries.RECIPES.getValuesCollection()) {
            JsonObject recipeobj = new JsonObject();
            var id = cr.getRegistryName();
            if (id == null)
                continue;
            recipeobj.addProperty("id", id.toString());
            recipeobj.addProperty("isDynamic", cr.isDynamic());
            recipeobj.addProperty("class", cr.getClass().toString());
            recipeobj.addProperty("group", cr.getGroup());
            recipeobj.addProperty("registryName", cr.getRegistryName().toString());
            if (cr instanceof IShapedRecipe shaped) {
                recipeobj.addProperty("type", "shaped");
                recipeobj.add("recipe", this.shapedToJson(shaped));
            } else if (cr instanceof ShapelessRecipes shapeless) {
                recipeobj.addProperty("type", "shapeless");
                recipeobj.add("recipe", this.shapelessToJson(shapeless));
                // } else if (cr instanceof GTShapedOreRecipe shapedore) {
                // recipeobj.addProperty("type", "gtShaped");
                //
            } else if (cr instanceof ShapelessOreRecipe oreRecipe) {
                recipeobj.addProperty("type", "shapelessOre");
                recipeobj.add("recipe", this.shapelessOreToJson(oreRecipe));
            } else {
                recipeobj.addProperty("type", "unknown");
                SusyLog.logger.warn("unknown type of {}", cr.getClass().getName());
                recipeobj.add("recipe", JsonNull.INSTANCE);
            }
            recipeobj.add("output", stackToJson(cr.getRecipeOutput()));
            root.add(recipeobj);
        }

        return root;
    }

    public JsonElement dumpSmeltingRecipes() {
        JsonArray smeltingArray = new JsonArray();
        Map<ItemStack, ItemStack> smeltMap = FurnaceRecipes.instance().getSmeltingList();

        for (ItemStack input : smeltMap.keySet()) {
            ItemStack output = smeltMap.get(input);
            if (output == null)
                continue; // just in case
            JsonObject smeltJson = new JsonObject();
            smeltJson.add("input", stackToJson(input));
            smeltJson.add("output", stackToJson(output));
            smeltingArray.add(smeltJson);
        }
        return smeltingArray;
    }

    public JsonElement dumpMachines() {
        var root = new JsonObject();
        for (ResourceLocation key : GregTechAPI.MTE_REGISTRY.getKeys()) {
            var machineObj = new JsonObject();
            MetaTileEntity machine = GregTechAPI.MTE_REGISTRY.getObject(key);
            machineObj.addProperty("class", machine.getClass().toString());
            machineObj.addProperty("metaName", machine.getMetaName());
            machineObj.addProperty("isController", machine instanceof MultiblockControllerBase);
            if (machine instanceof WorkableTieredMetaTileEntity tiered) {
                machineObj.addProperty("tier", tiered.getTier());
                machineObj.addProperty("recipemapName", tiered.getRecipeMap().getUnlocalizedName());
                machineObj.addProperty("workable", tiered.getRecipeLogic().getName());
                machineObj.addProperty(
                        "workableParallelLogicType", tiered.getRecipeLogic().getParallelLogicType().toString());
            } else if (machine instanceof RecipeMapMultiblockController mm) {
                if (mm.recipeMap != null) {
                    machineObj.addProperty("recipemapName", mm.recipeMap.getUnlocalizedName());
                }
                var workable = mm.getRecipeMapWorkable();
                if (workable != null) {
                    machineObj.addProperty("workable", workable.getName());
                    machineObj.addProperty("workableParallelLogicType", workable.getParallelLogicType().toString());
                }
            }
            root.add(key.toString(), machineObj);
        }

        return root;
    }

    public JsonElement dumpPipeTypes() {
        var root = new JsonObject();

        var fluidSizes = new JsonArray();
        for (var type : FluidPipeType.VALUES) {
            var obj = new JsonObject();
            obj.addProperty("name", type.name);
            obj.addProperty("thickness", type.thickness);
            obj.addProperty("capacityMultiplier", type.capacityMultiplier);
            obj.addProperty("channels", type.channels);
            fluidSizes.add(obj);
        }
        root.add("fluid", fluidSizes);

        var fluidMaterials = new JsonArray();
        for (var mat : MaterialRegistryManager.getInstance().getRegisteredMaterials()) {
            if (!mat.hasProperty(PropertyKey.FLUID_PIPE)) continue;
            var base = mat.getProperty(PropertyKey.FLUID_PIPE);
            var matObj = new JsonObject();
            matObj.addProperty("name", mat.getRegistryName());
            matObj.addProperty("maxTemperature", base.getMaxFluidTemperature());
            matObj.addProperty("throughput", base.getThroughput());
            matObj.addProperty("gasProof", base.isGasProof());
            matObj.addProperty("acidProof", base.isAcidProof());
            matObj.addProperty("cryoProof", base.isCryoProof());
            matObj.addProperty("plasmaProof", base.isPlasmaProof());
            fluidMaterials.add(matObj);
        }
        root.add("fluidMaterials", fluidMaterials);

        var itemSizes = new JsonArray();
        for (var type : ItemPipeType.VALUES) {
            var obj = new JsonObject();
            obj.addProperty("name", type.name);
            obj.addProperty("thickness", type.getThickness());
            obj.addProperty("rateMultiplier", type.getRateMultiplier());
            try {
                obj.addProperty("resistanceMultiplier", (float) ITEM_PIPE_RESISTANCE.get(type));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            obj.addProperty("isRestrictive", type.isRestrictive());
            itemSizes.add(obj);
        }
        root.add("item", itemSizes);

        var itemMaterials = new JsonArray();
        for (var mat : MaterialRegistryManager.getInstance().getRegisteredMaterials()) {
            if (!mat.hasProperty(PropertyKey.ITEM_PIPE)) continue;
            var base = mat.getProperty(PropertyKey.ITEM_PIPE);
            var matObj = new JsonObject();
            matObj.addProperty("name", mat.getRegistryName());
            matObj.addProperty("transferRate", base.getTransferRate());
            matObj.addProperty("priority", base.getPriority());
            itemMaterials.add(matObj);
        }
        root.add("itemMaterials", itemMaterials);

        return root;
    }

    public JsonElement dumpCableTypes() {
        var root = new JsonObject();

        var insTypes = new JsonArray();
        for (var ins : Insulation.VALUES) {
            var obj = new JsonObject();
            obj.addProperty("name", ins.name);
            obj.addProperty("thickness", ins.thickness);
            obj.addProperty("amperage", ins.amperage);
            obj.addProperty("lossMultiplier", ins.lossMultiplier);
            obj.addProperty("insulationLevel", ins.insulationLevel);
            obj.addProperty("isCable", ins.isCable());
            insTypes.add(obj);
        }
        root.add("insTypes", insTypes);

        var wireMaterials = new JsonArray();
        for (var mat : MaterialRegistryManager.getInstance().getRegisteredMaterials()) {
            if (!mat.hasProperty(PropertyKey.WIRE)) continue;
            var base = mat.getProperty(PropertyKey.WIRE);
            var matObj = new JsonObject();
            matObj.addProperty("name", mat.getRegistryName());
            matObj.addProperty("voltage", base.getVoltage());
            matObj.addProperty("amperage", base.getAmperage());
            matObj.addProperty("lossPerBlock", base.getLossPerBlock());
            matObj.addProperty("isSuperconductor", base.isSuperconductor());
            wireMaterials.add(matObj);
        }
        root.add("materials", wireMaterials);

        var coils = new JsonArray();
        for (var coil : BlockWireCoil.CoilType.values()) {
            var obj = new JsonObject();
            obj.addProperty("name", coil.getName());
            obj.addProperty("coilTemperature", coil.getCoilTemperature());
            obj.addProperty("level", coil.getLevel());
            obj.addProperty("energyDiscount", coil.getEnergyDiscount());
            if (coil.getMaterial() != null) {
                obj.addProperty("material", coil.getMaterial().getRegistryName());
            }
            coils.add(obj);
        }
        root.add("coils", coils);

        return root;
    }

    private static int coverRate(String baseType, int tier) {
        if (baseType.equals("conveyor") || baseType.equals("robotic_arm")) {
            if (tier <= 1) return 8;
            if (tier == 2) return 32;
            if (tier == 3) return 64;
            if (tier == 4) return 192;
            return 1024;
        }
        if (baseType.equals("pump") || baseType.equals("fluid.regulator")) {
            if (tier <= 1) return 1280;
            if (tier == 2) return 5120;
            if (tier == 3) return 20480;
            if (tier == 4) return 81920;
            if (tier == 5) return 327680;
            if (tier == 6) return 1310720;
            if (tier == 7) return 5242880;
            return 20971520;
        }
        return -1;
    }

    private static boolean coverSupportsExact(String baseType) {
        return baseType.equals("robotic_arm") || baseType.equals("fluid.regulator");
    }

    public JsonElement dumpCoverTypes() {
        var root = new JsonObject();
        for (var key : GregTechAPI.COVER_REGISTRY.getKeys()) {
            var obj = new JsonObject();
            obj.addProperty("id", key.toString());

            var path = key.getPath();
            var parts = path.split("\\.");
            if (parts.length >= 2) {
                var suffix = parts[parts.length - 1];
                var tier = parseTierSuffix(suffix);
                obj.addProperty("tierSuffix", suffix);

                var baseType = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
                obj.addProperty("baseType", baseType);

                if (tier >= 0) {
                    obj.addProperty("tier", tier);
                    var rate = coverRate(baseType, tier);
                    if (rate >= 0) {
                        obj.addProperty("rate", rate);
                    }
                    obj.addProperty("supportsExactAmount", coverSupportsExact(baseType));
                }
            }

            root.add(key.toString(), obj);
        }
        return root;
    }

    public JsonElement dumpHatchTypes() {
        var root = new JsonObject();
        for (var key : GregTechAPI.MTE_REGISTRY.getKeys()) {
            var mte = GregTechAPI.MTE_REGISTRY.getObject(key);
            if (!(mte instanceof IMultiblockAbilityPart abilityPart)) continue;

            var obj = new JsonObject();
            obj.addProperty("metaName", mte.getMetaName());
            obj.addProperty("class", mte.getClass().getName());

            var ability = getAbilityName(abilityPart.getAbility());
            if (ability != null) {
                obj.addProperty("ability", ability);
            }

            if (mte instanceof ITieredMetaTileEntity tiered) {
                obj.addProperty("tier", tiered.getTier());
            }

            if (mte instanceof MetaTileEntityEnergyHatch energyHatch) {
                try {
                    obj.addProperty("isExport", ENERGY_HATCH_IS_EXPORT.getBoolean(energyHatch));
                    obj.addProperty("amperage", ENERGY_HATCH_AMPERAGE.getInt(energyHatch));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            } else if (mte instanceof MetaTileEntityItemBus itemBus) {
                try {
                    obj.addProperty("isExport", NOTIFIABLE_IS_EXPORT.getBoolean(itemBus));
                    obj.addProperty("inventorySize", (Integer) ITEM_BUS_INVENTORY_SIZE.invoke(itemBus));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (mte instanceof MetaTileEntityFluidHatch fluidHatch) {
                try {
                    obj.addProperty("isExport", NOTIFIABLE_IS_EXPORT.getBoolean(fluidHatch));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                var tier = fluidHatch.getTier();
                obj.addProperty("tankCapacity", 8000 * (1 << Math.min(9, tier)));
            } else if (mte instanceof MetaTileEntityMufflerHatch) {
                try {
                    obj.addProperty("recoveryChance", MUFFLER_RECOVERY_CHANCE.getInt(mte));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }

            root.add(key.toString(), obj);
        }
        return root;
    }

    public JsonElement dumpCleanroomTypes() {
        // shrug
        var root = new JsonObject();
        for (var type : List.of(CleanroomType.CLEANROOM, CleanroomType.STERILE_CLEANROOM)) {
            var obj = new JsonObject();
            obj.addProperty("name", type.getName());
            obj.addProperty("translationKey", type.getTranslationKey());
            root.add(type.getName(), obj);
        }
        return root;
    }

    private void runAll(ICommandSender sender, Map<String, Supplier<JsonElement>> funcMap) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Supplier<JsonElement>> e : funcMap.entrySet()) {
            long start = System.nanoTime();
            JsonElement el = e.getValue().get();
            long end = System.nanoTime();
            sender.sendMessage(
                    new TextComponentString(
                            String.format("%s" + " ran in %.3fms", e.getKey(), (end - start) / 1e6)));
            root.add(e.getKey(), el);
        }
        this.writeJsonToRoot(root, "recipedump", sender);
    }

    private JsonElement dumpMultis() {
        var root = new JsonObject();
        for (MetaTileEntity mte : GregTechAPI.MTE_REGISTRY) {
            if (!(mte instanceof MultiblockControllerBase multiblock)) continue;

            var multiObj = new JsonObject();
            multiObj.addProperty("class", mte.getClass().getName());
            multiObj.addProperty("metaName", mte.getMetaName());
            multiObj.addProperty("allowsExtendedFacing", multiblock.allowsExtendedFacing());
            multiObj.addProperty("allowsFlip", multiblock.allowsFlip());

            var pattern = multiblock.structurePattern;
            if (pattern == null) continue;
            TraceabilityPredicate[][][] predicates;
            try {
                predicates = (TraceabilityPredicate[][][]) BLOCK_MATCHES.get(pattern);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            var predicateToChar = new HashMap<TraceabilityPredicate, Character>();
            var charToPredicate = new HashMap<Character, TraceabilityPredicate>();
            for (int z = 0; z < predicates.length; z++) {
                for (int y = 0; y < predicates[z].length; y++) {
                    for (int x = 0; x < predicates[z][y].length; x++) {
                        var predicate = predicates[z][y][x];
                        if (predicateToChar.containsKey(predicate)) continue;
                        char c = (char) ('A' + predicateToChar.size());
                        predicateToChar.put(predicate, c);
                        charToPredicate.put(c, predicate);
                    }
                }
            }

            var aisles = new JsonArray();
            for (int z = 0; z < predicates.length; z++) {
                var aisle = new JsonArray();
                for (int y = 0; y < predicates[z].length; y++) {
                    var sb = new StringBuilder();
                    for (int x = 0; x < predicates[z][y].length; x++) {
                        sb.append(predicateToChar.get(predicates[z][y][x]));
                    }
                    aisle.add(sb.toString());
                }
                aisles.add(aisle);
            }
            multiObj.add("aisles", aisles);

            var reps = new JsonArray();
            for (int[] rep : pattern.aisleRepetitions) {
                var pair = new JsonArray();
                pair.add(rep[0]);
                pair.add(rep[1]);
                reps.add(pair);
            }
            multiObj.add("aisleRepetitions", reps);

            var symbols = new JsonObject();
            for (var entry : charToPredicate.entrySet()) {
                char c = entry.getKey();
                var predicate = entry.getValue();
                var symObj = new JsonObject();
                try {
                    symObj.addProperty("isCenter", IS_CENTER.getBoolean(predicate));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                symObj.addProperty("hasAir", predicate.isHasAir());
                symObj.addProperty("isSingle", predicate.isSingle());
                symObj.add("common", simplePredicatesToJson(predicate.common));
                symObj.add("limited", simplePredicatesToJson(predicate.limited));

                var abilities = new JsonArray();
                var seen = new HashSet<String>();
                for (var sp : predicate.common) {
                    if (sp.candidates == null) continue;
                    for (var info : sp.candidates.get()) {
                        if (info.getTileEntity() instanceof IGregTechTileEntity gtTe &&
                                gtTe.getMetaTileEntity() instanceof IMultiblockAbilityPart<?>ap) {
                            var name = getAbilityName(ap.getAbility());
                            if (name != null && seen.add(name)) {
                                abilities.add(name);
                            }
                        }
                    }
                }
                if (abilities.size() > 0) {
                    symObj.add("abilities", abilities);
                }

                symbols.add(String.valueOf(c), symObj);
            }
            multiObj.add("symbols", symbols);

            root.add(mte.metaTileEntityId.toString(), multiObj);
        }
        return root;
    }

    private JsonArray simplePredicatesToJson(List<TraceabilityPredicate.SimplePredicate> list) {
        var arr = new JsonArray();
        for (var sp : list) {
            var obj = new JsonObject();
            if (sp.minGlobalCount != -1) obj.addProperty("minGlobal", sp.minGlobalCount);
            if (sp.maxGlobalCount != -1) obj.addProperty("maxGlobal", sp.maxGlobalCount);
            if (sp.minLayerCount != -1) obj.addProperty("minLayer", sp.minLayerCount);
            if (sp.maxLayerCount != -1) obj.addProperty("maxLayer", sp.maxLayerCount);
            if (sp.previewCount != -1) obj.addProperty("preview", sp.previewCount);

            var candArr = new JsonArray();
            if (sp.candidates != null) {
                for (var info : sp.candidates.get()) {
                    var candObj = new JsonObject();
                    var state = info.getBlockState();
                    var block = state.getBlock();
                    var regName = block.getRegistryName();
                    if (regName != null) {
                        candObj.addProperty("block", regName.toString());
                        candObj.addProperty("meta", block.damageDropped(state));
                    }
                    if (info.getTileEntity() instanceof IGregTechTileEntity gtTe) {
                        var stack = gtTe.getMetaTileEntity().getStackForm();
                        var itemReg = stack.getItem().getRegistryName();
                        if (itemReg != null) {
                            candObj.addProperty("item", itemReg.toString());
                            candObj.addProperty("itemMeta", stack.getItemDamage());
                        }
                    }
                    candArr.add(candObj);
                }
            }
            obj.add("candidates", candArr);
            arr.add(obj);
        }
        return arr;
    }

    private JsonElement materialToJson(Material mat) {
        JsonObject matObj = new JsonObject();
        matObj.addProperty("resource", mat.getRegistryName());
        matObj.addProperty("unlocalizedName", mat.getUnlocalizedName());
        matObj.addProperty("localizedName", mat.getLocalizedName());
        matObj.addProperty("color", mat.getMaterialRGB());
        matObj.addProperty("chemicalFormula", mat.getChemicalFormula());
        matObj.addProperty("id", mat.getId());
        matObj.addProperty("modId", mat.getModid());
        matObj.addProperty("mass", mat.getMass());
        matObj.addProperty("neutrons", mat.getNeutrons());
        matObj.addProperty("protons", mat.getProtons());
        matObj.addProperty("isRadioactive", mat.isRadioactive());

        if (fluidStorage.get(mat) != null) {
            JsonArray matFluids = new JsonArray();

            for (Fluid fluid : fluidStorage.get(mat)) {
                matFluids.add(fluid.getUnlocalizedName());
            }
            matObj.add("fluids", matFluids);
        }
        if (itemStorage.get(mat) != null) {
            JsonArray matItems = new JsonArray();
            for (ItemStack item : itemStorage.get(mat)) {
                matItems.add(stackToJson(item));
            }
            matObj.add("items", matItems);
        }

        if (mat.hasProperty(PropertyKey.TOOL)) {
            JsonObject toolProps = new JsonObject();
            ToolProperty prop = mat.getProperty(PropertyKey.TOOL);
            toolProps.addProperty(
                    "durability", prop.getToolDurability() * prop.getDurabilityMultiplier());
            toolProps.addProperty("miningSpeed", prop.getToolSpeed());
            toolProps.addProperty("attackDamage", prop.getToolAttackDamage());
            toolProps.addProperty("attackSpeed", prop.getToolAttackSpeed());
            toolProps.addProperty("harvestLevel", prop.getToolHarvestLevel());
            matObj.add("tool", toolProps);
        }
        if (mat.hasProperty(PropertyKey.ITEM_PIPE)) {
            JsonObject pipeProps = new JsonObject();
            ItemPipeProperties prop = mat.getProperty(PropertyKey.ITEM_PIPE);
            pipeProps.addProperty("transferRate", prop.getTransferRate());
            pipeProps.addProperty("priority", prop.getPriority());
            matObj.add("pipe", pipeProps);
        }
        if (mat.hasProperty(PropertyKey.FLUID_PIPE)) {
            JsonObject pipeProps = new JsonObject();
            FluidPipeProperties prop = mat.getProperty(PropertyKey.FLUID_PIPE);
            pipeProps.addProperty("channels", prop.getTanks());
            pipeProps.addProperty("priority", prop.getPriority());
            pipeProps.addProperty("acidProof", prop.isAcidProof());
            pipeProps.addProperty("cryoProof", prop.isCryoProof());
            pipeProps.addProperty("gasProof", prop.isGasProof());
            pipeProps.addProperty("plasmaProof", prop.isPlasmaProof());
            pipeProps.addProperty("maxTemperature", prop.getMaxFluidTemperature());
            pipeProps.addProperty("throughput", prop.getThroughput());

            matObj.add("pipe", pipeProps);
        }
        if (mat.hasProperty(PropertyKey.WIRE)) {
            JsonObject wireProps = new JsonObject();
            WireProperties prop = mat.getProperty(PropertyKey.WIRE);
            wireProps.addProperty("amperage", prop.getAmperage());
            wireProps.addProperty("voltage", prop.getVoltage());
            wireProps.addProperty("lossPerBlock", prop.getLossPerBlock());
            wireProps.addProperty("isSuperconductor", prop.isSuperconductor());
            wireProps.addProperty("curieTemperature", prop.getSuperconductorCriticalTemperature());
            matObj.add("wire", wireProps);
        }
        return matObj;
    }

    private @NotNull JsonObject recipeToJson(Recipe recipe) {
        JsonObject recipeobj = new JsonObject();
        // general recipe information
        recipeobj.addProperty("class", recipe.getClass().toString());
        recipeobj.addProperty("EUt", recipe.getEUt());
        recipeobj.addProperty("duration", recipe.getDuration());
        recipeobj.addProperty("isCTRecipe", recipe.getIsCTRecipe());
        recipeobj.addProperty("propertyCount", recipe.getPropertyCount());
        recipeobj.addProperty("unhiddenPropertyCount", recipe.getUnhiddenPropertyCount());
        JsonArray propertyArray = getPropertiesArray(recipe);
        recipeobj.add("properties", propertyArray);
        recipeobj.addProperty("categoryName", recipe.getRecipeCategory().getName());
        recipeobj.addProperty("categoryTranslationKey", recipe.getRecipeCategory().getTranslationKey());
        recipeobj.addProperty("categoryUniqueID", recipe.getRecipeCategory().getUniqueID());
        recipeobj.addProperty("categoryModID", recipe.getRecipeCategory().getModid());

        // items and fluids
        {
            JsonArray itemInputs = new JsonArray();
            if (recipe.getInputs() != null) {
                for (GTRecipeInput recipeInput : recipe.getInputs()) {
                    JsonObject input = new JsonObject();
                    input.addProperty("class", recipeInput.getClass().toString());
                    input.addProperty("amount", recipeInput.getAmount());
                    input.addProperty("oreDict", recipeInput.getOreDict());
                    input.addProperty("sortingOrder", recipeInput.getSortingOrder());
                    input.addProperty("nonConsumable", recipeInput.isNonConsumable());
                    JsonArray inputstacks = new JsonArray();
                    if (recipeInput.getInputStacks() != null) {
                        for (ItemStack stackInput : recipeInput.getInputStacks()) {
                            inputstacks.add(this.stackToJson(stackInput));
                        }
                    }
                    input.add("inputStacks", inputstacks);
                    input.add("inputFluidStack", this.fluidStackToJson(recipeInput.getInputFluidStack()));

                    itemInputs.add(input);
                }
            }

            JsonArray fluidInputs = new JsonArray();
            if (recipe.getFluidInputs() != null) {
                for (GTRecipeInput recipeInput : recipe.getFluidInputs()) {
                    JsonObject input = new JsonObject();
                    input.addProperty("class", recipeInput.getClass().toString());
                    input.addProperty("amount", recipeInput.getAmount());
                    input.addProperty("oreDict", recipeInput.getOreDict());
                    input.addProperty("sortingOrder", recipeInput.getSortingOrder());
                    input.addProperty("nonConsumable", recipeInput.isNonConsumable());
                    JsonArray inputstacks = new JsonArray();
                    if (recipeInput.getInputStacks() != null) {
                        for (ItemStack stackInput : recipeInput.getInputStacks()) {
                            inputstacks.add(this.stackToJson(stackInput));
                        }
                    }
                    input.add("inputStacks", inputstacks);
                    input.add("inputFluidStack", this.fluidStackToJson(recipeInput.getInputFluidStack()));

                    fluidInputs.add(input);
                }
            }
            JsonArray fluidOutputs = new JsonArray();
            if (recipe.getFluidOutputs() != null) {
                recipe.getFluidOutputs().forEach(x -> fluidOutputs.add(this.fluidStackToJson(x)));
            }
            JsonArray itemOutputs = new JsonArray();
            if (recipe.getOutputs() != null) {
                recipe.getOutputs().forEach(x -> itemOutputs.add(this.stackToJson(x)));
            }
            if (recipe.getChancedOutputs() != null) {
                JsonObject chancedOutputObj = new JsonObject();
                JsonArray chancedOutputs = new JsonArray();
                var chanced = recipe.getChancedOutputs();
                chanced
                        .getChancedEntries()
                        .forEach(
                                x -> {
                                    var stack = this.stackToJson(x.getIngredient());
                                    stack.addProperty("chance", x.getChance());
                                    chancedOutputs.add(stack);
                                });
                chancedOutputObj.addProperty(
                        "logic", I18n.translateToLocal(chanced.getChancedOutputLogic().getTranslationKey()));
                recipeobj.add("chancedOutputs", chancedOutputs);
            }
            if (recipe.getChancedFluidOutputs() != null) {
                JsonObject chancedOutputObj = new JsonObject();
                JsonArray chancedOutputs = new JsonArray();
                var chanced = recipe.getChancedFluidOutputs();
                chanced
                        .getChancedEntries()
                        .forEach(
                                x -> {
                                    JsonObject stack = this.fluidStackToJson(x.getIngredient());
                                    stack.addProperty("chance", x.getChance());
                                    chancedOutputs.add(stack);
                                });
                chancedOutputObj.addProperty(
                        "logic", I18n.translateToLocal(chanced.getChancedOutputLogic().getTranslationKey()));
                recipeobj.add("chancedFluidOutputs", chancedOutputs);
            }
            recipeobj.add("inputsFluid", fluidInputs);
            recipeobj.add("inputs", itemInputs);
            recipeobj.add("outputs", itemOutputs);
            recipeobj.add("fluidOutputs", fluidOutputs);
        }
        return recipeobj;
    }

    private JsonElement shapedToJson(IShapedRecipe shaped) {
        int width = shaped.getRecipeWidth();
        int height = shaped.getRecipeHeight();
        JsonObject root = new JsonObject();
        List<Ingredient> ingredients = shaped.getIngredients();

        // map ingredient (by registry name) -> a single ASCII char
        Map<Ingredient, Character> keyMap = new LinkedHashMap<>();
        char nextChar = 'A';

        StringBuilder patternBuilder = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var ing = ingredients.get(y * width + x);
                if (ing == null || ing.getMatchingStacks() == null || ing.getMatchingStacks().length == 0) {
                    patternBuilder.append(' '); // empty slot
                } else {

                    if (!keyMap.containsKey(ing)) {
                        keyMap.put(ing, nextChar);
                        nextChar++;
                    }
                    patternBuilder.append(keyMap.get(ing));
                }
            }
            patternBuilder.append("\n");
        }
        // the pattern should end up like "A A\n B \nC C" or something
        var keymap = new JsonObject();
        for (var e : keyMap.entrySet()) {
            keymap.add(String.valueOf(e.getValue()), ingredientToJson(e.getKey()));
            // results in something like keymap : { "A": { class:"..." validInputs: [
            // {stack1},{stack2}]}}
        }
        var shape = new JsonArray();
        for (String line : patternBuilder.toString().split("\n")) {
            if (line.length() != 0) {
                shape.add(line);
            }
        }
        root.add("keymap", keymap);
        root.add("shape", shape);
        // root.add("isClearing", shaped.getRecipeOutput());

        return root;
    }

    private JsonElement shapelessToJson(ShapelessRecipes shapeless) {
        JsonArray ingredients = new JsonArray();
        var root = new JsonObject();
        for (var ingredient : shapeless.getIngredients()) {
            if (ingredient != null && ingredient.getMatchingStacks() != null &&
                    ingredient.getMatchingStacks().length != 0) {
                ingredients.add(ingredientToJson(ingredient));
            }
        }
        root.add("ingredients", ingredients);
        return root;
    }

    private JsonElement shapelessOreToJson(ShapelessOreRecipe shapeless) {
        JsonArray ingredients = new JsonArray();
        var root = new JsonObject();
        for (var ingredient : shapeless.getIngredients()) {
            ingredients.add(ingredientToJson(ingredient));
        }
        root.add("ingredients", ingredients);
        return root;
    }
}
