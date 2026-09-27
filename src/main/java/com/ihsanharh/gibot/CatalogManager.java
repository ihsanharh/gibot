package com.ihsanharh.gibot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the gift item catalog, tracks token balances,
 * and handles on-demand in-memory store parsing.
 */
@Log4j2
public class CatalogManager {
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)§[0-9a-z]");
    private static final Pattern GENERAL_TOKEN_AVAIL_PATTERN = Pattern.compile("(\\d+)\\s+Gift Tokens?\\s+Available", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY_TOKEN_AVAIL_PATTERN = Pattern.compile("(\\d+)\\s+(?:Gift Tokens?\\s+)?Available", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_COST_PATTERN = Pattern.compile("\\((\\d+)(?:-(\\d+))?\\s+tokens?\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUB_ITEM_COST_PATTERN = Pattern.compile("(\\d+)\\s+Tokens?", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOCK_PATTERN = Pattern.compile("(\\d+)\\s+Available", Pattern.CASE_INSENSITIVE);

    @Data
    @NoArgsConstructor
    public static class ItemEntry {
        private String name;
        private String category;
        private int tokenCost;
        private int tokenCostMax;
        private Integer stock;
        private String imageUrl;
    }

    @Data
    @NoArgsConstructor
    public static class CatalogData {
        private String accountName = "Unknown";
        private int accountTokenBalance = 0;
        private Map<String, Integer> categoryTokens = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private Map<String, String> categoryImages = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private Map<String, ItemEntry> items = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private CatalogData data = new CatalogData();

    public CatalogManager() {
    }

    public static String cleanFormatting(String text) {
        if (text == null) return "";
        return MINECRAFT_FORMATTING.matcher(text).replaceAll("").trim();
    }

    private static String extractImageUrl(JsonElement buttonElem) {
        if (buttonElem == null || !buttonElem.isJsonObject()) return null;
        JsonObject obj = buttonElem.getAsJsonObject();
        if (obj.has("image") && obj.get("image").isJsonObject()) {
            JsonObject img = obj.getAsJsonObject("image");
            if (img.has("data") && !img.get("data").isJsonNull()) {
                return img.get("data").getAsString();
            }
        }
        return null;
    }

    public synchronized ParsedFormInfo processForm(String formJson, String accountName, String activeSubcategory) {
        ParsedFormInfo info = new ParsedFormInfo();
        if (formJson == null || formJson.isBlank()) {
            return info;
        }

        try {
            JsonObject root = JsonParser.parseString(formJson).getAsJsonObject();
            String title = root.has("title") ? cleanFormatting(root.get("title").getAsString()) : "";
            info.setTitle(title);

            if (!root.has("buttons") || !root.get("buttons").isJsonArray()) {
                return info;
            }

            JsonArray buttons = root.getAsJsonArray("buttons");
            boolean isMainForm = false;

            for (int i = 0; i < buttons.size(); i++) {
                String btnText = cleanFormatting(buttons.get(i).getAsJsonObject().get("text").getAsString());
                String line0 = btnText.split("\n")[0].trim();
                if (CATEGORY_TOKEN_AVAIL_PATTERN.matcher(btnText).find() || line0.equals("Buy Gifts")) {
                    isMainForm = true;
                    break;
                }
            }

            info.setMainForm(isMainForm);

            if (isMainForm) {
                this.data.setAccountName(accountName);

                // Checker: when the account has no gift tokens, Hive returns only 1 button: "Buy Gifts"
                boolean onlyBuyGiftsButton = false;
                if (buttons.size() == 1) {
                    String btn0 = cleanFormatting(buttons.get(0).getAsJsonObject().get("text").getAsString());
                    String line0 = btn0.split("\n")[0].trim();
                    if (line0.equals("Buy Gifts")) {
                        onlyBuyGiftsButton = true;
                    }
                }

                if (onlyBuyGiftsButton) {
                    info.setNoTokens(true);
                    info.setTokenBalance(0);
                    this.data.setAccountTokenBalance(0);
                    log.warn("Account has no gift tokens! /gift form returned only one button: 'Buy Gifts'.");
                }

                for (int i = 0; i < buttons.size(); i++) {
                    String raw = buttons.get(i).getAsJsonObject().get("text").getAsString();
                    String clean = cleanFormatting(raw);
                    String[] lines = clean.split("\n");
                    String btnName = lines[0].replaceFirst("(?i)^NEW\\s+", "").trim();

                    Matcher generalTokenAvail = GENERAL_TOKEN_AVAIL_PATTERN.matcher(clean);
                    if (generalTokenAvail.find()) {
                        int balance = Integer.parseInt(generalTokenAvail.group(1));
                        this.data.setAccountTokenBalance(balance);
                        info.setTokenBalance(balance);
                        if (balance == 0) {
                            info.setNoTokens(true);
                        }
                    }

                    Matcher categoryTokenAvail = CATEGORY_TOKEN_AVAIL_PATTERN.matcher(clean);
                    if (categoryTokenAvail.find()) {
                        int balance = Integer.parseInt(categoryTokenAvail.group(1));
                        info.getCategoryTokens().put(btnName, balance);
                        this.data.getCategoryTokens().put(btnName, balance);
                    }

                    if (btnName.toLowerCase().contains("buy gifts")) {
                        continue;
                    }

                    String imageUrl = extractImageUrl(buttons.get(i));
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        info.getCategoryImages().put(btnName, imageUrl);
                        this.data.getCategoryImages().put(btnName, imageUrl);
                    }

                    Matcher directCost = DIRECT_COST_PATTERN.matcher(clean);
                    if (directCost.find()) {
                        int minCost = Integer.parseInt(directCost.group(1));
                        int maxCost = directCost.group(2) != null ? Integer.parseInt(directCost.group(2)) : minCost;

                        Matcher stockMatch = STOCK_PATTERN.matcher(clean);
                        Integer stock = stockMatch.find() ? Integer.parseInt(stockMatch.group(1)) : null;

                        ItemEntry entry = new ItemEntry();
                        entry.setName(btnName);
                        entry.setCategory("Main Store");
                        entry.setTokenCost(minCost);
                        entry.setTokenCostMax(maxCost);
                        entry.setStock(stock);
                        entry.setImageUrl(imageUrl);

                        this.data.getItems().put(btnName, entry);
                        info.getItems().add(entry);
                    } else {
                        info.getSubcategories().add(new SubcategoryButton(btnName, i));
                    }
                }
            } else {
                String category = (activeSubcategory != null && !activeSubcategory.isBlank()) ? activeSubcategory : title;
                int backIdx = -1;
                for (int i = 0; i < buttons.size(); i++) {
                    String raw = buttons.get(i).getAsJsonObject().get("text").getAsString();
                    String clean = cleanFormatting(raw);
                    String[] lines = clean.split("\n");
                    String btnName = lines[0].replaceFirst("(?i)^NEW\\s+", "").trim();

                    if (btnName.equalsIgnoreCase("Go back")) {
                        backIdx = i;
                        continue;
                    }

                    if (btnName.equalsIgnoreCase("Search")) {
                        continue;
                    }

                    Matcher itemCost = SUB_ITEM_COST_PATTERN.matcher(clean);
                    Matcher directCost = DIRECT_COST_PATTERN.matcher(clean);
                    int cost = 1;
                    int maxCost = 1;
                    if (itemCost.find()) {
                        cost = Integer.parseInt(itemCost.group(1));
                        maxCost = cost;
                    } else if (directCost.find()) {
                        cost = Integer.parseInt(directCost.group(1));
                        maxCost = directCost.group(2) != null ? Integer.parseInt(directCost.group(2)) : cost;
                    }

                    Matcher stockMatch = STOCK_PATTERN.matcher(clean);
                    Integer stock = stockMatch.find() ? Integer.parseInt(stockMatch.group(1)) : null;

                    String imageUrl = extractImageUrl(buttons.get(i));
                    if (imageUrl == null || imageUrl.isBlank()) {
                        imageUrl = this.data.getCategoryImages().get(category);
                    }

                    ItemEntry entry = new ItemEntry();
                    entry.setName(btnName);
                    entry.setCategory(category.isEmpty() ? "Subcategory" : category);
                    entry.setTokenCost(cost);
                    entry.setTokenCostMax(maxCost);
                    entry.setStock(stock);
                    entry.setImageUrl(imageUrl);

                    this.data.getItems().put(btnName, entry);
                    info.getItems().add(entry);
                }
                info.setGoBackButtonIndex(backIdx);
            }
        } catch (Exception e) {
            log.error("Error processing modal form JSON", e);
        }

        return info;
    }

    public Optional<ItemEntry> findItem(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String q = query.trim();

        for (ItemEntry entry : data.getItems().values()) {
            if (entry.getName().equalsIgnoreCase(q)) {
                return Optional.of(entry);
            }
        }

        return Optional.empty();
    }

    @lombok.Getter
    @lombok.Setter
    private boolean jsonOutput = false;

    public String getItemReport(String query) {
        Optional<ItemEntry> opt = findItem(query);
        if (opt.isEmpty()) {
            if (jsonOutput) {
                JsonObject obj = new JsonObject();
                obj.addProperty("status", "error");
                obj.addProperty("message", "Item '" + query + "' was not found in store or any sub-menu.");
                return obj.toString();
            }
            return String.format("Failed: Item '%s' was not found in store or any sub-menu.", query);
        }

        ItemEntry item = opt.get();
        if (jsonOutput) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", item.getName());
            obj.addProperty("category", item.getCategory());
            obj.addProperty("cost", item.getTokenCost());
            if (item.getTokenCost() != item.getTokenCostMax()) {
                obj.addProperty("costMax", item.getTokenCostMax());
            }
            if (item.getStock() != null) {
                obj.addProperty("stock", item.getStock());
            }
            obj.addProperty("image", item.getImageUrl());
            return obj.toString();
        }

        String costStr = item.getTokenCost() == item.getTokenCostMax()
                ? item.getTokenCost() + " Tokens"
                : item.getTokenCost() + "-" + item.getTokenCostMax() + " Tokens";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Item: %s\n", item.getName()));
        sb.append(String.format("Cost: %s", costStr));
        if (item.getImageUrl() != null && !item.getImageUrl().isBlank()) {
            sb.append(String.format("\nImage: %s", item.getImageUrl()));
        }
        return sb.toString();
    }

    public void printCatalogSummary() {
        if (data.getItems().isEmpty()) {
            if (jsonOutput) {
                JsonObject obj = new JsonObject();
                obj.addProperty("status", "error");
                obj.addProperty("message", "Catalog is empty. No items found.");
                System.out.println(obj.toString());
            } else {
                System.out.println("Failed: Catalog is empty. No items found.");
            }
            return;
        }

        if (jsonOutput) {
            JsonObject root = new JsonObject();
            root.addProperty("tokens", data.getAccountTokenBalance());
            Integer costumeTokens = data.getCategoryTokens().get("Regular Costume");
            root.addProperty("costumeTokens", costumeTokens != null ? costumeTokens : 0);
            JsonObject catTokensObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : data.getCategoryTokens().entrySet()) {
                catTokensObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("categoryTokens", catTokensObj);

            JsonArray itemsArray = new JsonArray();
            for (ItemEntry item : data.getItems().values()) {
                JsonObject io = new JsonObject();
                io.addProperty("name", item.getName());
                io.addProperty("category", item.getCategory());
                io.addProperty("cost", item.getTokenCost());
                if (item.getTokenCost() != item.getTokenCostMax()) {
                    io.addProperty("costMax", item.getTokenCostMax());
                }
                if (item.getStock() != null) {
                    io.addProperty("stock", item.getStock());
                }
                io.addProperty("image", item.getImageUrl());
                itemsArray.add(io);
            }
            root.add("items", itemsArray);
            System.out.println(root.toString());
            return;
        }

        System.out.printf("Available Tokens: %d\n", data.getAccountTokenBalance());
        Integer plainCostumeTokens = data.getCategoryTokens().get("Regular Costume");
        if (plainCostumeTokens != null) {
            System.out.printf("Available Costume Tokens: %d\n", plainCostumeTokens);
        }

        Map<String, List<ItemEntry>> grouped = new TreeMap<>();
        for (ItemEntry entry : data.getItems().values()) {
            String cat = entry.getCategory() != null ? entry.getCategory() : "Other";
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<String, List<ItemEntry>> group : grouped.entrySet()) {
            System.out.printf("\n[%s]\n", group.getKey());
            for (ItemEntry item : group.getValue()) {
                String tokenStr = item.getTokenCost() == item.getTokenCostMax()
                        ? item.getTokenCost() + " Tokens"
                        : item.getTokenCost() + "-" + item.getTokenCostMax() + " Tokens";
                String stockStr = item.getStock() != null ? " [" + item.getStock() + " in stock]" : "";
                String imgStr = (item.getImageUrl() != null && !item.getImageUrl().isBlank())
                        ? " (" + item.getImageUrl() + ")"
                        : "";
                System.out.printf("- %s: %s%s%s\n",
                        item.getName(), tokenStr, stockStr, imgStr);
            }
        }
    }

    public CatalogData getData() {
        return data;
    }

    @Data
    public static class SubcategoryButton {
        private final String name;
        private final int buttonIndex;
    }

    @Data
    public static class ParsedFormInfo {
        private String title = "";
        private boolean isMainForm = false;
        private boolean noTokens = false;
        private int tokenBalance = -1;
        private final Map<String, Integer> categoryTokens = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, String> categoryImages = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final List<SubcategoryButton> subcategories = new ArrayList<>();
        private final List<ItemEntry> items = new ArrayList<>();
        private int goBackButtonIndex = -1;

        public boolean hasNoGiftTokens() {
            if (noTokens) return true;
            if (tokenBalance > 0) return false;
            for (int bal : categoryTokens.values()) {
                if (bal > 0) return false;
            }
            return tokenBalance == 0 && categoryTokens.isEmpty();
        }
    }
}
