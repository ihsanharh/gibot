package com.ihsanharh.gibot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class CatalogManagerTest {

    private CatalogManager catalogManager;

    @BeforeEach
    public void setup() {
        catalogManager = new CatalogManager();
    }

    @Test
    public void testZeroGiftTokensChecker() {
        // Modal form when account has 0 gift tokens: returns ONLY ONE BUTTON: "Buy Gifts"
        String zeroTokensForm = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(zeroTokensForm, "TestBot", null);

        assertTrue(info.isMainForm(), "Form with only 'Buy Gifts' button must be recognized as main /gift form");
        assertTrue(info.isNoTokens(), "Must flag noTokens when only 'Buy Gifts' button is returned");
        assertTrue(info.hasNoGiftTokens(), "hasNoGiftTokens() must return true");
        assertEquals(0, info.getTokenBalance(), "Token balance must be 0");
        assertEquals(0, catalogManager.getData().getAccountTokenBalance(), "Account token balance must be 0");
        assertTrue(info.getSubcategories().isEmpty(), "No subcategories should be present");
        assertTrue(info.getItems().isEmpty(), "No items should be present");
    }

    @Test
    public void testZeroTokensPatternInBuyGiftsButton() {
        // Modal form with Buy Gifts button explicitly showing 0 Gift Tokens Available
        String formWithZeroPattern = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts\\n§70 Gift Tokens Available"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(formWithZeroPattern, "TestBot", null);

        assertTrue(info.isMainForm());
        assertTrue(info.hasNoGiftTokens());
        assertEquals(0, info.getTokenBalance());
    }

    @Test
    public void testFormWithAvailableTokens() {
        // Modal form when account has tokens (e.g. 5 tokens) and category buttons
        String normalForm = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts\\n§75 Gift Tokens Available"
                },
                {
                    "text": "Hats\\n§7View all hats"
                },
                {
                    "text": "Costumes\\n§7View all costumes"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(normalForm, "TestBot", null);

        assertTrue(info.isMainForm());
        assertFalse(info.hasNoGiftTokens(), "Account has 5 tokens, must not flag no tokens");
        assertEquals(5, info.getTokenBalance());
        assertEquals(2, info.getSubcategories().size());
    }

    @Test
    public void testExactMatchingItemName() {
        // Subcategory form with items
        String subcategoryForm = """
        {
            "type": "form",
            "title": "Hats",
            "content": "",
            "buttons": [
                {
                    "text": "Cardboard Box\\n§a1 Token"
                },
                {
                    "text": "Axe Head\\n§a2 Tokens"
                },
                {
                    "text": "Go back"
                }
            ]
        }
        """;

        catalogManager.processForm(subcategoryForm, "TestBot", "Hats");

        // Exact match (case-insensitive) succeeds
        Optional<CatalogManager.ItemEntry> cardboardBox = catalogManager.findItem("Cardboard Box");
        assertTrue(cardboardBox.isPresent());
        assertEquals("Cardboard Box", cardboardBox.get().getName());

        Optional<CatalogManager.ItemEntry> lowerCardboard = catalogManager.findItem("cardboard box");
        assertTrue(lowerCardboard.isPresent());

        Optional<CatalogManager.ItemEntry> upperAxe = catalogManager.findItem("AXE HEAD");
        assertTrue(upperAxe.isPresent());

        // Partial match MUST fail / be rejected
        Optional<CatalogManager.ItemEntry> partialCardboard = catalogManager.findItem("cardboard");
        assertTrue(partialCardboard.isEmpty(), "Partial item name 'cardboard' must be rejected");

        Optional<CatalogManager.ItemEntry> partialBox = catalogManager.findItem("box");
        assertTrue(partialBox.isEmpty(), "Partial item name 'box' must be rejected");

        Optional<CatalogManager.ItemEntry> partialAxe = catalogManager.findItem("axe");
        assertTrue(partialAxe.isEmpty(), "Partial item name 'axe' must be rejected");

        // Extra words MUST fail / be rejected
        Optional<CatalogManager.ItemEntry> extraWords = catalogManager.findItem("cardboard box hat");
        assertTrue(extraWords.isEmpty(), "Extra words 'cardboard box hat' must be rejected");
    }

    @Test
    public void testCostumeTokensInMainMenu() {
        String mainFormWithCostumes = """
        {
            "type": "form",
            "title": "Gifting",
            "content": "You have gifts available! You can also buy more gifts using the Buy Gifts button.",
            "buttons": [
                {
                    "text": "Buy Gifts"
                },
                {
                    "image": {
                        "data": "https://cdn.playhive.com/icons/hub/gifts/costumes.png",
                        "type": "url"
                    },
                    "text": "§5§5Regular Costume\\n§a5 Available"
                },
                {
                    "image": {
                        "data": "https://cdn.playhive.com/icons/hub/gifts/pets.png",
                        "type": "url"
                    },
                    "text": "§5§5Regular Pet\\n§a7 Gift Tokens Available"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(mainFormWithCostumes, "TestBot", null);

        assertTrue(info.isMainForm());
        assertFalse(info.hasNoGiftTokens());
        assertEquals(7, info.getTokenBalance());
        assertEquals(5, info.getCategoryTokens().get("Regular Costume"));
        assertEquals(7, info.getCategoryTokens().get("Regular Pet"));
        assertEquals("https://cdn.playhive.com/icons/hub/gifts/costumes.png",
                catalogManager.getData().getCategoryImages().get("Regular Costume"));
        assertEquals(2, info.getSubcategories().size());
        assertEquals("Regular Costume", info.getSubcategories().get(0).getName());
        assertEquals(1, info.getSubcategories().get(0).getButtonIndex());
    }

    @Test
    public void testOnlyCostumeTokensAvailable() {
        // Account has 0 general gift tokens, but 5 costume tokens
        String onlyCostumesForm = """
        {
            "type": "form",
            "title": "Gifting",
            "content": "You have gifts available!",
            "buttons": [
                {
                    "text": "Buy Gifts"
                },
                {
                    "image": {
                        "data": "https://cdn.playhive.com/icons/hub/gifts/costumes.png",
                        "type": "url"
                    },
                    "text": "§5§5Regular Costume\\n§a5 Available"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(onlyCostumesForm, "TestBot", null);

        assertTrue(info.isMainForm());
        assertFalse(info.hasNoGiftTokens(), "Must not flag noTokens when category tokens are available");
        assertEquals(5, info.getCategoryTokens().get("Regular Costume"));
    }

    @Test
    public void testCostumesSubcategoryParsing() {
        // First parse main menu to load category images
        String mainForm = """
        {
            "type": "form",
            "title": "Gifting",
            "content": "",
            "buttons": [
                {
                    "image": {
                        "data": "https://cdn.playhive.com/icons/hub/gifts/costumes.png",
                        "type": "url"
                    },
                    "text": "§5§5Regular Costume\\n§a5 Available"
                }
            ]
        }
        """;
        catalogManager.processForm(mainForm, "TestBot", null);

        // Subcategory form like Form 10
        String costumeSubForm = """
        {
            "type": "form",
            "title": "Gifting",
            "content": "",
            "buttons": [
                {
                    "text": "Search"
                },
                {
                    "text": "§5Abyssal Angler"
                },
                {
                    "text": "§5Alien"
                },
                {
                    "text": "§5Owl"
                },
                {
                    "text": "Go back"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(costumeSubForm, "TestBot", "Regular Costume");

        assertFalse(info.isMainForm());
        assertEquals(4, info.getGoBackButtonIndex());
        assertEquals(3, info.getItems().size());

        Optional<CatalogManager.ItemEntry> owl = catalogManager.findItem("Owl");
        assertTrue(owl.isPresent());
        assertEquals("Owl", owl.get().getName());
        assertEquals("Regular Costume", owl.get().getCategory());
        assertEquals(1, owl.get().getTokenCost());
        assertEquals(1, owl.get().getTokenCostMax());
        assertEquals("https://cdn.playhive.com/icons/hub/gifts/costumes.png", owl.get().getImageUrl());

        Optional<CatalogManager.ItemEntry> angler = catalogManager.findItem("Abyssal Angler");
        assertTrue(angler.isPresent());
        assertEquals(1, angler.get().getTokenCost());

        // "Search" and "Go back" should not be parsed as items
        assertTrue(catalogManager.findItem("Search").isEmpty());
        assertTrue(catalogManager.findItem("Go back").isEmpty());
    }
}
